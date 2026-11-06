package com.example.login.totp;

public final class TotpExceptions {

    private TotpExceptions() {
    }

    /**
     * Contraseña o código incorrectos al gestionar el 2FA estando YA autenticado. Se responde 403 (no 401) a
     * propósito: el frontend reintenta tras un 401 renovando el token, y eso contaría el fallo dos veces.
     */
    public static class ReauthenticationFailedException extends RuntimeException {
        public ReauthenticationFailedException() {
            super("reauthentication failed");
        }
    }

    /** Operación incompatible con el estado actual (p. ej. activar un 2FA que ya está activo). */
    public static class TotpStateException extends RuntimeException {
        public TotpStateException() {
            super("invalid totp state");
        }
    }
}
