package com.urlshortener.redirect.controller;

import com.urlshortener.redirect.config.RedirectServiceProperties;
import com.urlshortener.redirect.service.RedirectResult;
import com.urlshortener.redirect.service.RedirectService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice tests for {@link RedirectController} focusing on HTTP response headers.
 *
 * <p>These tests verify the CDN caching strategy:
 * <ul>
 *   <li>302 Found responses carry {@code Cache-Control: public, max-age=N, s-maxage=N}
 *       so the upstream CDN caches the redirect at the edge.</li>
 *   <li>302 Found responses carry {@code Surrogate-Key} and {@code Cache-Tag} headers
 *       set to {@code url-{shortKey}} for tag-based CDN purge on URL deletion.</li>
 *   <li>404 and 410 responses carry {@code Cache-Control: no-store} — negative results
 *       must never be cached by CDN or browser.</li>
 * </ul>
 */
@WebMvcTest(RedirectController.class)
@Import(RedirectServiceProperties.class)
class RedirectControllerTest {

    private static final String SHORT_KEY = "aB3xY9z";
    private static final String LONG_URL  = "https://www.example.com/some/long/path";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RedirectService redirectService;

    // ── 302 Found — CDN cache headers ────────────────────────────────────────

    @Test
    @DisplayName("302 Found sets Cache-Control public with max-age and s-maxage")
    void found_setsPublicCacheControl() throws Exception {
        when(redirectService.redirect(eq(SHORT_KEY), any()))
                .thenReturn(new RedirectResult.Found(LONG_URL));

        mockMvc.perform(get("/{shortKey}", SHORT_KEY))
                .andExpect(status().isFound())
                .andExpect(header().string("Cache-Control",
                        containsString("public")))
                .andExpect(header().string("Cache-Control",
                        containsString("max-age=")))
                .andExpect(header().string("Cache-Control",
                        containsString("s-maxage=")));
    }

    @Test
    @DisplayName("302 Found sets Surrogate-Key header for Fastly tag-based purge")
    void found_setsSurrogateKeyHeader() throws Exception {
        when(redirectService.redirect(eq(SHORT_KEY), any()))
                .thenReturn(new RedirectResult.Found(LONG_URL));

        mockMvc.perform(get("/{shortKey}", SHORT_KEY))
                .andExpect(status().isFound())
                .andExpect(header().string("Surrogate-Key", "url-" + SHORT_KEY));
    }

    @Test
    @DisplayName("302 Found sets Cache-Tag header for Cloudflare tag-based purge")
    void found_setsCacheTagHeader() throws Exception {
        when(redirectService.redirect(eq(SHORT_KEY), any()))
                .thenReturn(new RedirectResult.Found(LONG_URL));

        mockMvc.perform(get("/{shortKey}", SHORT_KEY))
                .andExpect(status().isFound())
                .andExpect(header().string("Cache-Tag", "url-" + SHORT_KEY));
    }

    @Test
    @DisplayName("302 Found sets Location header to the long URL")
    void found_setsLocationHeader() throws Exception {
        when(redirectService.redirect(eq(SHORT_KEY), any()))
                .thenReturn(new RedirectResult.Found(LONG_URL));

        mockMvc.perform(get("/{shortKey}", SHORT_KEY))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", LONG_URL));
    }

    // ── 404 Not Found — must NOT be CDN-cached ────────────────────────────────

    @Test
    @DisplayName("404 Not Found sets Cache-Control: no-store to prevent negative caching")
    void notFound_setsNoCacheControl() throws Exception {
        when(redirectService.redirect(eq(SHORT_KEY), any()))
                .thenReturn(new RedirectResult.NotFound());

        mockMvc.perform(get("/{shortKey}", SHORT_KEY))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    @DisplayName("404 Not Found does NOT set Surrogate-Key or Cache-Tag headers")
    void notFound_doesNotSetCdnTagHeaders() throws Exception {
        when(redirectService.redirect(eq(SHORT_KEY), any()))
                .thenReturn(new RedirectResult.NotFound());

        mockMvc.perform(get("/{shortKey}", SHORT_KEY))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist("Surrogate-Key"))
                .andExpect(header().doesNotExist("Cache-Tag"));
    }

    // ── 410 Gone — must NOT be CDN-cached ────────────────────────────────────

    @Test
    @DisplayName("410 Gone sets Cache-Control: no-store to prevent negative caching")
    void gone_setsNoCacheControl() throws Exception {
        when(redirectService.redirect(eq(SHORT_KEY), any()))
                .thenReturn(new RedirectResult.Gone());

        mockMvc.perform(get("/{shortKey}", SHORT_KEY))
                .andExpect(status().isGone())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    @DisplayName("410 Gone does NOT set Surrogate-Key or Cache-Tag headers")
    void gone_doesNotSetCdnTagHeaders() throws Exception {
        when(redirectService.redirect(eq(SHORT_KEY), any()))
                .thenReturn(new RedirectResult.Gone());

        mockMvc.perform(get("/{shortKey}", SHORT_KEY))
                .andExpect(status().isGone())
                .andExpect(header().doesNotExist("Surrogate-Key"))
                .andExpect(header().doesNotExist("Cache-Tag"));
    }

    // ── Input validation ──────────────────────────────────────────────────────

    @Test
    @DisplayName("400 Bad Request for short key containing invalid characters")
    void invalidShortKey_returns400() throws Exception {
        mockMvc.perform(get("/{shortKey}", "invalid!!key"))
                .andExpect(status().isBadRequest());
    }
}
