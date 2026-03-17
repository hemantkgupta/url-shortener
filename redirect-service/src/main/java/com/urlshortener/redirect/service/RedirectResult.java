package com.urlshortener.redirect.service;

/**
 * Sealed result type returned by {@link RedirectService#redirect}.
 *
 * <p>Pattern-matching on this type in the controller ensures exhaustive handling of
 * all redirect outcomes without null checks or exception-driven control flow:
 *
 * <pre>{@code
 * RedirectResult result = redirectService.redirect(shortKey, request);
 * return switch (result) {
 *     case RedirectResult.Found f    -> ResponseEntity.status(302)
 *                                           .header("Location", f.longUrl()).build();
 *     case RedirectResult.NotFound() -> ResponseEntity.notFound().build();
 *     case RedirectResult.Gone()     -> ResponseEntity.status(410).build();
 * };
 * }</pre>
 */
public sealed interface RedirectResult
        permits RedirectResult.Found, RedirectResult.NotFound, RedirectResult.Gone {

    /**
     * The short URL was found and is active — redirect the client to {@link #longUrl()}.
     *
     * @param longUrl the destination URL (HTTP 302 Location header value)
     */
    record Found(String longUrl) implements RedirectResult {}

    /**
     * No mapping exists for the requested short key (Bloom filter rejected it, or it
     * was never created / has been hard-deleted).  Return HTTP 404.
     */
    record NotFound() implements RedirectResult {}

    /**
     * A mapping exists but is either inactive ({@code is_active = false}) or has
     * passed its expiry time.  Return HTTP 410 to signal permanent unavailability.
     */
    record Gone() implements RedirectResult {}
}
