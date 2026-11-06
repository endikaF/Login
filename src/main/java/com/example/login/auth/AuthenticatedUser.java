package com.example.login.auth;

import com.example.login.user.Role;

/** Identidad ya verificada de la petición. El id sale del token firmado, nunca de datos del cliente. */
public record AuthenticatedUser(long id, String email, Role role) {
}
