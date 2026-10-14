package dev.payledger.core.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Records every API request: when (to the millisecond), who, which route, the status, how long it took, and for a
 * failure its error code and message. Runs outside Spring Security, so refused requests (401, 403) are on record too.
 * Gives each request an id (X-Request-Id, also in error responses and in the logs) to find it again. Bodies are never
 * kept. Kept 90 days.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLog extends OncePerRequestFilter {

    public static final String REQUEST_ID = "X-Request-Id";
    /** Request attributes set further in: who it was, and what went wrong. */
    public static final String USER = RequestLog.class.getName() + ".user";
    public static final String ERROR_CODE = RequestLog.class.getName() + ".errorCode";
    public static final String ERROR_DETAIL = RequestLog.class.getName() + ".errorDetail";

    private static final Logger log = LoggerFactory.getLogger(RequestLog.class);
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final JdbcClient jdbc;

    RequestLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // The gateway may pass one on; otherwise a new one.
        String incoming = request.getHeader(REQUEST_ID);
        String id = incoming != null && SAFE_ID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID, id);
        response.setHeader(REQUEST_ID, id);
        MDC.put("requestId", id);
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
            record(request, response, id, (System.nanoTime() - start) / 1_000_000);
        }
    }

    private void record(HttpServletRequest request, HttpServletResponse response, String id, long ms) {
        try {
            int status = response.getStatus();
            Object code = request.getAttribute(ERROR_CODE);
            Object detail = request.getAttribute(ERROR_DETAIL);
            if (status >= 400 && code == null) {
                code = status == 401 ? "unauthorized" : status == 403 ? "forbidden" : status == 404 ? "not-found" : "http-" + status;
            }
            String forwarded = request.getHeader("X-Forwarded-For");
            jdbc.sql("""
                            insert into request_log (id, request_id, user_id, method, path, route, status, duration_ms, ip, user_agent, error_code, error_detail)
                            values (:id, :rid, :user, :method, :path, :route, :status, :ms, :ip, :ua, :code, :detail)
                            """)
                    .param("id", Uuid7.next()).param("rid", id)
                    .param("user", request.getAttribute(USER) instanceof UUID u ? u : null)
                    .param("method", request.getMethod()).param("path", cut(request.getRequestURI(), 500))
                    .param("route", cut(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) instanceof String r ? r : null, 200))
                    .param("status", (short) status).param("ms", (int) Math.min(ms, Integer.MAX_VALUE))
                    .param("ip", cut(forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].strip() : request.getRemoteAddr(), 64))
                    .param("ua", cut(request.getHeader("User-Agent"), 300))
                    .param("code", cut(code == null ? null : code.toString(), 80))
                    .param("detail", cut(detail == null ? null : detail.toString(), 1000))
                    .update();
        } catch (RuntimeException e) {
            // Never fail a request because it couldn't be logged.
            log.warn("Request {} not logged: {}", id, e.getMessage());
        }
    }

    @Scheduled(cron = "0 45 3 * * *", zone = "Asia/Kolkata")
    void forget() {
        jdbc.sql("delete from request_log where at < now() - interval '90 days'").update();
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }
}
