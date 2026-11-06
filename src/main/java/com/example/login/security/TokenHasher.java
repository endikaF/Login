package com.example.login.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Tokens opacos: 256 bits aleatorios del SecureRandom del JDK y SHA-256 para guardarlos.
 * SHA-256 (rápido) basta aquí porque el token ya tiene entropía de sobra: no se puede adivinar ni
 * atacar por diccionario como una contraseña, así que no hace falta un hash lento tipo Argon2.
 * Si se filtra la BD, los hashes no permiten reconstruir ningún token válido.
 */
public final class TokenHasher {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TokenHasher() {
    }

    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256Hex(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
