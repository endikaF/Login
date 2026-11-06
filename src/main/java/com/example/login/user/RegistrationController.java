package com.example.login.user;

import java.util.Map;

import com.example.login.security.RateLimitService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class RegistrationController {

    private final UserService users;
    private final RateLimitService rateLimit;

    public RegistrationController(UserService users, RateLimitService rateLimit) {
        this.users = users;
        this.rateLimit = rateLimit;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.ACCEPTED) // 202: se procesa y se avisa por email; no se confirma si la cuenta es nueva
    public Map<String, String> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        // Límite por IP antes de hashear: cada registro cuesta ~19 MiB y CPU de Argon2.
        rateLimit.checkRegisterIp(http.getRemoteAddr());
        users.register(request.email(), request.password());
        // Respuesta idéntica para email nuevo y ya registrado (anti-enumeración). Sin datos del usuario.
        return Map.of("message", "Si el email es válido, recibirás un correo para verificar tu cuenta.");
    }
}
