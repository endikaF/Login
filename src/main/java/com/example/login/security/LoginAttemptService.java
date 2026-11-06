package com.example.login.security;

import java.time.Duration;
import java.time.Instant;

import com.example.login.user.UserRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bloqueo temporal progresivo de cuentas tras fallos de contraseña consecutivos.
 * Tras N fallos la cuenta se bloquea 1 min; el siguiente bloqueo dura 2, luego 4... hasta un tope (30 min).
 * Un login correcto lo reinicia todo. El estado vive en la BD (sobrevive a reinicios, a diferencia del rate limit).
 * [TRADE-OFF] Esto permite que un atacante bloquee a propósito la cuenta de otra persona (denegación de
 * servicio dirigida). Por eso los bloqueos son cortos, progresivos y con tope, no permanentes; la alternativa
 * (CAPTCHA o bloqueo por pareja IP+cuenta) queda fuera del alcance de este proyecto.
 */
@Service
public class LoginAttemptService {

    private final UserRepository users;
    private final TransactionTemplate tx;
    private final int maxAttempts;
    private final Duration base;
    private final Duration max;

    public LoginAttemptService(UserRepository users, TransactionTemplate tx,
                               @Value("${app.lockout.max-attempts}") int maxAttempts,
                               @Value("${app.lockout.base-minutes}") long baseMinutes,
                               @Value("${app.lockout.max-minutes}") long maxMinutes) {
        this.users = users;
        this.tx = tx;
        this.maxAttempts = maxAttempts;
        this.base = Duration.ofMinutes(baseMinutes);
        this.max = Duration.ofMinutes(maxMinutes);
    }

    /**
     * Registra un fallo. Transacción corta (contar y, si toca, bloquear) para que dos fallos simultáneos
     * no se pisen. Devuelve true si este fallo ha bloqueado la cuenta.
     */
    public boolean recordFailure(long userId) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            long[] state = users.addFailedAttempt(userId);
            if (state[0] < maxAttempts) {
                return false;
            }
            users.lock(userId, Instant.now().plus(lockDuration(state[1])), state[1] + 1);
            return true;
        }));
    }

    public void recordSuccess(long userId) {
        users.clearLockState(userId);
    }

    /** base * 2^nivel, limitado por el tope. El nivel se acota para no desbordar al desplazar bits. */
    Duration lockDuration(long level) {
        Duration d = base.multipliedBy(1L << Math.min(level, 20));
        return d.compareTo(max) > 0 ? max : d;
    }
}
