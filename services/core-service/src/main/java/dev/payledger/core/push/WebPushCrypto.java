package dev.payledger.core.push;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * The cryptography of Web Push, with the JDK alone: message encryption (RFC 8291, the aes128gcm content coding of
 * RFC 8188) and the server's signed identity (VAPID, RFC 8292). Tested against RFC 8291's published example.
 */
public final class WebPushCrypto {

    private WebPushCrypto() {
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    static final ECParameterSpec P256 = p256();
    private static final byte[] KEY_INFO = "WebPush: info".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CEK_INFO = "Content-Encoding: aes128gcm".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NONCE_INFO = "Content-Encoding: nonce".getBytes(StandardCharsets.US_ASCII);

    // ---------------------------------------------------------------- keys

    public static KeyPair newKeyPair() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 0x04 || X || Y: how Web Push writes a P-256 public key. */
    public static byte[] raw(ECPublicKey key) {
        byte[] out = new byte[65];
        out[0] = 4;
        copy32(key.getW().getAffineX(), out, 1);
        copy32(key.getW().getAffineY(), out, 33);
        return out;
    }

    public static byte[] raw(ECPrivateKey key) {
        byte[] out = new byte[32];
        copy32(key.getS(), out, 0);
        return out;
    }

    public static ECPublicKey publicKey(byte[] raw) {
        if (raw.length != 65 || raw[0] != 4) {
            throw new IllegalArgumentException("not an uncompressed P-256 point");
        }
        try {
            ECPoint w = new ECPoint(new BigInteger(1, Arrays.copyOfRange(raw, 1, 33)), new BigInteger(1, Arrays.copyOfRange(raw, 33, 65)));
            return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(w, P256));
        } catch (Exception e) {
            throw new IllegalArgumentException("not a valid P-256 public key", e);
        }
    }

    public static ECPrivateKey privateKey(byte[] raw) {
        try {
            return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, raw), P256));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    // ---------------------------------------------------------------- RFC 8291

    /** Encrypts with a fresh key pair and salt. */
    public static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return encrypt(plaintext, uaPublic, authSecret, newKeyPair(), salt);
    }

    /** The aes128gcm body: salt, record size, the server's public key, then one encrypted record. */
    static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret, KeyPair as, byte[] salt) {
        try {
            byte[] asPublic = raw((ECPublicKey) as.getPublic());
            byte[][] keys = keys(ecdh(as.getPrivate(), uaPublic), authSecret, uaPublic, asPublic, salt);
            Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
            gcm.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keys[0], "AES"), new GCMParameterSpec(128, keys[1]));
            // One record: the message and the last-record delimiter (2), no padding.
            byte[] record = gcm.doFinal(concat(plaintext, new byte[]{2}));
            ByteBuffer header = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length);
            header.put(salt).putInt(4096).put((byte) asPublic.length).put(asPublic);
            return concat(header.array(), record);
        } catch (Exception e) {
            throw new IllegalStateException("could not encrypt the push message", e);
        }
    }

    /** The receiving side, as a browser does it (for tests). */
    public static byte[] decrypt(byte[] body, ECPrivateKey uaPrivate, byte[] uaPublic, byte[] authSecret) {
        try {
            byte[] salt = Arrays.copyOfRange(body, 0, 16);
            int idLen = body[20] & 0xff;
            byte[] asPublic = Arrays.copyOfRange(body, 21, 21 + idLen);
            byte[][] keys = keys(ecdh(uaPrivate, asPublic), authSecret, uaPublic, asPublic, salt);
            Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
            gcm.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keys[0], "AES"), new GCMParameterSpec(128, keys[1]));
            byte[] padded = gcm.doFinal(Arrays.copyOfRange(body, 21 + idLen, body.length));
            int end = padded.length - 1;
            while (end >= 0 && padded[end] == 0) {
                end--;
            }
            return Arrays.copyOf(padded, end);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] ecdh(java.security.PrivateKey mine, byte[] theirPublic) throws Exception {
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(mine);
        ka.doPhase(publicKey(theirPublic), true);
        return ka.generateSecret();
    }

    /** {content key, nonce} from the shared secret (RFC 8291 section 3.4, RFC 8188 section 2.2). */
    private static byte[][] keys(byte[] ecdh, byte[] authSecret, byte[] uaPublic, byte[] asPublic, byte[] salt) throws Exception {
        byte[] ikm = hmac(hmac(authSecret, ecdh), concat(KEY_INFO, new byte[]{0}, uaPublic, asPublic, new byte[]{1}));
        byte[] prk = hmac(salt, ikm);
        return new byte[][]{
                Arrays.copyOf(hmac(prk, concat(CEK_INFO, new byte[]{0, 1})), 16),
                Arrays.copyOf(hmac(prk, concat(NONCE_INFO, new byte[]{0, 1})), 12)};
    }

    // ---------------------------------------------------------------- RFC 8292

    /** The Authorization header: a 12-hour JWT for the push service's origin, signed with the server's key. */
    public static String vapid(String endpoint, String subject, ECPrivateKey key, byte[] publicKey, long nowSeconds) {
        try {
            java.net.URI uri = java.net.URI.create(endpoint);
            String audience = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
            String header = b64("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
            String claims = b64(("{\"aud\":\"" + audience + "\",\"exp\":" + (nowSeconds + 12 * 3600) + ",\"sub\":\"" + subject + "\"}")
                    .getBytes(StandardCharsets.UTF_8));
            Signature es256 = Signature.getInstance("SHA256withECDSAinP1363Format");
            es256.initSign(key);
            es256.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
            return "vapid t=" + header + "." + claims + "." + b64(es256.sign()) + ", k=" + b64(publicKey);
        } catch (Exception e) {
            throw new IllegalStateException("could not sign the push request", e);
        }
    }

    // ---------------------------------------------------------------- helpers

    public static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static byte[] unb64(String s) {
        return Base64.getUrlDecoder().decode(s.replace('+', '-').replace('/', '_').replace("=", ""));
    }

    private static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    private static void copy32(BigInteger n, byte[] out, int at) {
        byte[] b = n.toByteArray();
        int len = Math.min(b.length, 32);
        System.arraycopy(b, b.length - len, out, at + 32 - len, len);
    }

    private static ECParameterSpec p256() {
        try {
            AlgorithmParameters p = AlgorithmParameters.getInstance("EC");
            p.init(new ECGenParameterSpec("secp256r1"));
            return p.getParameterSpec(ECParameterSpec.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** For RFC 8291's example: a key pair from its raw private and public parts. */
    static KeyPair keyPair(byte[] rawPrivate, byte[] rawPublic) {
        return new KeyPair(publicKey(rawPublic), privateKey(rawPrivate));
    }
}
