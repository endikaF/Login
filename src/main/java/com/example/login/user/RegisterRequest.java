package com.example.login.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo del registro. La validación de formato se hace en servidor (el JS del navegador es opcional
 * para un atacante). La longitud de la contraseña la valida PasswordPolicy, una única fuente de verdad.
 */
public record RegisterRequest(
        @NotBlank @Email @Size(max = 254) String email, // 254 = máximo práctico de una dirección (RFC 5321)
        @NotNull String password) {

    /** Evita que la contraseña acabe en logs si alguien imprime el objeto. */
    @Override
    public String toString() {
        return "RegisterRequest[email=" + email + ", password=***]";
    }
}
