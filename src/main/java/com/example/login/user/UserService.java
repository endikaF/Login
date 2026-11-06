package com.example.login.user;

import java.time.Duration;
import java.util.Locale;

import com.example.login.mail.AccountMailer;
import com.example.login.security.PasswordPolicy;
import com.example.login.token.OneTimeTokenService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final OneTimeTokenService tokens;
    private final AccountMailer mailer;
    private final Duration verifyTtl;

    public UserService(UserRepository users, PasswordEncoder encoder, OneTimeTokenService tokens,
                       AccountMailer mailer, @Value("${app.tokens.verify-email-hours}") long verifyHours) {
        this.users = users;
        this.encoder = encoder;
        this.tokens = tokens;
        this.mailer = mailer;
        this.verifyTtl = Duration.ofHours(verifyHours);
    }

    /**
     * Registro público: siempre rol USER (el cliente nunca decide su rol) y email sin verificar.
     * Para no revelar qué emails tienen cuenta, el resultado es el mismo exista o no: no lanza error por
     * duplicado y tarda parecido, porque se calcula el hash Argon2 en ambos casos. El dueño del email se
     * entera por correo (verificación si es nuevo, aviso "ya tienes cuenta" si ya existía).
     */
    public void register(String email, String rawPassword) {
        PasswordPolicy.validate(rawPassword);
        String normalized = normalize(email);
        String hash = encoder.encode(rawPassword); // siempre, para igualar tiempos
        if (users.existsByEmail(normalized)) {
            mailer.sendAlreadyRegistered(normalized);
            return;
        }
        try {
            users.insert(normalized, hash, Role.USER, false);
        } catch (EmailAlreadyRegisteredException e) {
            mailer.sendAlreadyRegistered(normalized); // otro registro idéntico ganó la carrera
            return;
        }
        User created = users.findByEmail(normalized).orElseThrow();
        mailer.sendVerification(normalized,
                tokens.issue(created.id(), OneTimeTokenService.Purpose.VERIFY_EMAIL, verifyTtl));
    }

    /** Alta de usuario con rol explícito; solo la usa el arranque para el primer ADMIN. */
    public void create(String email, String rawPassword, Role role, boolean emailVerified) {
        // Política ANTES de hashear: así una contraseña enorme no llega a consumir CPU/memoria de Argon2.
        PasswordPolicy.validate(rawPassword);
        // Normalizar evita cuentas duplicadas "A@x.com" / "a@x.com" (además de COLLATE NOCASE en BD).
        String normalized = normalize(email);
        if (users.existsByEmail(normalized)) {
            throw new EmailAlreadyRegisteredException();
        }
        users.insert(normalized, encoder.encode(rawPassword), role, emailVerified);
    }

    public boolean existsByEmail(String email) {
        return users.existsByEmail(normalize(email));
    }

    static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
