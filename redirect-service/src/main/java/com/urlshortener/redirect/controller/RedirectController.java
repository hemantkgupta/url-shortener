package com.urlshortener.redirect.controller;

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
 * <h2>Cache-Control</h2>
 * Every redirect response carries {@code Cache-Control: no-store} to ensure that CDNs
 * and browsers do not cache the redirect.  This guarantees that each user click passes
 * through this service and is counted in the analytics pipeline.
 */
@RestController
public class RedirectController {

    private static final Logger log = LoggerFactory.getLogger(RedirectController.class);

    /** Allowed short key format: 1–8 alphanumeric characters. */
    private static final Pattern SHORT_KEY_PATTERN = Pattern.compile("[A-Za-z0-9]{1,8}");

    /** Prevent browser / CDN caching of redirect responses so every click is counted. */
    private static final String CACHE_CONTROL_NO_STORE = "no-store";

    private final RedirectService redirectService;

    public RedirectController(RedirectService redirectService) {
        this.redirectService = redirectService;
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
                yield ResponseEntity.status(HttpStatus.FOUND)
                        .header(HttpHeaders.LOCATION, found.longUrl())
                        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_NO_STORE)
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
