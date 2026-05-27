package com.zzl.framework.web.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import me.zhyd.oauth.AuthRequestBuilder;
import me.zhyd.oauth.cache.AuthDefaultStateCache;
import me.zhyd.oauth.config.AuthConfig;
import me.zhyd.oauth.model.AuthCallback;
import me.zhyd.oauth.model.AuthResponse;
import me.zhyd.oauth.model.AuthUser;
import me.zhyd.oauth.request.AuthRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import com.zzl.common.constant.Constants;
import com.zzl.common.core.domain.entity.SysUser;
import com.zzl.common.core.domain.model.LoginUser;
import com.zzl.common.enums.UserStatus;
import com.zzl.common.exception.ServiceException;
import com.zzl.common.utils.DateUtils;
import com.zzl.common.utils.SecurityUtils;
import com.zzl.common.utils.StringUtils;
import com.zzl.common.utils.ip.IpUtils;
import com.zzl.framework.config.properties.SocialAuthProperties;
import com.zzl.system.service.ISysConfigService;
import com.zzl.system.service.ISysUserService;

/**
 * Third-party OAuth login and auto-registration.
 *
 * @author zzl
 */
@Component
public class SysSocialAuthService
{
    private static final String STATE_SEPARATOR = ".";

    @Autowired
    private SocialAuthProperties socialAuthProperties;

    @Autowired
    private ISysUserService userService;

    @Autowired
    private ISysConfigService configService;

    @Autowired
    private SysPermissionService permissionService;

    @Autowired
    private TokenService tokenService;

    public List<SocialProviderView> getProviders()
    {
        List<SocialProviderView> providers = new ArrayList<>();
        for (Map.Entry<String, SocialAuthProperties.Provider> entry : socialAuthProperties.getProviders().entrySet())
        {
            SocialAuthProperties.Provider provider = entry.getValue();
            providers.add(new SocialProviderView(entry.getKey(), StringUtils.nvl(provider.getName(), entry.getKey()),
                    isProviderReady(provider)));
        }
        return providers;
    }

    public String authorize(String providerKey, String mode, String redirect)
    {
        String safeRedirect = normalizeRedirect(redirect);
        AuthRequest request = buildRequest(providerKey);
        return request.authorize(encodeState(mode, safeRedirect));
    }

    public SocialLoginResult callback(String providerKey, AuthCallback callback)
    {
        StatePayload statePayload = decodeState(callback.getState());
        AuthResponse<AuthUser> response = buildRequest(providerKey).login(callback);
        if (response == null || !response.ok() || response.getData() == null)
        {
            throw new ServiceException("第三方授权失败：" + (response == null ? "无响应" : response.getMsg()));
        }

        SysUser user = findOrCreateUser(providerKey, response.getData());
        LoginUser loginUser = new LoginUser(user.getUserId(), user.getDeptId(), user, permissionService.getMenuPermission(user));
        String token = tokenService.createToken(loginUser);
        userService.updateLoginInfo(user.getUserId(), IpUtils.getIpAddr(), DateUtils.getNowDate());
        return new SocialLoginResult(token, statePayload.redirect());
    }

    public String buildFrontendSuccessUrl(SocialLoginResult result)
    {
        return UriComponentsBuilder.fromUriString(socialAuthProperties.getFrontendUrl())
                .path("/social-callback")
                .queryParam(Constants.TOKEN, result.token())
                .queryParam("redirect", normalizeRedirect(result.redirect()))
                .build()
                .encode()
                .toUriString();
    }

    public String buildFrontendErrorUrl(String message, String redirect)
    {
        return UriComponentsBuilder.fromUriString(socialAuthProperties.getFrontendUrl())
                .path("/social-callback")
                .queryParam("error", StringUtils.nvl(message, "第三方登录失败"))
                .queryParam("redirect", normalizeRedirect(redirect))
                .build()
                .encode()
                .toUriString();
    }

    private AuthRequest buildRequest(String providerKey)
    {
        SocialAuthProperties.Provider provider = getProvider(providerKey);
        AuthConfig authConfig = AuthConfig.builder()
                .clientId(provider.getClientId())
                .clientSecret(provider.getClientSecret())
                .redirectUri(provider.getRedirectUri())
                .scopes(provider.getScopes())
                .unionId(provider.isUnionId())
                .build();

        return AuthRequestBuilder.builder()
                .source(StringUtils.nvl(provider.getSource(), providerKey).toUpperCase(Locale.ROOT))
                .authConfig(authConfig)
                .authStateCache(AuthDefaultStateCache.INSTANCE)
                .build();
    }

    private SocialAuthProperties.Provider getProvider(String providerKey)
    {
        String key = StringUtils.nvl(providerKey, "").toLowerCase(Locale.ROOT);
        SocialAuthProperties.Provider provider = socialAuthProperties.getProviders().get(key);
        if (provider == null)
        {
            throw new ServiceException("暂不支持该第三方登录：" + providerKey);
        }
        if (!isProviderReady(provider))
        {
            throw new ServiceException(StringUtils.nvl(provider.getName(), providerKey) + " 登录尚未配置，请先填写 client-id/client-secret 并启用");
        }
        return provider;
    }

    private boolean isProviderReady(SocialAuthProperties.Provider provider)
    {
        return provider != null && provider.isEnabled()
                && StringUtils.isNotEmpty(provider.getClientId())
                && StringUtils.isNotEmpty(provider.getClientSecret())
                && StringUtils.isNotEmpty(provider.getRedirectUri());
    }

    private SysUser findOrCreateUser(String providerKey, AuthUser authUser)
    {
        String userName = buildSocialUserName(providerKey, authUser);
        SysUser user = userService.selectUserByUserName(userName);
        if (user == null && StringUtils.isNotEmpty(authUser.getEmail()))
        {
            user = userService.selectUserByEmail(authUser.getEmail());
        }
        if (user != null)
        {
            return user;
        }
        if (!socialAuthProperties.isAutoRegister() || !"true".equals(configService.selectConfigByKey("sys.account.registerUser")))
        {
            throw new ServiceException("账号不存在，且系统未开启第三方自动注册");
        }

        SysUser socialUser = new SysUser();
        socialUser.setUserName(userName);
        socialUser.setNickName(firstNotEmpty(authUser.getNickname(), authUser.getUsername(), userName));
        socialUser.setEmail(authUser.getEmail());
        socialUser.setAvatar(authUser.getAvatar());
        socialUser.setStatus(UserStatus.OK.getCode());
        socialUser.setPwdUpdateDate(DateUtils.getNowDate());
        socialUser.setPassword(SecurityUtils.encryptPassword(UUID.randomUUID().toString()));
        socialUser.setCreateBy("social:" + providerKey);
        socialUser.setRemark("OAuth auto-created from " + providerKey + ", uuid=" + authUser.getUuid());
        if (!userService.registerUser(socialUser))
        {
            throw new ServiceException("第三方账号自动注册失败");
        }
        return userService.selectUserByUserName(userName);
    }

    private String buildSocialUserName(String providerKey, AuthUser authUser)
    {
        String raw = providerKey + "_" + firstNotEmpty(authUser.getUuid(), authUser.getUsername(), UUID.randomUUID().toString());
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }

    private String firstNotEmpty(String... values)
    {
        for (String value : values)
        {
            if (StringUtils.isNotEmpty(value))
            {
                return value;
            }
        }
        return "";
    }

    private String normalizeRedirect(String redirect)
    {
        String value = StringUtils.nvl(redirect, socialAuthProperties.getDefaultRedirect());
        if (!value.startsWith("/") || value.startsWith("//"))
        {
            return socialAuthProperties.getDefaultRedirect();
        }
        return value;
    }

    private String encodeState(String mode, String redirect)
    {
        String normalizedMode = "register".equalsIgnoreCase(mode) ? "register" : "login";
        String encodedRedirect = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(normalizeRedirect(redirect).getBytes(StandardCharsets.UTF_8));
        return normalizedMode + STATE_SEPARATOR + encodedRedirect + STATE_SEPARATOR + UUID.randomUUID();
    }

    private StatePayload decodeState(String state)
    {
        if (StringUtils.isEmpty(state))
        {
            return new StatePayload("login", socialAuthProperties.getDefaultRedirect());
        }
        String[] parts = state.split("\\" + STATE_SEPARATOR, 3);
        if (parts.length < 2)
        {
            return new StatePayload("login", socialAuthProperties.getDefaultRedirect());
        }
        try
        {
            String redirect = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            return new StatePayload(parts[0], normalizeRedirect(redirect));
        }
        catch (IllegalArgumentException ex)
        {
            return new StatePayload("login", socialAuthProperties.getDefaultRedirect());
        }
    }

    public record SocialProviderView(String key, String name, boolean enabled)
    {
    }

    public record SocialLoginResult(String token, String redirect)
    {
    }

    private record StatePayload(String mode, String redirect)
    {
    }
}
