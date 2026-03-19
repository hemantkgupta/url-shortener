package com.urlshortener.analytics.security;

import com.urlshortener.core.auth.AuthenticatedUser;
import com.urlshortener.core.auth.UserIdentityHasher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class AnalyticsAuthenticatedUserResolver {

    public AuthenticatedUser require(Jwt jwt) {
        if (jwt == null) {
            throw new AccessDeniedException("Authentication is required");
        }

        String subject = jwt.getSubject();
        return new AuthenticatedUser(
                UserIdentityHasher.hashSubject(subject),
                subject,
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("name"));
    }
}
