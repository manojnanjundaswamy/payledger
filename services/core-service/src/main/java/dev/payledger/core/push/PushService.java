package dev.payledger.core.push;

import dev.payledger.core.common.InvalidRequestException;
import dev.payledger.core.common.Uuid7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends what lands in someone's bell to their phones and browsers as well (Web Push). Each device subscribes once;
 * messages are encrypted for that device, signed with the server's key, and sent after the change that caused them
 * is saved, off the request. A device that's gone (404/410) is forgotten.
 *
 * Push services are a fixed list of hosts: the server never posts to an address a browser makes up.
 */
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);
    static final Set<String> PUSH_HOSTS = Set.of("fcm.googleapis.com", "updates.push.services.mozilla.com", "web.push.apple.com");
    private static final List<String> PUSH_SUFFIXES = List.of(".push.apple.com", ".notify.windows.com", ".push.services.mozilla.com");

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final String subject;
    private final Set<String> extraHosts;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor();
    private volatile KeyPair keys;

    PushService(JdbcClient jdbc, JsonMapper json, @Value("${payledger.push.subject:mailto:hello@payledger.app}") String subject,
                @Value("${payledger.push.extra-hosts:}") Set<String> extraHosts) {
        this.jdbc = jdbc;
        this.json = json;
        this.subject = subject;
        this.extraHosts = extraHosts;
    }

    public record Subscription(String endpoint, String p256dh, String auth) {
    }

    /** The server's public key, for the browser's subscribe call. */
    public String publicKey() {
        return WebPushCrypto.b64(WebPushCrypto.raw((ECPublicKey) keys().getPublic()));
    }

    public void subscribe(UUID userId, Subscription s, String userAgent) {
        URI uri;
        try {
            uri = URI.create(s.endpoint());
        } catch (Exception e) {
            throw new InvalidRequestException("invalid-subscription", "that isn't a push address");
        }
        if (!allowed(uri)) {
            throw new InvalidRequestException("invalid-subscription", "that isn't a known push service");
        }
        try {
            WebPushCrypto.publicKey(WebPushCrypto.unb64(s.p256dh()));
            if (WebPushCrypto.unb64(s.auth()).length != 16) {
                throw new IllegalArgumentException();
            }
        } catch (Exception e) {
            throw new InvalidRequestException("invalid-subscription", "the browser's keys don't look right");
        }
        // One endpoint is one device: re-subscribing (or another person signing in on it) takes it over.
        jdbc.sql("""
                        insert into push_subscriptions (id, user_id, endpoint, p256dh, auth, user_agent)
                        values (:id, :u, :e, :k, :a, :ua)
                        on conflict (endpoint) do update set user_id = excluded.user_id, p256dh = excluded.p256dh, auth = excluded.auth,
                            user_agent = excluded.user_agent, failures = 0
                        """).param("id", Uuid7.next()).param("u", userId).param("e", s.endpoint()).param("k", s.p256dh()).param("a", s.auth())
                .param("ua", userAgent == null ? null : userAgent.substring(0, Math.min(300, userAgent.length()))).update();
    }

    public void unsubscribe(UUID userId, String endpoint) {
        jdbc.sql("delete from push_subscriptions where user_id = :u and endpoint = :e").param("u", userId).param("e", endpoint).update();
    }

    public int devices(UUID userId) {
        return jdbc.sql("select count(*)::int from push_subscriptions where user_id = :u").param("u", userId).query(Integer.class).single();
    }

    /**
     * Pushes a notification to all of a person's devices, once the current transaction commits (nothing is sent for a
     * change that rolled back). {@code tag} groups notifications of a kind, so a newer one replaces an older one.
     */
    public void publish(UUID userId, String title, String body, String link, String tag) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("body", body == null ? "" : body);
        payload.put("link", link == null ? "/dashboard" : link);
        payload.put("tag", tag);
        byte[] message = json.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
        Runnable send = () -> senders.submit(() -> sendNow(userId, message));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    /** Sends to each device and returns how many took it. */
    int sendNow(UUID userId, byte[] message) {
        record Row(UUID id, String endpoint, String p256dh, String auth) {
        }
        int ok = 0;
        for (Row r : jdbc.sql("select id, endpoint, p256dh, auth from push_subscriptions where user_id = :u").param("u", userId).query(Row.class).list()) {
            try {
                byte[] body = WebPushCrypto.encrypt(message, WebPushCrypto.unb64(r.p256dh()), WebPushCrypto.unb64(r.auth()));
                HttpRequest request = HttpRequest.newBuilder(URI.create(r.endpoint())).timeout(Duration.ofSeconds(10))
                        .header("Content-Encoding", "aes128gcm").header("Content-Type", "application/octet-stream")
                        .header("TTL", "86400").header("Urgency", "high")
                        .header("Authorization", WebPushCrypto.vapid(r.endpoint(), subject, (ECPrivateKey) keys().getPrivate(),
                                WebPushCrypto.raw((ECPublicKey) keys().getPublic()), System.currentTimeMillis() / 1000))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
                int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status == 404 || status == 410) {
                    jdbc.sql("delete from push_subscriptions where id = :id").param("id", r.id()).update();
                } else if (status >= 200 && status < 300) {
                    jdbc.sql("update push_subscriptions set last_ok_at = now(), failures = 0 where id = :id").param("id", r.id()).update();
                    ok++;
                } else {
                    failed(r.id(), "status " + status);
                }
            } catch (Exception e) {
                failed(r.id(), e.getMessage());
            }
        }
        return ok;
    }

    private void failed(UUID id, String why) {
        log.debug("Push to {} failed: {}", id, why);
        // A device that keeps failing (uninstalled, keys reset) is dropped after ten tries.
        jdbc.sql("update push_subscriptions set failures = failures + 1 where id = :id").param("id", id).update();
        jdbc.sql("delete from push_subscriptions where id = :id and failures >= 10").param("id", id).update();
    }

    boolean allowed(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        if (extraHosts.contains(host)) {
            return true;
        }
        return "https".equals(uri.getScheme()) && (PUSH_HOSTS.contains(host) || PUSH_SUFFIXES.stream().anyMatch(host::endsWith));
    }

    /** Made once, on first use, and kept: every subscription is tied to this key. */
    private KeyPair keys() {
        KeyPair k = keys;
        if (k != null) {
            return k;
        }
        synchronized (this) {
            if (keys == null) {
                record Stored(String publicKey, String privateKey) {
                }
                Stored s = jdbc.sql("select public_key, private_key from push_keys where id = 1").query(Stored.class).optional().orElse(null);
                if (s == null) {
                    KeyPair fresh = WebPushCrypto.newKeyPair();
                    jdbc.sql("insert into push_keys (id, public_key, private_key) values (1, :pub, :priv) on conflict (id) do nothing")
                            .param("pub", WebPushCrypto.b64(WebPushCrypto.raw((ECPublicKey) fresh.getPublic())))
                            .param("priv", WebPushCrypto.b64(WebPushCrypto.raw((ECPrivateKey) fresh.getPrivate()))).update();
                    s = jdbc.sql("select public_key, private_key from push_keys where id = 1").query(Stored.class).single();
                }
                keys = new KeyPair(WebPushCrypto.publicKey(WebPushCrypto.unb64(s.publicKey())), WebPushCrypto.privateKey(WebPushCrypto.unb64(s.privateKey())));
                Arrays.fill(new byte[0], (byte) 0);
            }
            return keys;
        }
    }
}
