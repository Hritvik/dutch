package com.dutch.iam;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "iam.oauth.issuer=https://iam.test.invalid"
})
@AutoConfigureMockMvc
class IamApplicationTest {

    @DynamicPropertySource
    static void signingKey(DynamicPropertyRegistry registry) {
        String jwkSet = TestSigningJwkSet.create();
        registry.add("iam.oauth.signing-jwk-set-json", () -> jwkSet);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exposesItsOwnHealthEndpoint() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
