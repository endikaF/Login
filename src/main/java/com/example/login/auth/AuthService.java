package com.example.login.auth;

import java.util.Locale;

import com.example.login.security.PasswordPolicy;
import com.example.login.user.User;
import com.example.login.user.UserRepository;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    /** Resultado de login/refresh: access token (JSON) y refresh token (solo cookie). */
    public record Tokens(String accessToken, long expiresInSeconds, String refreshToken) {
    }

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final RefreshTokenService refreshTokens;
    /**
     * Hash Argon2id de una contraseña que nadie conoce. Se compara cuando el usuario NO existe para que el
     * login tarde aproximadamente lo mismo exista o no la cuenta (evita enumerar usuarios midiendo tiempos).
     */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt,
                       RefreshTokenService refreshTokens) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.refreshTokens = refreshTokens;
        this.dummyHash = encoder.encode(java.util.UUID.randomUUID().toString());
    }

    public Tokens login(String email, String rawPassword) {
        // Contraseñas absurdamente largas: se descartan sin hashear (anti-DoS). Misma respuesta genérica.
        if (rawPassword == null || rawPassword.length() > PasswordPolicy.MAX_LENGTH) {
            throw new InvalidCredentialsException();
        }
        User user = users.findByEmail(email.trim().toLowerCase(Locale.ROOT)).orElse(null);

        // Siempre se ejecuta una verificación Argon2, exista el usuario o no.
        boolean passwordOk = encoder.matches(rawPassword, user != null ? user.passwordHash() : dummyHash);

        // Misma excepción para: usuario inexistente, contraseña mala y cuenta desactivada.
        if (user == null || !passwordOk || !user.enabled()) {
            throw new InvalidCredentialsException();
        }
        return new Tokens(jwt.issue(user.id(), user.tokenVersion()), jwt.accessTtl().toSeconds(),
                refreshTokens.startSession(user.id()));
    }

    public Tokens refresh(String refreshToken) {
        var rotation = refreshTokens.rotate(refreshToken);
        User user = rotation.user();
        return new Tokens(jwt.issue(user.id(), user.tokenVersion()), jwt.accessTtl().toSeconds(),
                rotation.newToken());
    }

    public void logout(String refreshToken) {
        refreshTokens.revokeSession(refreshToken);
    }

    /**
     * Cierre global: revoca todos los refresh tokens y sube token_version, con lo que los access token
     * ya emitidos (aún no caducados) también dejan de valer en la siguiente petición.
     */
    public void logoutAll(long userId) {
        refreshTokens.revokeAll(userId);
        users.incrementTokenVersion(userId);
    }
}
