package com.dutch.iam.oauth;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;

/**
 * The issuer is an external security boundary. It is deliberately required from
 * deployment configuration rather than inferred from an incoming HTTP request.
 */
@Configuration(proxyBeanMethods = false)
public class AuthorizationServerIssuerConfiguration {

    @Bean
    AuthorizationServerSettings authorizationServerSettings(@Value("${iam.oauth.issuer}") String issuer) {
        URI parsed = URI.create(issuer);
        if (!"https".equals(parsed.getScheme()) || parsed.getHost() == null
                || parsed.getRawQuery() != null || parsed.getRawFragment() != null) {
            throw new IllegalArgumentException("IAM OAuth issuer must be an absolute HTTPS origin.");
        }
        return AuthorizationServerSettings.builder().issuer(issuer).build();
    }
}
