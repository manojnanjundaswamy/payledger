package dev.payledger.gateway;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

/**
 * Caps API calls per signed-in user (or per IP before sign-in), counted in Redis so every gateway instance
 * shares the count: a fixed one-minute window, with a lower cap for writes. Stops a runaway script, scraping, or a
 * stolen session from hammering core-service. If Redis is down the request goes through: a limit must never become
 * an outage.
 */
@Component
class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final StringRedisTemplate redis;
    private final int perMinute;
    private final int writesPerMinute;

    RateLimitFilter(StringRedisTemplate redis,
                    @Value("${payledger.rate-limit.per-minute:600}") int perMinute,
                    @Value("${payledger.rate-limit.writes-per-minute:120}") int writesPerMinute) {
        this.redis = redis;
        this.perMinute = perMinute;
        this.writesPerMinute = writesPerMinute;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long minute = Instant.now().getEpochSecond() / 60;
        String who = who(request);
        boolean write = !("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()) || "OPTIONS".equals(request.getMethod()));
        if (over("all", who, minute, perMinute) || (write && over("write", who, minute, writesPerMinute))) {
            long retry = 60 - Instant.now().getEpochSecond() % 60;
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(retry));
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("""
                    {"type":"https://github.com/payledger/problems/rate-limited","title":"rate-limited","status":429,\
                    "detail":"Too many requests. Try again in a minute.","code":"rate-limited"}""");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean over(String kind, String who, long minute, int limit) {
        String key = "payledger:rl:" + kind + ":" + who + ":" + minute;
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1) {
                redis.expire(key, Duration.ofSeconds(90));
            }
            return count != null && count > limit;
        } catch (RuntimeException e) {
            log.warn("Rate limit check skipped, Redis unavailable: {}", e.getMessage());
            return false;
        }
    }

    /** The user's subject once signed in (shared across their devices), else the client's address. */
    private static String who(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return "user:" + auth.getName();
        }
        return "ip:" + request.getRemoteAddr();
    }
}
