package com.example.login.auth;

/** Enlace de verificación/recuperación inválido, caducado, ya usado o de otro propósito: un único mensaje para todo. */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException() {
        super("invalid or expired token");
    }
}
