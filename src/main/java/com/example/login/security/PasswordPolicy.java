package com.example.login.security;

import com.example.login.user.WeakPasswordException;

/**
 * Política de contraseñas (NIST 800-63B / OWASP ASVS V2.1).
 * Solo longitud: sin reglas de composición (mayúscula, símbolo...) porque empujan a contraseñas
 * predecibles ("Password1!") y no aumentan la seguridad real.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    /** Tope antes de hashear: Argon2 es costoso a propósito y una contraseña de 10 MB sería un vector de DoS. */
    public static final int MAX_LENGTH = 128;

    private PasswordPolicy() {
    }

    public static void validate(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw new WeakPasswordException("La contraseña debe tener al menos " + MIN_LENGTH + " caracteres.");
        }
        if (password.length() > MAX_LENGTH) {
            throw new WeakPasswordException("La contraseña no puede superar los " + MAX_LENGTH + " caracteres.");
        }
    }
}
