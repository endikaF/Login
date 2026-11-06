package com.example.login.user;

/**
 * Usuario tal como se lee de la BD. {@code tokenVersion} se compara con el claim "ver" del JWT:
 * si no coinciden, el access token ya no vale aunque no haya caducado.
 * toString() omite el hash para que nunca acabe en un log.
 */
public record User(long id, String email, String passwordHash, Role role, boolean enabled,
                   boolean emailVerified, long tokenVersion) {

    @Override
    public String toString() {
        return "User[id=" + id + ", role=" + role + "]";
    }
}
