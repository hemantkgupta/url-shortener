package com.urlshortener.write.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.urlshortener.core.dto.ShortenRequest;
import com.urlshortener.core.dto.ShortenResponse;
import com.urlshortener.write.cdn.CdnPurgeService;
import com.urlshortener.write.config.KgsClient;
import com.urlshortener.write.config.WriteServiceProperties;
import com.urlshortener.write.exception.AliasConflictException;
import com.urlshortener.write.exception.KeyGenerationException;
import com.urlshortener.write.repository.UrlMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WriteService}.
 *
 * <p>All collaborators are Mockito mocks.  No Spring context is started.
 */
@ExtendWith(MockitoExtension.class)
class WriteServiceTest {

    private static final String GENERATED_KEY = "00abc123";
    private static final String LONG_URL       = "https://example.com/some/path";
    private static final String OWN_DOMAIN     = "short.ly";

    @Mock private UrlValidationService    validationService;
    @Mock private KgsClient               kgsClient;
    @Mock private UrlMappingRepository    repository;
    @Mock private CacheWarmupService      cacheWarmupService;
    @Mock private KafkaTemplate<String, String> kafkaTemplate;
    @Mock private CdnPurgeService         cdnPurgeService;

    private WriteServiceProperties properties;
    private ObjectMapper           objectMapper;
    private WriteService           writeService;

    @BeforeEach
    void setUp() {
        properties = new WriteServiceProperties();
        properties.setOwnDomain(OWN_DOMAIN);

        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        writeService = new WriteService(
                validationService,
                kgsClient,
                repository,
                cacheWarmupService,
                kafkaTemplate,
                properties,
                objectMapper,
                cdnPurgeService);

        // Default stubs
        when(validationService.validateAndNormalize(anyString())).thenReturn(LONG_URL);
        when(kgsClient.nextKey()).thenReturn(GENERATED_KEY);
        when(cacheWarmupService.warmCache(anyString(), anyString(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(cacheWarmupService.addToBloomFilter(anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        CompletableFuture<SendResult<String, String>> kafkaFuture = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(kafkaFuture);
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("successful shorten returns ShortenResponse with correct shortKey")
    void successfulShortenReturnsCorrectResponse() {
        ShortenRequest request = new ShortenRequest(LONG_URL, null, null);

        ShortenResponse response = writeService.shorten(request);

        assertThat(response.getShortKey()).isEqualTo(GENERATED_KEY);
        assertThat(response.getLongUrl()).isEqualTo(LONG_URL);
        assertThat(response.getShortUrl()).contains(GENERATED_KEY);
        assertThat(response.getCreatedAt()).isNotNull();
        assertThat(response.getExpiresAt()).isNotNull();
    }

    @Test
    @DisplayName("successful shorten saves mapping to repository")
    void successfulShortenPersistsMapping() {
        ShortenRequest request = new ShortenRequest(LONG_URL, null, null);

        writeService.shorten(request);

        verify(repository).save(any());
    }

    @Test
    @DisplayName("successful shorten triggers async cache warmup")
    void successfulShortenTriggersAsyncCacheWarm() {
        ShortenRequest request = new ShortenRequest(LONG_URL, null, null);

        writeService.shorten(request);

        verify(cacheWarmupService).warmCache(eq(GENERATED_KEY), eq(LONG_URL), anyLong());
        verify(cacheWarmupService).addToBloomFilter(eq(GENERATED_KEY));
    }

    @Test
    @DisplayName("successful shorten publishes Kafka event")
    void successfulShortenPublishesKafkaEvent() {
        ShortenRequest request = new ShortenRequest(LONG_URL, null, null);

        writeService.shorten(request);

        ArgumentCaptor<String> topicCaptor   = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor     = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(topicCaptor.capture(), keyCaptor.capture(), payloadCaptor.capture());

        assertThat(topicCaptor.getValue()).isEqualTo("url.created");
        assertThat(keyCaptor.getValue()).isEqualTo(GENERATED_KEY);
        assertThat(payloadCaptor.getValue()).contains(GENERATED_KEY);
    }

    // ── Custom alias ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("custom alias that is free is used as the short key")
    void customAliasUsedWhenFree() {
        String alias = "my-brand";
        when(repository.existsAlias(alias)).thenReturn(false);

        ShortenRequest request = new ShortenRequest(LONG_URL, alias, null);
        ShortenResponse response = writeService.shorten(request);

        assertThat(response.getShortKey()).isEqualTo(alias);
        verify(kgsClient, never()).nextKey();  // KGS should not be called for custom key
        verify(repository).saveAlias(eq(alias), eq(alias), any());
    }

    @Test
    @DisplayName("custom alias that is already taken throws AliasConflictException")
    void customAliasTakenThrowsAliasConflict() {
        String alias = "taken-alias";
        when(repository.existsAlias(alias)).thenReturn(true);

        ShortenRequest request = new ShortenRequest(LONG_URL, alias, null);

        assertThatThrownBy(() -> writeService.shorten(request))
                .isInstanceOf(AliasConflictException.class)
                .hasMessageContaining(alias);

        // Repository save must not be called when alias is in conflict
        verify(repository, never()).save(any());
    }

    // ── KGS failure ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("KGS failure (KeyGenerationException) propagates to caller")
    void kgsFailurePropagates() {
        when(kgsClient.nextKey()).thenThrow(new KeyGenerationException("KGS is down"));

        ShortenRequest request = new ShortenRequest(LONG_URL, null, null);

        assertThatThrownBy(() -> writeService.shorten(request))
                .isInstanceOf(KeyGenerationException.class)
                .hasMessageContaining("KGS is down");
    }

    // ── Delete path ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("delete removes mapping from repository")
    void deleteRemovesMappingFromRepository() {
        writeService.delete(GENERATED_KEY);

        verify(repository).deleteByShortKey(GENERATED_KEY);
    }

    @Test
    @DisplayName("delete purges CDN by tag and by url")
    void deletePurgesCdnCacheByTagAndUrl() {
        writeService.delete(GENERATED_KEY);

        // tag convention: "url-{shortKey}"
        verify(cdnPurgeService).purgeByTag("url-" + GENERATED_KEY);
        // url: https://{ownDomain}/{shortKey}
        verify(cdnPurgeService).purgeByUrl("https://" + OWN_DOMAIN + "/" + GENERATED_KEY);
    }

    @Test
    @DisplayName("delete publishes Kafka cache-invalidation event with DELETE action")
    void deletePublishesKafkaCacheInvalidationEvent() {
        writeService.delete(GENERATED_KEY);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(anyString(), eq(GENERATED_KEY), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
                .contains(GENERATED_KEY)
                .contains("DELETE");
    }

    // ── TTL handling ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("ttlDays is respected when building the mapping")
    void ttlDaysIsRespected() {
        ShortenRequest request = new ShortenRequest(LONG_URL, null, 30);

        ShortenResponse response = writeService.shorten(request);

        assertThat(response.getExpiresAt()).isNotNull();
        // expiresAt should be approximately 30 days from now
        long daysUntilExpiry = java.time.Duration.between(
                java.time.Instant.now(), response.getExpiresAt()).toDays();
        assertThat(daysUntilExpiry).isBetween(29L, 30L);
    }
}
