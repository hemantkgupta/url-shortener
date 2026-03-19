package com.urlshortener.write.security;

import com.urlshortener.core.exception.RateLimitException;
import com.urlshortener.write.config.WriteServiceProperties;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CreateRateLimitService {

    private static final Duration WINDOW = Duration.ofHours(1);

    private final WriteServiceProperties properties;
    private final ConcurrentHashMap<String, CounterWindow> counters = new ConcurrentHashMap<>();

    public CreateRateLimitService(WriteServiceProperties properties) {
        this.properties = properties;
    }

    public void checkCreateAllowed(String clientId, boolean authenticated) {
        int limit = authenticated
                ? properties.getRateLimit().getAuthenticatedCreatesPerHour()
                : properties.getRateLimit().getAnonymousCreatesPerHour();
        long now = System.currentTimeMillis();

        CounterWindow window = counters.compute(clientId, (key, existing) -> {
            if (existing == null || existing.expiresAtMillis() <= now) {
                return new CounterWindow(1, now + WINDOW.toMillis());
            }
            return existing.increment();
        });

        if (window.count() > limit) {
            throw new RateLimitException(clientId, limit, WINDOW.toSeconds());
        }

        if (counters.size() > 10_000) {
            cleanupExpiredWindows(now);
        }
    }

    private void cleanupExpiredWindows(long now) {
        for (Map.Entry<String, CounterWindow> entry : counters.entrySet()) {
            if (entry.getValue().expiresAtMillis() <= now) {
                counters.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    private record CounterWindow(int count, long expiresAtMillis) {
        private CounterWindow increment() {
            return new CounterWindow(count + 1, expiresAtMillis);
        }
    }
}
