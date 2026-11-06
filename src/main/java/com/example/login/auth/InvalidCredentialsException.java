package com.example.login.auth;

/** Login fallido. Un único tipo para todas las causas: así la respuesta no distingue qué falló. */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("invalid credentials");
    }
}
