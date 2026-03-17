package com.urlshortener.write.cdn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * No-op implementation of {@link CdnPurgeService} for local development.
 *
 * <p>Active by default ({@code write.cdn.enabled=false} or the property absent).
 * All calls are logged at {@code INFO} level so that purge events are visible during
 * local testing without making any outbound HTTP calls.
 *
 * <p>In production, set {@code write.cdn.enabled=true} to activate
 * {@link CloudflareCdnPurgeService} instead.
 */
@Service
@ConditionalOnProperty(name = "write.cdn.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpCdnPurgeService implements CdnPurgeService {

    private static final Logger log = LoggerFactory.getLogger(NoOpCdnPurgeService.class);

    @Override
    public void purgeByTag(String tag) {
        log.info("[CDN-PURGE STUB] would purge cache tag: {}", tag);
    }

    @Override
    public void purgeByUrl(String url) {
        log.info("[CDN-PURGE STUB] would purge cache url: {}", url);
    }
}
