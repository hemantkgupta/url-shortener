package com.urlshortener.redirect.service;

import java.util.regex.Pattern;

/**
 * Lightweight User-Agent parser for device-type and browser-family classification.
 *
 * <p>This is an intentionally simple heuristic parser — for high-fidelity parsing use
 * a dedicated library such as <em>ua-parser</em> or <em>yauaa</em>.  The redirect
 * service keeps it simple to avoid adding latency on the hot path; richer analytics
 * enrichment happens downstream in the Flink pipeline.
 *
 * <p>All patterns are pre-compiled as static constants.  All methods are stateless
 * and thread-safe.
 */
public final class DeviceParser {

    // ── Device patterns ───────────────────────────────────────────────────────

    /** Matches common mobile device signals in User-Agent strings. */
    private static final Pattern MOBILE_PATTERN = Pattern.compile(
            "(?i)(android.*mobile|iphone|ipod|windows phone|blackberry|mobile safari|"
                    + "opera mini|opera mobi|iemobile|symbian|nokia|samsung.*mobile|lg.*mobile)",
            Pattern.CASE_INSENSITIVE);

    /** Matches tablet identifiers — checked after MOBILE_PATTERN. */
    private static final Pattern TABLET_PATTERN = Pattern.compile(
            "(?i)(ipad|android(?!.*mobile)|tablet|kindle|silk|playbook|nexus 7|nexus 10)",
            Pattern.CASE_INSENSITIVE);

    // ── Browser patterns ──────────────────────────────────────────────────────

    private static final Pattern EDGE_PATTERN    = Pattern.compile("(?i)Edg/|Edge/");
    private static final Pattern CHROME_PATTERN  = Pattern.compile("(?i)Chrome/");
    private static final Pattern FIREFOX_PATTERN = Pattern.compile("(?i)Firefox/");
    private static final Pattern SAFARI_PATTERN  = Pattern.compile("(?i)Safari/");

    private DeviceParser() {
        // utility class — no instances
    }

    /**
     * Classifies the device type from a User-Agent string.
     *
     * @param userAgent the raw {@code User-Agent} header value; may be {@code null}
     * @return {@code "mobile"}, {@code "tablet"}, or {@code "desktop"}
     */
    public static String parseDevice(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "desktop";
        }
        if (MOBILE_PATTERN.matcher(userAgent).find()) {
            return "mobile";
        }
        if (TABLET_PATTERN.matcher(userAgent).find()) {
            return "tablet";
        }
        return "desktop";
    }

    /**
     * Identifies the browser family from a User-Agent string.
     *
     * <p>Edge must be checked before Chrome because Edge also includes {@code Chrome/}
     * in its User-Agent token.
     *
     * @param userAgent the raw {@code User-Agent} header value; may be {@code null}
     * @return {@code "Edge"}, {@code "Chrome"}, {@code "Firefox"}, {@code "Safari"},
     *         or {@code "Other"}
     */
    public static String parseBrowser(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "Other";
        }
        // Order matters: Edge UA also contains "Chrome/", so check Edge first
        if (EDGE_PATTERN.matcher(userAgent).find()) {
            return "Edge";
        }
        if (CHROME_PATTERN.matcher(userAgent).find()) {
            return "Chrome";
        }
        if (FIREFOX_PATTERN.matcher(userAgent).find()) {
            return "Firefox";
        }
        if (SAFARI_PATTERN.matcher(userAgent).find()) {
            return "Safari";
        }
        return "Other";
    }
}
