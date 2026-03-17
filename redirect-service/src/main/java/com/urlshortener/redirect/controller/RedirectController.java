package com.urlshortener.redirect.controller;

import com.urlshortener.redirect.config.RedirectServiceProperties;
import com.urlshortener.redirect.service.RedirectResult;
import com.urlshortener.redirect.service.RedirectService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Pattern;

/**
 * REST controller that handles {@code GET /{shortKey}} — the sole endpoint of the
 * Redirect Service.
 *
 * <h2>Input validation</h2>
 * Short keys must match {@code [A-Za-z0-9]{1,8}}.  Requests with invalid keys are
 * rejected with HTTP 400 before any downstream lookup, preventing malformed keys from
 * wasting DB or cache resources.
 *
 * <h2>Response codes</h2>
 * <ul>
 *   <li>{@code 302 Found} — active mapping; {@code Location} header contains the long URL</li>
 *   <li>{@code 404 Not Found} — no mapping for the key (Bloom filter rejected or DB miss)</li>
 *   <li>{@code 410 Gone} — mapping exists but is inactive or past its expiry time</li>
 *   <li>{@code 400 Bad Request} — short key fails format validation</li>
 * </ul>
 *
 * <h2>Cache-Control strategy</h2>
 * <ul>
 *   <li><b>302 Found</b> — {@code Cache-Control: public, max-age=N, s-maxage=N} so the
 *       upstream CDN (Cloudflare/Fastly) caches the redirect for {@code N} seconds (default
 *       3 600 s = 1 hour).  The CDN absorbs ~80% of read traffic; requests that hit the CDN
 *       never reach this service, so analytics are captured only for origin hits (~20%).
 *       {@code Surrogate-Key} (Fastly) and {@code Cache-Tag} (Cloudflare) headers are set to
 *       {@code url-{shortKey}} to enable targeted cache purge on URL deletion.</li>
 *   <li><b>404 / 410</b> — {@code Cache-Control: no-store}.  Negative results must never be
 *       CDN-cached: a key that returns 404 today could be created tomorrow.</li>
 * </ul>
 */
@RestController
public class RedirectController {

    private static final Logger log = LoggerFactory.getLogger(RedirectController.class);

    /** Allowed short key format: 1–8 Base-62 characters. */
    private static final Pattern SHORT_KEY_PATTERN = Pattern.compile("[A-Za-z0-9]{1,8}");

    /** Cache-Control for negative results (404/410) — never cache. */
    private static final String CACHE_CONTROL_NO_STORE = "no-store";

    /**
     * Surrogate-Key header used by Fastly for tag-based purge.
     * Value convention: {@code url-{shortKey}}.
     */
    private static final String SURROGATE_KEY_HEADER = "Surrogate-Key";

    /**
     * Cache-Tag header used by Cloudflare for tag-based purge.
     * Value convention: {@code url-{shortKey}}.
     */
    private static final String CACHE_TAG_HEADER = "Cache-Tag";

    private final RedirectService redirectService;
    private final RedirectServiceProperties properties;

    public RedirectController(RedirectService redirectService,
                              RedirectServiceProperties properties) {
        this.redirectService = redirectService;
        this.properties = properties;
    }

    /**
     * Resolves a short key and issues a 302 redirect to the original long URL.
     *
     * @param shortKey the Base-62 short key from the path (e.g. {@code /aB3xY9z})
     * @param request  the full HTTP request — passed to the service for analytics extraction
     * @return a {@link ResponseEntity} with the appropriate status and headers
     */
    @GetMapping("/{shortKey}")
    public ResponseEntity<Void> redirect(
            @PathVariable String shortKey,
            HttpServletRequest request) {

        // ── Input validation ──────────────────────────────────────────────────
        if (!SHORT_KEY_PATTERN.matcher(shortKey).matches()) {
            log.debug("Rejected invalid shortKey format: '{}'", shortKey);
            return ResponseEntity.badRequest().build();
        }

        log.debug("Redirect request for shortKey={}", shortKey);

        // ── Service call ──────────────────────────────────────────────────────
        RedirectResult result = redirectService.redirect(shortKey, request);

        // ── Map result to HTTP response ───────────────────────────────────────
        return switch (result) {
            case RedirectResult.Found found -> {
                log.debug("Redirecting shortKey={} → {}", shortKey, found.longUrl());
                long ttl = properties.getCdn().getCacheMaxAgeSeconds();
                String cacheControl = "public, max-age=" + ttl + ", s-maxage=" + ttl;
                String cacheTag     = "url-" + shortKey;
                yield ResponseEntity.status(HttpStatus.FOUND)
                        .header(HttpHeaders.LOCATION,  found.longUrl())
                        .header(HttpHeaders.CACHE_CONTROL, cacheControl)
                        .header(SURROGATE_KEY_HEADER,  cacheTag)   // Fastly tag-based purge
                        .header(CACHE_TAG_HEADER,      cacheTag)   // Cloudflare tag-based purge
                        .build();
            }
            case RedirectResult.NotFound notFound -> {
                log.debug("Not found: shortKey={}", shortKey);
                yield ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_NO_STORE)
                        .build();
            }
            case RedirectResult.Gone gone -> {
                log.debug("Gone (inactive/expired): shortKey={}", shortKey);
                yield ResponseEntity.status(HttpStatus.GONE)
                        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_NO_STORE)
                        .build();
            }
        };
    }
}
