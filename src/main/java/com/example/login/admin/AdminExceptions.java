package com.example.login.admin;

/** Excepciones del panel de administración (cada una se traduce a una respuesta fija en GlobalExceptionHandler). */
public final class AdminExceptions {

    private AdminExceptions() {
    }

    /** Un admin intenta desactivarse o quitarse el rol a sí mismo (se quedaría fuera o dejaría el sistema sin admin). */
    public static class SelfModificationException extends RuntimeException {
        public SelfModificationException() {
            super("self modification not allowed");
        }
    }

    public static class UserNotFoundException extends RuntimeException {
        public UserNotFoundException() {
            super("user not found");
        }
    }
}
