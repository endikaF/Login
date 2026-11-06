package com.example.login.auth;

/** Refresh token ausente, desconocido, caducado, revocado o reutilizado. Al cliente solo se le dice "sesión no válida". */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException() {
        super("invalid refresh token");
    }
}
