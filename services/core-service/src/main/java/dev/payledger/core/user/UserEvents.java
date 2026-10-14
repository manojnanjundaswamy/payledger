package dev.payledger.core.user;

import dev.payledger.core.common.Uuid7;
import dev.payledger.core.notify.Inbox;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What people do, for staff to look into (support, abuse, security): each new sign-in session once, and every change made
 * through the API (method, route, ids, status), never the amounts or words in it. Also tells the user, in their inbox, about
 * a sign-in from a browser they haven't used before. Kept 180 days.
 */
@Component
public class UserEvents implements HandlerInterceptor {

    static final String USER_ATTRIBUTE = UserEvents.class.getName() + ".user";

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Inbox inbox;
    /** Sessions already recorded by this instance; the unique index catches the rest. */
    private final Set<String> seenSessions = ConcurrentHashMap.newKeySet();

    UserEvents(JdbcClient jdbc, JsonMapper json, Inbox inbox) {
        this.jdbc = jdbc;
        this.json = json;
        this.inbox = inbox;
    }

    /** Called when a request's user is resolved: a Keycloak session seen for the first time is a sign-in. */
    void seen(CurrentUser user, String sessionId, HttpServletRequest request) {
        if (request != null) {
            request.setAttribute(USER_ATTRIBUTE, user.id());
            request.setAttribute(dev.payledger.core.common.RequestLog.USER, user.id());
        }
        if (sessionId == null || !seenSessions.add(user.id() + ":" + sessionId)) {
            return;
        }
        if (seenSessions.size() > 100_000) {
            seenSessions.clear();
        }
        String ua = request == null ? null : cut(request.getHeader("User-Agent"), 300);
        // A browser this person has signed in with before isn't news; a new one might be someone else.
        boolean knownDevice = ua != null && jdbc.sql("""
                        select exists (select 1 from user_events where user_id = :userId and type = 'SIGN_IN' and user_agent = :ua)
                        """).param("userId", user.id()).param("ua", ua).query(Boolean.class).single();
        int added = jdbc.sql("""
                        insert into user_events (id, user_id, type, session_id, ip, user_agent)
                        values (:id, :userId, 'SIGN_IN', :sid, :ip, :ua)
                        on conflict do nothing
                        """)
                .param("id", Uuid7.next()).param("userId", user.id()).param("sid", cut(sessionId, 64))
                .param("ip", request == null ? null : cut(ip(request), 64)).param("ua", ua)
                .update();
        if (added > 0 && !knownDevice) {
            inbox.notify(user.id(), Inbox.Kind.SIGN_IN, "New sign-in" + (ua == null ? "" : " on " + device(ua)),
                    "If this wasn't you, change your password and sign out everywhere from Settings.", "/settings?tab=account", null,
                    "sign-in:" + user.id() + ":" + sessionId, null);
        }
    }

    /** Every change (not reads), after it is answered, with its outcome. */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        String method = request.getMethod();
        if (method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS")
                || !(request.getAttribute(USER_ATTRIBUTE) instanceof UUID userId)) {
            return;
        }
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        jdbc.sql("""
                        insert into user_events (id, user_id, type, action, status, target, ip, user_agent)
                        values (:id, :userId, 'CHANGE', :action, :status, cast(:target as jsonb), :ip, :ua)
                        """)
                .param("id", Uuid7.next()).param("userId", userId)
                .param("action", cut(method + " " + (pattern == null ? request.getRequestURI() : pattern), 200))
                .param("status", (short) response.getStatus())
                .param("target", json.writeValueAsString(vars instanceof Map<?, ?> m ? m : Map.of()))
                .param("ip", cut(ip(request), 64)).param("ua", cut(request.getHeader("User-Agent"), 300))
                .update();
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Kolkata")
    void forget() {
        jdbc.sql("delete from user_events where at < now() - interval '180 days'").update();
        jdbc.sql("delete from inbox where created_at < now() - interval '180 days'").update();
    }

    private static String ip(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].strip() : request.getRemoteAddr();
    }

    /** "Chrome on Windows" from a user agent, for the sign-in notice. */
    static String device(String ua) {
        String browser = ua.contains("Edg/") ? "Edge" : ua.contains("Chrome/") ? "Chrome" : ua.contains("Firefox/") ? "Firefox"
                : ua.contains("Safari/") ? "Safari" : "a browser";
        String os = ua.contains("Android") ? "Android" : ua.contains("iPhone") || ua.contains("iPad") ? "iOS" : ua.contains("Windows") ? "Windows"
                : ua.contains("Mac OS") ? "Mac" : ua.contains("Linux") ? "Linux" : null;
        return os == null ? browser : browser + " on " + os;
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }
}
