package com.example.login.config;

import com.example.login.auth.JwtAuthFilter;
import com.example.login.auth.JwtService;
import com.example.login.user.UserRepository;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Argon2id (ganador de la Password Hashing Competition; resistente a GPU por usar memoria).
     * Parámetros según la guía de OWASP (Password Storage Cheat Sheet), opción de 19 MiB:
     *  - memoria 19456 KiB (19 MiB), 2 iteraciones, paralelismo 1;
     *  - sal de 16 bytes (aleatoria por contraseña) y hash de 32 bytes.
     * El hash resultante ($argon2id$v=19$m=19456,t=2,p=1$...) incluye sus parámetros, así que se pueden
     * subir en el futuro sin romper los hashes ya guardados.
     * [TRADE-OFF] Menos memoria = más rápido pero más barato de atacar; 19 MiB es el mínimo recomendado.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtService jwtService, UserRepository users)
            throws Exception {
        http
                // CSRF desactivado A PROPÓSITO: no hay sesión por cookie; la API usará un access token en la
                // cabecera Authorization (que el navegador no adjunta solo). Estrategia completa en Fase 5.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Públicos: no hay identidad previa. /refresh y /logout se apoyan en la cookie, no en el JWT.
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login",
                                "/api/auth/refresh", "/api/auth/logout").permitAll()
                        // Denegar por defecto: todo lo no listado exige autenticación.
                        .anyRequest().authenticated())
                // Sin identidad válida: 401 con cuerpo vacío (sin pistas sobre por qué falló el token).
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(new JwtAuthFilter(jwtService, users), UsernamePasswordAuthenticationFilter.class)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .headers(Customizer.withDefaults());
        return http.build();
    }
}
