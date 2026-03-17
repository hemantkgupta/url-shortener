package com.urlshortener.write.cdn;

/**
 * Abstraction over CDN cache invalidation.
 *
 * <p>Two implementations are provided:
 * <ul>
 *   <li>{@link CloudflareCdnPurgeService} — calls the Cloudflare Cache-Tag purge API.
 *       Active when {@code write.cdn.enabled=true}.</li>
 *   <li>{@link NoOpCdnPurgeService} — logs and does nothing.
 *       Active by default ({@code write.cdn.enabled=false}) for local development.</li>
 * </ul>
 *
 * <h2>Why CDN purge is needed</h2>
 * The redirect-service sets {@code Cache-Control: public, s-maxage=3600} and
 * {@code Cache-Tag: url-{shortKey}} on every 302 response so that Cloudflare/Fastly
 * caches the redirect for 1 hour.  When a URL is deleted, the stale cached redirect
 * must be purged immediately — otherwise users would be redirected to the old
 * destination for up to an hour, which is a phishing / redirect-hijack risk.
 */
public interface CdnPurgeService {

    /**
     * Purges all CDN edge nodes that have cached a response tagged with {@code tag}.
     *
     * <p>The tag convention used by this system is {@code url-{shortKey}},
     * e.g. {@code url-aB3xY9z}.
     *
     * @param tag the cache tag to purge (e.g. {@code "url-aB3xY9z"})
     */
    void purgeByTag(String tag);

    /**
     * Purges the CDN cache for the given absolute URL (e.g. {@code https://sho.rt/aB3xY9z}).
     *
     * <p>Used as a secondary purge mechanism alongside tag-based purge.
     *
     * @param url the fully-qualified short URL to purge
     */
    void purgeByUrl(String url);
}
