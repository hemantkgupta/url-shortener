package com.urlshortener.write.controller;

import com.urlshortener.core.auth.AuthenticatedUser;
import com.urlshortener.core.dto.ShortenResponse;
import com.urlshortener.write.security.AuthenticatedUserResolver;
import com.urlshortener.write.security.CreateRateLimitService;
import com.urlshortener.write.security.TrustedClientResolver;
import com.urlshortener.write.security.WriteSecurityConfig;
import com.urlshortener.write.service.WriteService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = WriteController.class, excludeAutoConfiguration = OAuth2ResourceServerAutoConfiguration.class)
@Import(WriteSecurityConfig.class)
class WriteControllerSecurityTest {

    private static final AuthenticatedUser CURRENT_USER =
            new AuthenticatedUser(42L, "google-subject", "user@example.com", "Test User");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WriteService writeService;

    @MockBean
    private AuthenticatedUserResolver authenticatedUserResolver;

    @MockBean
    private TrustedClientResolver trustedClientResolver;

    @MockBean
    private CreateRateLimitService createRateLimitService;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("delete requires authentication")
    void deleteRequiresAuthentication() throws Exception {
        mockMvc.perform(delete("/v1/urls/demo-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("create is allowed anonymously and still runs rate limiting")
    void createAllowsAnonymousRequests() throws Exception {
        when(authenticatedUserResolver.resolve(any())).thenReturn(Optional.empty());
        when(trustedClientResolver.resolveRateLimitClientId(any(), any())).thenReturn("ip:127.0.0.1");
        when(writeService.shorten(any(), any())).thenReturn(
                ShortenResponse.of(
                        "https://short.ly/demo-key",
                        "demo-key",
                        "https://example.com",
                        Instant.now().plusSeconds(3600),
                        Instant.now()));

        mockMvc.perform(post("/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "longUrl": "https://example.com"
                                }
                                """))
                .andExpect(status().isCreated());

        verify(createRateLimitService).checkCreateAllowed(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("delete succeeds for authenticated users")
    void deleteAllowsAuthenticatedRequests() throws Exception {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", CURRENT_USER.subject())
                .build();
        when(authenticatedUserResolver.require(any())).thenReturn(CURRENT_USER);

        mockMvc.perform(delete("/v1/urls/demo-key").with(jwt().jwt(jwt)))
                .andExpect(status().isNoContent());
    }
}
