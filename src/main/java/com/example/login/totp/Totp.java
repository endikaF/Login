package com.example.login.totp;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.OptionalLong;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TOTP según RFC 6238 (sobre HOTP, RFC 4226): HMAC-SHA1 de un contador de tiempo con el secreto compartido.
 * Usa solo el HMAC estándar del JDK; no se inventa ninguna primitiva criptográfica. SHA-1 aquí es correcto: en
 * un HMAC no le afectan las colisiones, y es lo que soportan todas las apps de autenticación (Google Authenticator,
 * Aegis, Authy...). Los parámetros (30 s, 6 dígitos, SHA-1) son los que esas apps esperan por defecto.
 */
public final class Totp {

    public static final int STEP_SECONDS = 30;
    public static final int DIGITS = 6;
    /** Se aceptan el intervalo actual y uno a cada lado (±30 s) para tolerar desfases de reloj. */
    static final int WINDOW = 1;
    private static final int SECRET_BYTES = 20; // 160 bits, el tamaño recomendado por RFC 4226
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    public static long stepAt(Instant time) {
        return Math.floorDiv(time.getEpochSecond(), STEP_SECONDS);
    }

    /** Código de {@code digits} dígitos para un intervalo (RFC 4226, truncado dinámico). */
    public static String code(byte[] key, long step, int digits) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
            int mod = (int) Math.pow(10, digits);
            return String.format("%0" + digits + "d", binary % mod);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 no disponible", e);
        }
    }

    public static String code(byte[] key, Instant time) {
        return code(key, stepAt(time), DIGITS);
    }

    /**
     * Comprueba un código contra la ventana de intervalos y devuelve el intervalo que coincide, solo si es MAYOR
     * que {@code lastUsedStep} (así un mismo código no se puede usar dos veces ni uno anterior al último aceptado).
     * Se comparan los tres candidatos siempre y en tiempo constante, sin salir al primer acierto.
     */
    public static OptionalLong verify(byte[] key, String submitted, Instant now, long lastUsedStep) {
        if (submitted == null || !submitted.matches("\\d{" + DIGITS + "}")) {
            return OptionalLong.empty();
        }
        byte[] given = submitted.getBytes(StandardCharsets.US_ASCII);
        long current = stepAt(now);
        long matched = -1;
        for (long step = current - WINDOW; step <= current + WINDOW; step++) {
            byte[] expected = code(key, step, DIGITS).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(expected, given) && step > lastUsedStep) {
                matched = Math.max(matched, step);
            }
        }
        return matched >= 0 ? OptionalLong.of(matched) : OptionalLong.empty();
    }

    public static String newSecretBase32() {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return encodeBase32(secret);
    }

    /** URI estándar que leen las apps de autenticación (se puede mostrar como texto o convertir en QR). */
    public static String otpauthUri(String issuer, String account, String base32Secret) {
        return "otpauth://totp/" + encode(issuer) + ":" + encode(account) + "?secret=" + base32Secret
                + "&issuer=" + encode(issuer) + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // ---- Base32 (RFC 4648), sin relleno: es la codificación de texto que usan las apps; no es criptografía ----

    public static String encodeBase32(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 0x1F));
        }
        return out.toString();
    }

    public static byte[] decodeBase32(String text) {
        String clean = text.toUpperCase(Locale.ROOT).replaceAll("[=\\s-]", "");
        byte[] out = new byte[clean.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (char c : clean.toCharArray()) {
            int value = BASE32.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("Carácter Base32 no válido");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out[index++] = (byte) ((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out;
    }
}
