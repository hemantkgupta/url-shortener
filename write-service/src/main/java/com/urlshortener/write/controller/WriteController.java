package com.urlshortener.write.controller;

import com.urlshortener.core.dto.ShortenRequest;
import com.urlshortener.core.dto.ShortenResponse;
import com.urlshortener.write.service.WriteService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * REST controller for URL creation and deletion.
 *
 * <p>All request-body validation is handled by the Jakarta Validation annotations on
 * {@link ShortenRequest}; violations are translated to 400 responses by
 * {@link com.urlshortener.write.exception.WriteExceptionHandler}.
 */
@RestController
@RequestMapping("/v1/urls")
public class WriteController {

    private static final Logger log = LoggerFactory.getLogger(WriteController.class);

    private final WriteService writeService;

    public WriteController(WriteService writeService) {
        this.writeService = writeService;
    }

    // ── Endpoints ─────────────────────────────────────────────────────────────

    /**
     * Shortens a long URL.
     *
     * <pre>POST /v1/urls</pre>
     *
     * @param request the shorten request body; validated via Jakarta Validation
     * @return 201 Created with {@code Location} header and {@link ShortenResponse} body
     */
    @PostMapping
    public ResponseEntity<ShortenResponse> createShortUrl(
            @RequestBody @Valid ShortenRequest request) {
        log.info("POST /v1/urls — longUrl={}, customKey={}, ttlDays={}",
                request.getLongUrl(), request.getCustomKey(), request.getTtlDays());

        ShortenResponse response = writeService.shorten(request);

        return ResponseEntity
                .created(URI.create(response.getShortUrl()))
                .body(response);
    }

    /**
     * Deletes a short URL mapping.
     *
     * <pre>DELETE /v1/urls/{shortKey}</pre>
     *
     * <p>This is a stub implementation: the mapping is removed from ScyllaDB and a
     * cache-invalidation Kafka event is published.  A future version should add
     * authentication/authorization checks.
     *
     * @param shortKey the short key to delete
     * @return 204 No Content
     */
    @DeleteMapping("/{shortKey}")
    public ResponseEntity<Void> deleteShortUrl(@PathVariable String shortKey) {
        log.info("DELETE /v1/urls/{}", shortKey);
        writeService.delete(shortKey);
        return ResponseEntity.noContent().build();
    }
}
