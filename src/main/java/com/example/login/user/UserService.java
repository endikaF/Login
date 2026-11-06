package com.example.login.user;

import java.util.Locale;

import com.example.login.security.PasswordPolicy;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder encoder;

    public UserService(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    /** Registro público: siempre rol USER (el cliente nunca decide su rol) y email sin verificar. */
    public void register(String email, String rawPassword) {
        create(email, rawPassword, Role.USER, false);
    }

    /** Alta de usuario con rol explícito; solo la usa el arranque para el primer ADMIN. */
    public void create(String email, String rawPassword, Role role, boolean emailVerified) {
        // Política ANTES de hashear: así una contraseña enorme no llega a consumir CPU/memoria de Argon2.
        PasswordPolicy.validate(rawPassword);
        // Normalizar evita cuentas duplicadas "A@x.com" / "a@x.com" (además de COLLATE NOCASE en BD).
        String normalized = normalize(email);
        if (users.existsByEmail(normalized)) {
            throw new EmailAlreadyRegisteredException();
        }
        users.insert(normalized, encoder.encode(rawPassword), role, emailVerified);
    }

    public boolean existsByEmail(String email) {
        return users.existsByEmail(normalize(email));
    }

    static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
