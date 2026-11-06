package com.example.login.config;

import com.example.login.user.Role;
import com.example.login.user.UserService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Crea el primer ADMIN al arrancar a partir de variables de entorno. Así no hay credenciales en el código
 * ni un admin por defecto conocido públicamente.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserService users;
    private final String email;
    private final String password;

    public AdminBootstrap(UserService users,
                          @Value("${ADMIN_EMAIL:}") String email,
                          @Value("${ADMIN_PASSWORD:}") String password) {
        this.users = users;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email.isBlank() || password.isBlank()) {
            log.info("ADMIN_EMAIL/ADMIN_PASSWORD no definidos: no se crea administrador.");
            return;
        }
        // Idempotente: si ya existe no se toca (no se resetea una contraseña cambiada por el admin).
        if (users.existsByEmail(email)) {
            log.info("El administrador inicial ya existe.");
            return;
        }
        // Si la contraseña incumple la política, arrancar falla (fail fast); el mensaje no incluye el valor.
        users.create(email, password, Role.ADMIN, true);
        log.info("Administrador inicial creado.");
    }
}
