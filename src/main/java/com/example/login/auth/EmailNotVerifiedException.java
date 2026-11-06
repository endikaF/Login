package com.example.login.auth;

/** Credenciales correctas pero email sin verificar. Solo se lanza a quien ya demostró conocer la contraseña. */
public class EmailNotVerifiedException extends RuntimeException {

    public EmailNotVerifiedException() {
        super("email not verified");
    }
}
