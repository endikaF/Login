package com.example.login.auth;

import java.time.Duration;
import java.util.Locale;

import com.example.login.audit.AuditEvent;
import com.example.login.audit.AuditService;
import com.example.login.mail.AccountMailer;
import com.example.login.security.LoginAttemptService;
import com.example.login.security.PasswordPolicy;
import com.example.login.security.RateLimitService;
import com.example.login.token.OneTimeTokenService;
import com.example.login.token.OneTimeTokenService.Purpose;
import com.example.login.user.User;
import com.example.login.user.UserRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Verificación de email y recuperación de contraseña, sobre el mismo mecanismo de token de un solo uso. */
@Service
public class AccountRecoveryService {

    private final UserRepository users;
    private final OneTimeTokenService tokens;
    private final AccountMailer mailer;
    private final PasswordEncoder encoder;
    private final RefreshTokenService refreshTokens;
    private final LoginAttemptService attempts;
    private final RateLimitService rateLimit;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Duration verifyTtl;
    private final Duration resetTtl;

    public AccountRecoveryService(UserRepository users, OneTimeTokenService tokens, AccountMailer mailer,
                                  PasswordEncoder encoder, RefreshTokenService refreshTokens,
                                  LoginAttemptService attempts, RateLimitService rateLimit, AuditService audit,
                                  TransactionTemplate tx,
                                  @Value("${app.tokens.verify-email-hours}") long verifyHours,
                                  @Value("${app.tokens.reset-minutes}") long resetMinutes) {
        this.users = users;
        this.tokens = tokens;
        this.mailer = mailer;
        this.encoder = encoder;
        this.refreshTokens = refreshTokens;
        this.attempts = attempts;
        this.rateLimit = rateLimit;
        this.audit = audit;
        this.tx = tx;
        this.verifyTtl = Duration.ofHours(verifyHours);
        this.resetTtl = Duration.ofMinutes(resetMinutes);
    }

    /**
     * Pide un enlace de recuperación. El controlador responde SIEMPRE lo mismo; aquí solo se envía el correo si
     * la cuenta existe y está activa. Los límites se aplican por IP y por destinatario (exista o no).
     * [TRADE-OFF] Queda una diferencia de milisegundos entre existir y no existir (crear el token y "enviar").
     * Igualarla del todo exigiría procesar el envío en segundo plano; se deja como simplificación.
     */
    public void requestPasswordReset(String email, String ip) {
        String normalized = normalize(email);
        limitMail(normalized, ip);
        User user = users.findByEmail(normalized).filter(User::enabled).orElse(null);
        if (user == null) {
            audit.record(AuditEvent.PASSWORD_RESET_REQUESTED, null, ip, "no_account");
            return;
        }
        mailer.sendPasswordReset(user.email(), tokens.issue(user.id(), Purpose.RESET_PASSWORD, resetTtl));
        audit.record(AuditEvent.PASSWORD_RESET_REQUESTED, user.id(), ip, null);
    }

    /** Cambia la contraseña con el enlace. Todo o nada: token, contraseña y revocación de sesiones en una transacción. */
    public void resetPassword(String rawToken, String newPassword, String ip) {
        limitToken(ip);
        // La política se valida ANTES de consumir el token: una contraseña floja no debe quemar el enlace.
        PasswordPolicy.validate(newPassword);
        String hash = encoder.encode(newPassword); // fuera de la transacción: Argon2 es lento y SQLite tiene un solo escritor

        User changed;
        try {
            changed = tx.execute(status -> {
                long id = tokens.consume(rawToken, Purpose.RESET_PASSWORD).orElseThrow(InvalidTokenException::new);
                User user = users.findById(id).filter(User::enabled).orElseThrow(InvalidTokenException::new);
                users.updatePassword(user.id(), hash);
                // Haber recibido el enlace demuestra que controla el email.
                users.markEmailVerified(user.id());
                // Se anula cualquier sesión previa: si alguien tenía la contraseña vieja (o una sesión robada), queda fuera.
                refreshTokens.revokeAll(user.id());
                users.incrementTokenVersion(user.id()); // los access token vigentes dejan de valer
                attempts.recordSuccess(user.id());      // limpia también fallos y bloqueos acumulados
                tokens.invalidateAll(user.id(), Purpose.RESET_PASSWORD);
                return user;
            });
        } catch (InvalidTokenException e) {
            audit.record(AuditEvent.PASSWORD_RESET_FAILED, null, ip, "invalid_token");
            throw e;
        }
        audit.record(AuditEvent.PASSWORD_RESET_DONE, changed.id(), ip, null);
        mailer.sendPasswordChanged(changed.email()); // fuera de la transacción: el envío no debe retenerla
    }

    public void verifyEmail(String rawToken, String ip) {
        limitToken(ip);
        Long userId = tx.execute(status -> {
            long id = tokens.consume(rawToken, Purpose.VERIFY_EMAIL).orElseThrow(InvalidTokenException::new);
            users.markEmailVerified(id);
            return id;
        });
        audit.record(AuditEvent.EMAIL_VERIFIED, userId, ip, null);
    }

    /** Reenvío del enlace de verificación (el primero puede caducar). Respuesta idéntica exista o no la cuenta. */
    public void resendVerification(String email, String ip) {
        String normalized = normalize(email);
        limitMail(normalized, ip);
        users.findByEmail(normalized).filter(User::enabled).filter(u -> !u.emailVerified())
                .ifPresent(u -> mailer.sendVerification(u.email(),
                        tokens.issue(u.id(), Purpose.VERIFY_EMAIL, verifyTtl)));
    }

    private void limitMail(String normalizedEmail, String ip) {
        try {
            rateLimit.checkMailIp(ip);
            rateLimit.checkMailAccount(normalizedEmail);
        } catch (RateLimitService.RateLimitedException e) {
            audit.record(AuditEvent.RATE_LIMITED, null, ip, "mail");
            throw e;
        }
    }

    private void limitToken(String ip) {
        try {
            rateLimit.checkTokenIp(ip);
        } catch (RateLimitService.RateLimitedException e) {
            audit.record(AuditEvent.RATE_LIMITED, null, ip, "token");
            throw e;
        }
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
