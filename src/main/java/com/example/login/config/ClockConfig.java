package com.example.login.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /** Reloj inyectable: el TOTP depende de la hora, y así los tests pueden avanzarla sin esperar 30 s reales. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
