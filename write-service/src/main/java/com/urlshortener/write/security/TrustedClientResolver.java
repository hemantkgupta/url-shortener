package com.urlshortener.write.security;

import com.urlshortener.core.auth.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class TrustedClientResolver {

    public String resolveRateLimitClientId(HttpServletRequest request, AuthenticatedUser user) {
        if (user != null) {
            return "user:" + user.subject();
        }
        return "ip:" + resolveClientIp(request);
    }

    public String resolveClientIp(HttpServletRequest request) {
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
