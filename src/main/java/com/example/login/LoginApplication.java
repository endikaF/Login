package com.example.login;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class LoginApplication {

    public static void main(String[] args) throws IOException {
        // SQLite crea el archivo pero no la carpeta; sin esto el primer arranque falla.
        Files.createDirectories(Path.of("data"));
        SpringApplication.run(LoginApplication.class, args);
    }
}
