package com.urlshortener.write.security;

import com.urlshortener.core.auth.AuthenticatedUser;
import com.urlshortener.core.auth.UserIdentityHasher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class AuthenticatedUserResolver {

    public Optional<AuthenticatedUser> resolve(Jwt jwt) {
        if (jwt == null) {
            return Optional.empty();
        }

        String subject = jwt.getSubject();
        String email = jwt.getClaimAsString("email");
        String name = jwt.getClaimAsString("name");
        return Optional.of(new AuthenticatedUser(
                UserIdentityHasher.hashSubject(subject),
                subject,
                email,
                name));
    }

    public AuthenticatedUser require(Jwt jwt) {
        return resolve(jwt)
                .orElseThrow(() -> new AccessDeniedException("Authentication is required"));
    }
}
