package dev.payledger.ledger.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

/**
 * Requires an {@code X-API-Key} header on every {@code /api/**} call.
 * <p>
 * An interim, service-to-service style guard so the public deployment is not an open money API.
 * It is replaced by OAuth2/OIDC (Keycloak) in a later phase. Keys are compared in constant time so
 * response timing can't leak how many leading characters of a guess were correct.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    static final String HEADER = "X-API-Key";

    private final List<byte[]> keys;

    public ApiKeyFilter(@Value("${payledger.security.api-keys}") String apiKeys,
                        @Value("${payledger.security.require-api-key}") boolean required) {
        this.keys = Arrays.stream(apiKeys.split(","))
                .map(String::strip)
                .filter(k -> !k.isEmpty())
                .map(k -> k.getBytes(StandardCharsets.UTF_8))
                .toList();
        if (required && keys.isEmpty()) {
            throw new IllegalStateException("payledger.security.api-keys (env API_KEYS) must be set when API keys are required");
        }
        if (keys.stream().anyMatch(k -> k.length < 32)) {
            throw new IllegalStateException("API keys must be at least 32 characters");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return keys.isEmpty() || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (presented != null && isKnown(presented.getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"type":"https://github.com/payledger/problems/unauthorized","title":"unauthorized",\
                "status":401,"detail":"missing or invalid X-API-Key header","code":"unauthorized"}""");
    }

    private boolean isKnown(byte[] presented) {
        boolean match = false;
        for (byte[] key : keys) {
            match |= MessageDigest.isEqual(key, presented);
        }
        return match;
    }
}
