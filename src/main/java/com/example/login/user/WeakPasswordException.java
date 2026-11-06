package com.example.login.user;

/** Contraseña que incumple la política. El mensaje es fijo y seguro de mostrar al cliente. */
public class WeakPasswordException extends RuntimeException {

    public WeakPasswordException(String message) {
        super(message);
    }
}
