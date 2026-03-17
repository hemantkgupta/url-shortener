package com.urlshortener.redirect.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DeviceParser}.
 *
 * <p>Verifies device-type and browser-family classification from raw User-Agent strings.
 * No Spring context is required — {@link DeviceParser} is a stateless utility class.
 */
class DeviceParserTest {

    // ── Real-world User-Agent samples ──────────────────────────────────────────

    /**
     * iPhone running Safari — classic mobile UA.
     */
    private static final String IPHONE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";

    /**
     * Android phone with Chrome Mobile — matches "Android.*Mobile".
     */
    private static final String ANDROID_MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    /**
     * iPad running Safari — tablet, not mobile.
     */
    private static final String IPAD_UA =
            "Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";

    /**
     * Windows desktop with Chrome — no mobile/tablet signals.
     */
    private static final String CHROME_DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.130 Safari/537.36";

    /**
     * macOS desktop with Firefox.
     */
    private static final String FIREFOX_DESKTOP_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14.2; rv:121.0) Gecko/20100101 Firefox/121.0";

    /**
     * macOS with Safari (no Chrome or Firefox token).
     */
    private static final String SAFARI_DESKTOP_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_2_1) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Safari/605.1.15";

    /**
     * Edge on Windows — UA contains both "Edg/" and "Chrome/"; Edge must win.
     */
    private static final String EDGE_DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0";

    /**
     * Completely unknown bot-style UA with no recognisable tokens.
     */
    private static final String UNKNOWN_UA = "CustomFetcher/1.0";

    // =========================================================================
    // parseDevice tests
    // =========================================================================

    @Test
    @DisplayName("parseDevice: iPhone UA → 'mobile'")
    void parseDevice_iphone_returnsMobile() {
        assertThat(DeviceParser.parseDevice(IPHONE_UA)).isEqualTo("mobile");
    }

    @Test
    @DisplayName("parseDevice: Android Mobile UA → 'mobile'")
    void parseDevice_androidMobile_returnsMobile() {
        assertThat(DeviceParser.parseDevice(ANDROID_MOBILE_UA)).isEqualTo("mobile");
    }

    @Test
    @DisplayName("parseDevice: iPad UA → 'tablet'")
    void parseDevice_ipad_returnsTablet() {
        assertThat(DeviceParser.parseDevice(IPAD_UA)).isEqualTo("tablet");
    }

    @Test
    @DisplayName("parseDevice: Chrome desktop UA → 'desktop'")
    void parseDevice_chromeDesktop_returnsDesktop() {
        assertThat(DeviceParser.parseDevice(CHROME_DESKTOP_UA)).isEqualTo("desktop");
    }

    @Test
    @DisplayName("parseDevice: Firefox desktop UA → 'desktop'")
    void parseDevice_firefoxDesktop_returnsDesktop() {
        assertThat(DeviceParser.parseDevice(FIREFOX_DESKTOP_UA)).isEqualTo("desktop");
    }

    @Test
    @DisplayName("parseDevice: unknown bot UA → 'desktop' (fallback)")
    void parseDevice_unknownUA_returnsDesktop() {
        assertThat(DeviceParser.parseDevice(UNKNOWN_UA)).isEqualTo("desktop");
    }

    @ParameterizedTest(name = "parseDevice: null/blank UA → 'desktop'")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    @DisplayName("parseDevice: null or blank UA → 'desktop'")
    void parseDevice_nullOrBlank_returnsDesktop(String userAgent) {
        // Per implementation: null/blank UA returns "desktop" (no mobile signal found)
        assertThat(DeviceParser.parseDevice(userAgent)).isEqualTo("desktop");
    }

    // =========================================================================
    // parseBrowser tests
    // =========================================================================

    @Test
    @DisplayName("parseBrowser: Chrome desktop UA → 'Chrome'")
    void parseBrowser_chromeDesktop_returnsChrome() {
        assertThat(DeviceParser.parseBrowser(CHROME_DESKTOP_UA)).isEqualTo("Chrome");
    }

    @Test
    @DisplayName("parseBrowser: Firefox desktop UA → 'Firefox'")
    void parseBrowser_firefoxDesktop_returnsFirefox() {
        assertThat(DeviceParser.parseBrowser(FIREFOX_DESKTOP_UA)).isEqualTo("Firefox");
    }

    @Test
    @DisplayName("parseBrowser: Safari desktop UA → 'Safari'")
    void parseBrowser_safariDesktop_returnsSafari() {
        assertThat(DeviceParser.parseBrowser(SAFARI_DESKTOP_UA)).isEqualTo("Safari");
    }

    @Test
    @DisplayName("parseBrowser: Edge UA (contains Chrome token) → 'Edge', not 'Chrome'")
    void parseBrowser_edgeUA_returnsEdge_notChrome() {
        // Edge UA includes "Chrome/" — verify Edge is matched first
        assertThat(DeviceParser.parseBrowser(EDGE_DESKTOP_UA)).isEqualTo("Edge");
    }

    @Test
    @DisplayName("parseBrowser: iPhone Safari UA → 'Safari'")
    void parseBrowser_iphoneSafari_returnsSafari() {
        assertThat(DeviceParser.parseBrowser(IPHONE_UA)).isEqualTo("Safari");
    }

    @Test
    @DisplayName("parseBrowser: Android Chrome Mobile UA → 'Chrome'")
    void parseBrowser_androidChromeMobile_returnsChrome() {
        assertThat(DeviceParser.parseBrowser(ANDROID_MOBILE_UA)).isEqualTo("Chrome");
    }

    @Test
    @DisplayName("parseBrowser: completely unknown UA → 'Other'")
    void parseBrowser_unknownUA_returnsOther() {
        assertThat(DeviceParser.parseBrowser(UNKNOWN_UA)).isEqualTo("Other");
    }

    @ParameterizedTest(name = "parseBrowser: null/blank UA → 'Other'")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    @DisplayName("parseBrowser: null or blank UA → 'Other'")
    void parseBrowser_nullOrBlank_returnsOther(String userAgent) {
        assertThat(DeviceParser.parseBrowser(userAgent)).isEqualTo("Other");
    }
}
