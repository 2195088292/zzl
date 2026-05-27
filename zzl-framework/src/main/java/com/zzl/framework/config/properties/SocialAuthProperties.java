package com.zzl.framework.config.properties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Third-party OAuth login settings.
 *
 * @author zzl
 */
@Component
@ConfigurationProperties(prefix = "zzl.social")
public class SocialAuthProperties
{
    private String frontendUrl = "http://localhost:8081";

    private String defaultRedirect = "/index";

    private boolean autoRegister = true;

    private Map<String, Provider> providers = new LinkedHashMap<>();

    public String getFrontendUrl()
    {
        return frontendUrl;
    }

    public void setFrontendUrl(String frontendUrl)
    {
        this.frontendUrl = frontendUrl;
    }

    public String getDefaultRedirect()
    {
        return defaultRedirect;
    }

    public void setDefaultRedirect(String defaultRedirect)
    {
        this.defaultRedirect = defaultRedirect;
    }

    public boolean isAutoRegister()
    {
        return autoRegister;
    }

    public void setAutoRegister(boolean autoRegister)
    {
        this.autoRegister = autoRegister;
    }

    public Map<String, Provider> getProviders()
    {
        return providers;
    }

    public void setProviders(Map<String, Provider> providers)
    {
        this.providers = providers;
    }

    public static class Provider
    {
        private String name;

        private String source;

        private boolean enabled;

        private String clientId;

        private String clientSecret;

        private String redirectUri;

        private List<String> scopes;

        private boolean unionId;

        public String getName()
        {
            return name;
        }

        public void setName(String name)
        {
            this.name = name;
        }

        public String getSource()
        {
            return source;
        }

        public void setSource(String source)
        {
            this.source = source;
        }

        public boolean isEnabled()
        {
            return enabled;
        }

        public void setEnabled(boolean enabled)
        {
            this.enabled = enabled;
        }

        public String getClientId()
        {
            return clientId;
        }

        public void setClientId(String clientId)
        {
            this.clientId = clientId;
        }

        public String getClientSecret()
        {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret)
        {
            this.clientSecret = clientSecret;
        }

        public String getRedirectUri()
        {
            return redirectUri;
        }

        public void setRedirectUri(String redirectUri)
        {
            this.redirectUri = redirectUri;
        }

        public List<String> getScopes()
        {
            return scopes;
        }

        public void setScopes(List<String> scopes)
        {
            this.scopes = scopes;
        }

        public boolean isUnionId()
        {
            return unionId;
        }

        public void setUnionId(boolean unionId)
        {
            this.unionId = unionId;
        }
    }
}
