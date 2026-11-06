package com.example.login.auth;

/** Caso concreto de refresh token inválido: uno ya rotado que se presenta otra vez (posible robo). */
public class RefreshTokenReuseException extends InvalidRefreshTokenException {

    private final long userId;

    public RefreshTokenReuseException(long userId) {
        this.userId = userId;
    }

    public long userId() {
        return userId;
    }
}
