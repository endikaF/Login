package com.example.login.auth;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AccountRecoveryController {

    public record EmailRequest(@NotBlank @Email @Size(max = 254) String email) {
    }

    public record TokenRequest(@NotBlank @Size(max = 200) String token) {
    }

    public record ResetPasswordRequest(@NotBlank @Size(max = 200) String token, @NotNull String newPassword) {
        /** Evita que la contraseña (o el token) acabe en logs si alguien imprime el objeto. */
        @Override
        public String toString() {
            return "ResetPasswordRequest[token=***, newPassword=***]";
        }
    }

    private final AccountRecoveryService recovery;

    public AccountRecoveryController(AccountRecoveryService recovery) {
        this.recovery = recovery;
    }

    /** Siempre 202 y el mismo mensaje, exista o no el email (anti-enumeración). */
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> forgotPassword(@Valid @RequestBody EmailRequest request, HttpServletRequest http) {
        recovery.requestPasswordReset(request.email(), http.getRemoteAddr());
        return Map.of("message", "Si el email existe, recibirás un enlace para recuperar la contraseña.");
    }

    @PostMapping("/reset-password")
    public Map<String, String> resetPassword(@Valid @RequestBody ResetPasswordRequest request,
                                             HttpServletRequest http) {
        recovery.resetPassword(request.token(), request.newPassword(), http.getRemoteAddr());
        return Map.of("message", "Contraseña actualizada. Inicia sesión con la nueva.");
    }

    @PostMapping("/verify-email")
    public Map<String, String> verifyEmail(@Valid @RequestBody TokenRequest request, HttpServletRequest http) {
        recovery.verifyEmail(request.token(), http.getRemoteAddr());
        return Map.of("message", "Email verificado. Ya puedes iniciar sesión.");
    }

    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> resendVerification(@Valid @RequestBody EmailRequest request, HttpServletRequest http) {
        recovery.resendVerification(request.email(), http.getRemoteAddr());
        return Map.of("message", "Si la cuenta existe y falta verificarla, recibirás un nuevo enlace.");
    }
}
