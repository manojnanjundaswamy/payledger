package dev.payledger.core.push;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;

import static dev.payledger.core.push.WebPushCrypto.b64;
import static dev.payledger.core.push.WebPushCrypto.unb64;
import static org.assertj.core.api.Assertions.assertThat;

class WebPushCryptoTest {

    /** RFC 8291, Appendix A: the published example, byte for byte. */
    @Test
    void encryptsExactlyAsTheRfcExample() {
        KeyPair as = WebPushCrypto.keyPair(unb64("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw"),
                unb64("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8"));
        byte[] body = WebPushCrypto.encrypt("When I grow up, I want to be a watermelon".getBytes(StandardCharsets.UTF_8),
                unb64("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"),
                unb64("BTBZMqHH6r4Tts7J_aSIgg"), as, unb64("DGv6ra1nlYgDCS1FRnbzlw"));
        assertThat(b64(body)).isEqualTo("DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN");
    }

    @Test
    void aBrowserCanReadWhatWasSent() {
        KeyPair browser = WebPushCrypto.newKeyPair();
        byte[] uaPublic = WebPushCrypto.raw((ECPublicKey) browser.getPublic());
        byte[] auth = new byte[16];
        Arrays.fill(auth, (byte) 7);
        byte[] body = WebPushCrypto.encrypt("{\"title\":\"Rent due\"}".getBytes(StandardCharsets.UTF_8), uaPublic, auth);
        assertThat(new String(WebPushCrypto.decrypt(body, (ECPrivateKey) browser.getPrivate(), uaPublic, auth), StandardCharsets.UTF_8))
                .isEqualTo("{\"title\":\"Rent due\"}");
    }

    @Test
    void theVapidTokenIsSignedForThePushServicesOrigin() throws Exception {
        KeyPair server = WebPushCrypto.newKeyPair();
        byte[] pub = WebPushCrypto.raw((ECPublicKey) server.getPublic());
        String header = WebPushCrypto.vapid("https://fcm.googleapis.com/fcm/send/abc", "mailto:hello@example.com",
                (ECPrivateKey) server.getPrivate(), pub, 1_800_000_000L);
        assertThat(header).startsWith("vapid t=").endsWith(", k=" + b64(pub));
        String jwt = header.substring("vapid t=".length(), header.indexOf(','));
        String[] parts = jwt.split("\\.");
        assertThat(new String(unb64(parts[1]), StandardCharsets.UTF_8))
                .contains("\"aud\":\"https://fcm.googleapis.com\"").contains("\"exp\":1800043200").contains("mailto:hello@example.com");
        Signature verify = Signature.getInstance("SHA256withECDSAinP1363Format");
        verify.initVerify(server.getPublic());
        verify.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verify.verify(unb64(parts[2]))).isTrue();
        assertThat(WebPushCrypto.privateKey(WebPushCrypto.raw((ECPrivateKey) server.getPrivate())).getS())
                .isEqualTo(((ECPrivateKey) server.getPrivate()).getS());
    }
}
