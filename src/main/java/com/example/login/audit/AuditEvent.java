package com.example.login.audit;

/** Eventos de seguridad registrados. Los de reset y administración se añaden en fases posteriores. */
public enum AuditEvent {
    LOGIN_OK,
    LOGIN_FAILED,
    LOGIN_BLOCKED,   // intento sobre una cuenta bloqueada temporalmente
    ACCOUNT_LOCKED,  // el fallo que dispara el bloqueo
    RATE_LIMITED,
    LOGOUT,
    LOGOUT_ALL,
    TOKEN_REUSE,     // refresh token ya rotado presentado de nuevo
    PASSWORD_RESET_REQUESTED,
    PASSWORD_RESET_DONE,
    PASSWORD_RESET_FAILED, // enlace inválido, caducado o ya usado
    EMAIL_VERIFIED,
    ADMIN_USER_ENABLED,   // acciones de administración: user_id = cuenta afectada, detail = "admin=<id>"
    ADMIN_USER_DISABLED,
    ADMIN_ROLE_CHANGED,
    ADMIN_FORCE_LOGOUT
}
