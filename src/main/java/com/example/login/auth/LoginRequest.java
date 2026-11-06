package com.example.login.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record LoginRequest(@NotBlank @Size(max = 254) String email, @NotNull String password) {

    /** Evita que la contraseña acabe en logs si alguien imprime el objeto. */
    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=***]";
    }
}
