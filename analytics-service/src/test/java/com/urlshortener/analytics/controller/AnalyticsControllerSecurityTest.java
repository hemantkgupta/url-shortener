package com.urlshortener.analytics.controller;

import com.urlshortener.analytics.security.AnalyticsAuthenticatedUserResolver;
import com.urlshortener.analytics.security.AnalyticsSecurityConfig;
import com.urlshortener.analytics.service.AnalyticsQueryService;
import com.urlshortener.analytics.service.LinkManagementService;
import com.urlshortener.analytics.service.LinkOwnershipService;
import com.urlshortener.core.auth.AuthenticatedUser;
import com.urlshortener.core.dto.AnalyticsResponse;
import com.urlshortener.core.dto.ShortenResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AnalyticsController.class, excludeAutoConfiguration = OAuth2ResourceServerAutoConfiguration.class)
@Import(AnalyticsSecurityConfig.class)
class AnalyticsControllerSecurityTest {

    private static final AuthenticatedUser CURRENT_USER =
            new AuthenticatedUser(42L, "google-subject", "user@example.com", "Test User");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AnalyticsQueryService analyticsQueryService;

    @MockBean
    private LinkManagementService linkManagementService;

    @MockBean
    private LinkOwnershipService linkOwnershipService;

    @MockBean
    private AnalyticsAuthenticatedUserResolver authenticatedUserResolver;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("list endpoint requires authentication")
    void listRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/v1/urls"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("list endpoint returns owned links for authenticated users")
    void listReturnsOwnedLinksForAuthenticatedUsers() throws Exception {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", CURRENT_USER.subject())
                .build();
        when(authenticatedUserResolver.require(any())).thenReturn(CURRENT_USER);
        when(linkManagementService.getUserLinks(anyLong(), any(Integer.class), any(Integer.class)))
                .thenReturn(List.of(ShortenResponse.of(
                        "https://short.ly/demo-key",
                        "demo-key",
                        "https://example.com",
                        Instant.now().plusSeconds(3600),
                        Instant.now())));

        mockMvc.perform(get("/v1/urls").with(jwt().jwt(jwt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].shortKey").value("demo-key"));
    }

    @Test
    @DisplayName("analytics endpoint enforces ownership for authenticated users")
    void analyticsRequiresOwnership() throws Exception {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", CURRENT_USER.subject())
                .build();
        when(authenticatedUserResolver.require(any())).thenReturn(CURRENT_USER);
        doNothing().when(linkOwnershipService).assertOwner("demo-key", CURRENT_USER.userId());
        when(analyticsQueryService.getAnalytics(anyString(), any(), any(), anyString()))
                .thenReturn(AnalyticsResponse.builder()
                        .shortKey("demo-key")
                        .totalClicks(12L)
                        .clicksByDay(Map.of("2026-03-18", 12L))
                        .clicksByCountry(Map.of("IN", 12L))
                        .clicksByDevice(Map.of("desktop", 12L))
                        .clicksByReferrer(Map.of("(unknown)", 12L))
                        .periodFrom(Instant.parse("2026-03-01T00:00:00Z"))
                        .periodTo(Instant.parse("2026-03-18T00:00:00Z"))
                        .build());

        mockMvc.perform(get("/v1/urls/demo-key/analytics").with(jwt().jwt(jwt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortKey").value("demo-key"))
                .andExpect(jsonPath("$.totalClicks").value(12));
    }
}
