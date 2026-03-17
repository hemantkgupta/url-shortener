package com.urlshortener.write.service;

import com.urlshortener.write.config.WriteServiceProperties;
import com.urlshortener.write.exception.MaliciousUrlException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link UrlValidationService}.
 *
 * <p>No Spring context is started; the service is constructed directly with
 * a hand-built {@link WriteServiceProperties}.
 */
class UrlValidationServiceTest {

    private static final String OWN_DOMAIN = "short.ly";
    private static final String BLOCKED_HOST = "malware.example.com";

    private WriteServiceProperties properties;
    private UrlValidationService validationService;

    @BeforeEach
    void setUp() {
        properties = new WriteServiceProperties();
        properties.setOwnDomain(OWN_DOMAIN);

        // Safe browsing disabled by default; individual tests override as needed
        WriteServiceProperties.SafeBrowsing sb = new WriteServiceProperties.SafeBrowsing();
        sb.setEnabled(false);
        sb.setBlocklist(List.of(BLOCKED_HOST));
        properties.setSafeBrowsing(sb);

        validationService = new UrlValidationService(properties);
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("valid HTTPS URL passes validation and is normalised")
    void validHttpsUrlPasses() {
        String result = validationService.validateAndNormalize("https://EXAMPLE.COM/path?q=1");

        // Scheme and host must be lowercased; path/query preserved
        assertThat(result).startsWith("https://example.com/path");
        assertThat(result).contains("q=1");
    }

    @Test
    @DisplayName("valid HTTP URL passes validation")
    void validHttpUrlPasses() {
        String result = validationService.validateAndNormalize("http://example.com");
        assertThat(result).isEqualTo("http://example.com");
    }

    // ── Length check ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("URL exceeding 2048 characters throws IllegalArgumentException")
    void urlExceedingMaxLengthThrows() {
        String tooLong = "https://example.com/" + "a".repeat(2040);

        assertThatThrownBy(() -> validationService.validateAndNormalize(tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2048");
    }

    @Test
    @DisplayName("URL of exactly 2048 characters passes")
    void urlAtMaxLengthPasses() {
        // Build a URL that is exactly 2048 chars
        int prefixLen = "https://example.com/".length();
        String url = "https://example.com/" + "a".repeat(2048 - prefixLen);
        assertThat(url).hasSize(2048);

        // Should not throw
        String result = validationService.validateAndNormalize(url);
        assertThat(result).isNotBlank();
    }

    // ── Null / blank checks ───────────────────────────────────────────────────

    @Test
    @DisplayName("null URL throws IllegalArgumentException")
    void nullUrlThrows() {
        assertThatThrownBy(() -> validationService.validateAndNormalize(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null or blank");
    }

    @Test
    @DisplayName("blank URL throws IllegalArgumentException")
    void blankUrlThrows() {
        assertThatThrownBy(() -> validationService.validateAndNormalize("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null or blank");
    }

    // ── Circular redirect ─────────────────────────────────────────────────────

    @Test
    @DisplayName("URL pointing to own domain is detected as circular redirect")
    void circularRedirectReturnsTrue() {
        assertThat(validationService.isCircularRedirect("https://" + OWN_DOMAIN + "/abc"))
                .isTrue();
    }

    @Test
    @DisplayName("circular redirect detected during validateAndNormalize throws")
    void circularRedirectThrowsDuringValidation() {
        assertThatThrownBy(() ->
                validationService.validateAndNormalize("https://" + OWN_DOMAIN + "/abc"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Circular redirect");
    }

    @Test
    @DisplayName("URL pointing to a different domain is not flagged as circular")
    void differentDomainNotCircular() {
        assertThat(validationService.isCircularRedirect("https://example.com/abc"))
                .isFalse();
    }

    // ── Safe Browsing ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("malicious URL (in blocklist, safeBrowsing.enabled=true) throws MaliciousUrlException")
    void maliciousUrlThrowsWhenSafeBrowsingEnabled() {
        // Enable safe browsing
        properties.getSafeBrowsing().setEnabled(true);
        validationService = new UrlValidationService(properties);

        assertThatThrownBy(() ->
                validationService.validateAndNormalize("https://" + BLOCKED_HOST + "/payload"))
                .isInstanceOf(MaliciousUrlException.class)
                .hasMessageContaining(BLOCKED_HOST);
    }

    @Test
    @DisplayName("blocked host is allowed through when safeBrowsing.enabled=false")
    void blockedHostPassesWhenSafeBrowsingDisabled() {
        // Safe browsing is disabled in setUp — blocked host should pass
        String result = validationService.validateAndNormalize(
                "https://" + BLOCKED_HOST + "/page");
        assertThat(result).contains(BLOCKED_HOST);
    }

    @Test
    @DisplayName("non-malicious URL passes Safe Browsing check when enabled")
    void nonMaliciousUrlPassesSafeBrowsing() {
        properties.getSafeBrowsing().setEnabled(true);
        validationService = new UrlValidationService(properties);

        // Should not throw
        String result = validationService.validateAndNormalize("https://legitimate.example.org/page");
        assertThat(result).isNotBlank();
    }

    // ── Scheme check ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("ftp:// URL is rejected")
    void ftpSchemeIsRejected() {
        assertThatThrownBy(() -> validationService.validateAndNormalize("ftp://files.example.com/file.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http or https");
    }
}
