package com.urlshortener.analytics.controller;

import com.urlshortener.analytics.service.AnalyticsQueryService;
import com.urlshortener.analytics.service.LinkManagementService;
import com.urlshortener.core.dto.AnalyticsResponse;
import com.urlshortener.core.dto.ShortenResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

/**
 * REST controller exposing the analytics API at {@code /v1}.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET /v1/urls/{shortKey}/analytics} — per-link analytics</li>
 *   <li>{@code GET /v1/urls} — paginated list of links owned by a user</li>
 * </ul>
 *
 * <h2>Authentication (stub)</h2>
 * Auth is intentionally stubbed: the user identity is extracted from the
 * {@code X-User-Id} header without any token validation.  A production
 * deployment would place an API gateway or Spring Security filter in front
 * of these endpoints.
 */
@RestController
@RequestMapping("/v1")
public class AnalyticsController {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsController.class);

    private static final Set<String> VALID_GRANULARITIES = Set.of("hour", "day", "week");

    /** Default look-back window when {@code from} is not provided. */
    private static final int DEFAULT_LOOKBACK_DAYS = 30;

    private final AnalyticsQueryService analyticsQueryService;
    private final LinkManagementService linkManagementService;

    public AnalyticsController(AnalyticsQueryService analyticsQueryService,
                               LinkManagementService linkManagementService) {
        this.analyticsQueryService = analyticsQueryService;
        this.linkManagementService = linkManagementService;
    }

    // ── GET /v1/urls/{shortKey}/analytics ────────────────────────────────────

    /**
     * Returns analytics for the given short key.
     *
     * <p>Query parameters:
     * <ul>
     *   <li>{@code from} — ISO-8601 datetime (e.g. {@code 2025-06-01T00:00:00Z});
     *       defaults to 30 days ago.</li>
     *   <li>{@code to}   — ISO-8601 datetime; defaults to now.</li>
     *   <li>{@code granularity} — one of {@code hour}, {@code day}, {@code week};
     *       defaults to {@code day}.</li>
     * </ul>
     *
     * @param shortKey    URL path variable — the short key to query
     * @param from        optional range start (ISO-8601)
     * @param to          optional range end (ISO-8601)
     * @param granularity optional grouping granularity
     * @return 200 OK with {@link AnalyticsResponse}
     */
    @GetMapping("/urls/{shortKey}/analytics")
    public ResponseEntity<AnalyticsResponse> getAnalytics(
            @PathVariable String shortKey,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false, defaultValue = "day") String granularity) {

        // ── Validate / default parameters ────────────────────────────────────
        Instant effectiveTo   = (to   != null) ? to   : Instant.now();
        Instant effectiveFrom = (from != null) ? from
                : effectiveTo.minus(DEFAULT_LOOKBACK_DAYS, ChronoUnit.DAYS);

        if (effectiveFrom.isAfter(effectiveTo)) {
            return ResponseEntity.badRequest().build();
        }

        String effectiveGranularity = VALID_GRANULARITIES.contains(granularity) ? granularity : "day";

        log.info("Analytics request — shortKey={}, from={}, to={}, granularity={}",
                shortKey, effectiveFrom, effectiveTo, effectiveGranularity);

        AnalyticsResponse response = analyticsQueryService.getAnalytics(
                shortKey, effectiveFrom, effectiveTo, effectiveGranularity);

        return ResponseEntity.ok(response);
    }

    // ── GET /v1/urls ─────────────────────────────────────────────────────────

    /**
     * Returns a paginated list of URLs belonging to the authenticated user.
     *
     * <p>The user identity is taken from the {@code X-User-Id} HTTP header
     * (stub authentication — no token validation).
     *
     * @param userId the user ID from {@code X-User-Id} header
     * @param page   zero-based page index (default 0)
     * @param size   page size (default 20, capped at 100 in the service layer)
     * @return 200 OK with a list of {@link ShortenResponse}; 400 if header missing/invalid
     */
    @GetMapping("/urls")
    public ResponseEntity<List<ShortenResponse>> getUserLinks(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        if (userId == null || userId.isBlank()) {
            log.warn("GET /v1/urls called without X-User-Id header");
            return ResponseEntity.badRequest().build();
        }

        long userIdLong;
        try {
            userIdLong = Long.parseLong(userId.trim());
        } catch (NumberFormatException e) {
            log.warn("GET /v1/urls called with non-numeric X-User-Id: {}", userId);
            return ResponseEntity.badRequest().build();
        }

        if (page < 0 || size <= 0) {
            return ResponseEntity.badRequest().build();
        }

        log.debug("getUserLinks — userId={}, page={}, size={}", userIdLong, page, size);

        List<ShortenResponse> links = linkManagementService.getUserLinks(userIdLong, page, size);
        return ResponseEntity.ok(links);
    }
}
