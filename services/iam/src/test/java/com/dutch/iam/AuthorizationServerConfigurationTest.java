package com.dutch.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jwt.SignedJWT;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "iam.oauth.issuer=https://iam.test.invalid"
})
@AutoConfigureMockMvc
class AuthorizationServerConfigurationTest {

    @DynamicPropertySource
    static void signingKey(DynamicPropertyRegistry registry) {
        String jwkSet = TestSigningJwkSet.create();
        registry.add("iam.oauth.signing-jwk-set-json", () -> jwkSet);
    }

    @Autowired
    private RegisteredClientRepository clients;

    @Autowired
    private AuthorizationServerSettings settings;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void registers_the_public_mobile_client_with_exact_redirect_and_pkce_required() {
        var client = clients.findByClientId("dutch-mobile");

        assertThat(client.getRedirectUris()).containsExactly("dutch://oauth/callback");
        assertThat(client.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(client.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.NONE);
        assertThat(client.getAuthorizationGrantTypes()).contains(AuthorizationGrantType.AUTHORIZATION_CODE,
                AuthorizationGrantType.REFRESH_TOKEN);
        assertThat(client.getScopes()).containsExactly("openid");
    }

    @Test
    void exposes_a_configured_issuer_and_public_jwks_without_private_key_material() throws Exception {
        assertThat(settings.getIssuer()).isEqualTo("https://iam.test.invalid");

        mockMvc.perform(get("/oauth2/jwks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
                .andExpect(jsonPath("$.keys[0].d").doesNotExist());
    }

    @Test
    void exchanges_a_s256_protected_code_for_a_short_lived_signed_access_token() throws Exception {
        var authorization = mockMvc.perform(get("/oauth2/authorize")
                        .with(user("1b5a9f04-6e4e-4431-88e6-8fcaab876f46"))
                        .queryParam("response_type", "code")
                        .queryParam("client_id", "dutch-mobile")
                        .queryParam("redirect_uri", "dutch://oauth/callback")
                        .queryParam("scope", "openid")
                        .queryParam("state", "opaque-client-state")
                        .queryParam("code_challenge", "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
                        .queryParam("code_challenge_method", "S256"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String location = authorization.getResponse().getHeader("Location");
        String code = org.springframework.web.util.UriComponentsBuilder.fromUriString(location)
                .build().getQueryParams().getFirst("code");

        var token = mockMvc.perform(post("/oauth2/token")
                        .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("client_id", "dutch-mobile")
                        .param("redirect_uri", "dutch://oauth/callback")
                        .param("code", code)
                        .param("code_verifier", "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.expires_in").isNumber())
                .andReturn();

        var body = new ObjectMapper().readTree(token.getResponse().getContentAsString());
        var accessToken = SignedJWT.parse(body.required("access_token").asText());
        assertThat(accessToken.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(accessToken.getJWTClaimsSet().getIssuer()).isEqualTo("https://iam.test.invalid");
        assertThat(accessToken.getJWTClaimsSet().getSubject()).isEqualTo("1b5a9f04-6e4e-4431-88e6-8fcaab876f46");
        assertThat(body.required("expires_in").asLong()).isLessThanOrEqualTo(900);
    }
}
