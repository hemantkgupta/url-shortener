package com.urlshortener.write.cdn;

import com.urlshortener.write.config.WriteServiceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/**
 * Cloudflare implementation of {@link CdnPurgeService}.
 *
 * <p>Active when {@code write.cdn.enabled=true}.  Calls the Cloudflare Cache API to
 * purge edge-cached redirect responses immediately after a URL is deleted.
 *
 * <h2>Tag-based purge (primary)</h2>
 * Uses the
 * <a href="https://developers.cloudflare.com/cache/how-to/purge-cache/purge-by-tags/">
 * Purge Cache by Cache-Tags</a> endpoint:
 * <pre>
 * POST /client/v4/zones/{zoneId}/purge_cache
 * { "tags": ["url-{shortKey}"] }
 * </pre>
 * This requires the redirect-service to set {@code Cache-Tag: url-{shortKey}} on every
 * 302 response — which {@code RedirectController} does.
 *
 * <h2>URL-based purge (secondary)</h2>
 * Falls back to purging the absolute short URL via:
 * <pre>
 * POST /client/v4/zones/{zoneId}/purge_cache
 * { "files": ["https://sho.rt/{shortKey}"] }
 * </pre>
 *
 * <h2>Failure handling</h2>
 * Purge failures are logged as warnings but do not throw — a failed CDN purge is
 * operationally recoverable (the cached entry expires within 1 hour anyway).
 */
@Service
@ConditionalOnProperty(name = "write.cdn.enabled", havingValue = "true")
public class CloudflareCdnPurgeService implements CdnPurgeService {

    private static final Logger log = LoggerFactory.getLogger(CloudflareCdnPurgeService.class);

    private static final String CLOUDFLARE_API_BASE = "https://api.cloudflare.com/client/v4";

    private final RestClient restClient;
    private final String zoneId;

    public CloudflareCdnPurgeService(WriteServiceProperties properties,
                                     RestClient.Builder restClientBuilder) {
        WriteServiceProperties.Cdn.Cloudflare cf = properties.getCdn().getCloudflare();
        this.zoneId = cf.getZoneId();

        this.restClient = restClientBuilder
                .baseUrl(CLOUDFLARE_API_BASE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + cf.getApiToken())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Purges all CDN edge nodes caching responses tagged with {@code tag}.
     *
     * @param tag the cache tag (e.g. {@code "url-aB3xY9z"})
     */
    @Override
    public void purgeByTag(String tag) {
        log.info("Purging CDN cache by tag: zoneId={} tag={}", zoneId, tag);
        try {
            restClient.post()
                    .uri("/zones/{zoneId}/purge_cache", zoneId)
                    .body(Map.of("tags", List.of(tag)))
                    .retrieve()
                    .toBodilessEntity();
            log.info("CDN cache purge by tag succeeded: tag={}", tag);
        } catch (RestClientException ex) {
            log.warn("CDN cache purge by tag failed (non-fatal): tag={} error={}",
                    tag, ex.getMessage());
        }
    }

    /**
     * Purges the CDN cache entry for the given absolute short URL.
     *
     * @param url the fully-qualified short URL (e.g. {@code https://sho.rt/aB3xY9z})
     */
    @Override
    public void purgeByUrl(String url) {
        log.info("Purging CDN cache by url: zoneId={} url={}", zoneId, url);
        try {
            restClient.post()
                    .uri("/zones/{zoneId}/purge_cache", zoneId)
                    .body(Map.of("files", List.of(url)))
                    .retrieve()
                    .toBodilessEntity();
            log.info("CDN cache purge by url succeeded: url={}", url);
        } catch (RestClientException ex) {
            log.warn("CDN cache purge by url failed (non-fatal): url={} error={}",
                    url, ex.getMessage());
        }
    }
}
