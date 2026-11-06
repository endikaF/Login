package com.example.login.totp;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Pattern;

import com.example.login.audit.AuditEvent;
import com.example.login.audit.AuditService;
import com.example.login.auth.AuthenticatedUser;
import com.example.login.mail.AccountMailer;
import com.example.login.security.LoginAttemptService;
import com.example.login.security.RateLimitService;
import com.example.login.security.TokenHasher;
import com.example.login.totp.TotpExceptions.ReauthenticationFailedException;
import com.example.login.totp.TotpExceptions.TotpStateException;
import com.example.login.user.User;
import com.example.login.user.UserRepository;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Segundo factor TOTP: alta, baja, verificación en el login y códigos de recuperación.
 * Regla común: cada intento FALLIDO de código o de contraseña cuenta para el bloqueo progresivo de la cuenta y
 * para el límite de intentos, igual que en el login. Con solo 10^6 códigos posibles, sin esto se podría adivinar.
 */
@Service
public class TotpService {

    static final String ISSUER = "Tablero de notas";
    private static final Pattern SIX_DIGITS = Pattern.compile("\\d{6}");
    private static final int RECOVERY_CODES = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Setup(String secret, String otpauthUri) {
    }

    private final TotpRepository repo;
    private final TotpSecretCipher cipher;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final LoginAttemptService attempts;
    private final RateLimitService rateLimit;
    private final AuditService audit;
    private final AccountMailer mailer;
    private final Clock clock;
    private final TransactionTemplate tx;

    public TotpService(TotpRepository repo, TotpSecretCipher cipher, UserRepository users, PasswordEncoder encoder,
                       LoginAttemptService attempts, RateLimitService rateLimit, AuditService audit,
                       AccountMailer mailer, Clock clock, TransactionTemplate tx) {
        this.repo = repo;
        this.cipher = cipher;
        this.users = users;
        this.encoder = encoder;
        this.attempts = attempts;
        this.rateLimit = rateLimit;
        this.audit = audit;
        this.mailer = mailer;
        this.clock = clock;
        this.tx = tx;
    }

    public boolean isEnabled(long userId) {
        return repo.isEnabled(userId);
    }

    /**
     * Paso 1 de la activación: genera un secreto nuevo (PENDIENTE hasta confirmarlo con un código). Pide la contraseña
     * aunque ya haya sesión: con solo un access token robado (10 min de vida) no se puede activar un 2FA ajeno y dejar
     * fuera al dueño.
     */
    public Setup setup(AuthenticatedUser me, String password, String ip) {
        User user = confirmPassword(me, password, ip);
        if (repo.find(user.id()).filter(TotpRepository.Record::enabled).isPresent()) {
            throw new TotpStateException();
        }
        String secret = Totp.newSecretBase32();
        repo.savePending(user.id(), cipher.encrypt(secret));
        return new Setup(secret, Totp.otpauthUri(ISSUER, user.email(), secret));
    }

    /**
     * Paso 2: confirma que la app del usuario genera códigos válidos y activa el 2FA. Devuelve los códigos de
     * recuperación EN CLARO por única vez (en BD solo queda su hash).
     */
    public List<String> enable(AuthenticatedUser me, String code, String ip) {
        User user = loadUser(me);
        limitAccount(user, ip);
        var pending = repo.find(user.id()).filter(r -> !r.enabled()).orElseThrow(TotpStateException::new);
        OptionalLong step = Totp.verify(Totp.decodeBase32(cipher.decrypt(pending.secretEnc())), code, clock.instant(), 0);
        if (step.isEmpty()) {
            recordFailure(user, ip, "enable");
            throw new ReauthenticationFailedException();
        }
        List<String> codes = newRecoveryCodes();
        tx.executeWithoutResult(status -> {
            repo.enable(user.id(), step.getAsLong()); // el código usado queda "gastado" y no se acepta en el login
            repo.replaceRecoveryCodes(user.id(), codes.stream().map(TotpService::hashRecovery).toList());
        });
        audit.record(AuditEvent.TOTP_ENABLED, user.id(), ip, null);
        mailer.sendTotpChanged(user.email(), true);
        return codes;
    }

    /** Desactiva el 2FA. Exige contraseña Y un código (o de recuperación): quitar un factor es una acción sensible. */
    public void disable(AuthenticatedUser me, String password, String code, String ip) {
        User user = confirmPassword(me, password, ip);
        if (!repo.isEnabled(user.id())) {
            throw new TotpStateException();
        }
        if (!verifyCode(user.id(), code, ip)) {
            recordFailure(user, ip, "disable");
            throw new ReauthenticationFailedException();
        }
        repo.deleteAll(user.id());
        audit.record(AuditEvent.TOTP_DISABLED, user.id(), ip, null);
        mailer.sendTotpChanged(user.email(), false);
    }

    /**
     * Verifica el segundo factor del login (o de la baja): un código TOTP de 6 dígitos o un código de recuperación.
     * Un código TOTP solo vale una vez (se reserva su intervalo de forma atómica) y uno de recuperación también.
     */
    public boolean verifyCode(long userId, String submitted, String ip) {
        if (submitted == null || submitted.length() > 40) {
            return false;
        }
        String code = submitted.trim();
        if (SIX_DIGITS.matcher(code).matches()) {
            var record = repo.find(userId).filter(TotpRepository.Record::enabled).orElse(null);
            if (record == null) {
                return false;
            }
            OptionalLong step = Totp.verify(Totp.decodeBase32(cipher.decrypt(record.secretEnc())), code,
                    clock.instant(), record.lastUsedStep());
            return step.isPresent() && repo.claimStep(userId, step.getAsLong());
        }
        String normalized = normalizeRecovery(code);
        if (normalized.length() == 16 && repo.consumeRecoveryCode(userId, TokenHasher.sha256Hex(normalized))) {
            audit.record(AuditEvent.TOTP_RECOVERY_USED, userId, ip, null);
            return true;
        }
        return false;
    }

    /** Uso administrativo: quita el 2FA de un usuario que perdió el dispositivo y los códigos de recuperación. */
    public void reset(long userId) {
        repo.deleteAll(userId);
    }

    /** Cuenta un fallo del segundo factor (bloqueo progresivo + auditoría). Lo usa también el login. */
    public void recordFailure(User user, String ip, String detail) {
        boolean locked = attempts.recordFailure(user.id());
        audit.record(AuditEvent.TOTP_FAILED, user.id(), ip, detail);
        if (locked) {
            audit.record(AuditEvent.ACCOUNT_LOCKED, user.id(), ip, "too_many_failures");
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    private User confirmPassword(AuthenticatedUser me, String password, String ip) {
        User user = loadUser(me);
        limitAccount(user, ip);
        if (user.isLocked(clock.instant()) || password == null || password.length() > 128
                || !encoder.matches(password, user.passwordHash())) {
            if (!user.isLocked(clock.instant())) {
                recordFailure(user, ip, "password");
            }
            throw new ReauthenticationFailedException();
        }
        return user;
    }

    private User loadUser(AuthenticatedUser me) {
        return users.findById(me.id()).orElseThrow(ReauthenticationFailedException::new);
    }

    private void limitAccount(User user, String ip) {
        try {
            rateLimit.checkLoginAccount(user.email());
        } catch (RateLimitService.RateLimitedException e) {
            audit.record(AuditEvent.RATE_LIMITED, user.id(), ip, "totp");
            throw e;
        }
    }

    /** 8 códigos de 80 bits (16 caracteres Base32), mostrados como XXXX-XXXX-XXXX-XXXX. */
    private static List<String> newRecoveryCodes() {
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            byte[] bytes = new byte[10];
            RANDOM.nextBytes(bytes);
            String raw = Totp.encodeBase32(bytes);
            codes.add(raw.substring(0, 4) + "-" + raw.substring(4, 8) + "-" + raw.substring(8, 12) + "-" + raw.substring(12));
        }
        return codes;
    }

    private static String normalizeRecovery(String code) {
        return code.toUpperCase(Locale.ROOT).replaceAll("[^A-Z2-7]", "");
    }

    private static String hashRecovery(String formatted) {
        return TokenHasher.sha256Hex(normalizeRecovery(formatted));
    }
}
