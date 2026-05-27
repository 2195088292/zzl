package com.zzl.web.controller.system;

import me.zhyd.oauth.model.AuthCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;
import com.zzl.common.core.domain.AjaxResult;
import com.zzl.framework.web.service.SysSocialAuthService;
import com.zzl.framework.web.service.SysSocialAuthService.SocialLoginResult;

/**
 * Third-party OAuth login.
 *
 * @author zzl
 */
@RestController
@RequestMapping("/auth/social")
public class SysSocialAuthController
{
    @Autowired
    private SysSocialAuthService socialAuthService;

    @GetMapping("/providers")
    public AjaxResult providers()
    {
        return AjaxResult.success(socialAuthService.getProviders());
    }

    @GetMapping("/{provider}/authorize")
    public RedirectView authorize(@PathVariable String provider,
            @RequestParam(defaultValue = "login") String mode,
            @RequestParam(defaultValue = "/index") String redirect)
    {
        try
        {
            return new RedirectView(socialAuthService.authorize(provider, mode, redirect));
        }
        catch (Exception ex)
        {
            return new RedirectView(socialAuthService.buildFrontendErrorUrl(ex.getMessage(), redirect));
        }
    }

    @GetMapping("/{provider}/callback")
    public RedirectView callback(@PathVariable String provider, AuthCallback callback)
    {
        try
        {
            SocialLoginResult result = socialAuthService.callback(provider, callback);
            return new RedirectView(socialAuthService.buildFrontendSuccessUrl(result));
        }
        catch (Exception ex)
        {
            return new RedirectView(socialAuthService.buildFrontendErrorUrl(ex.getMessage(), "/index"));
        }
    }
}
