package com.example.login.config;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.example.login.auth.JwtAuthFilter;
import com.example.login.auth.JwtService;
import com.example.login.user.UserRepository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
// Activa @PreAuthorize: la autorización se declara junto a cada endpoint, no solo en esta lista de rutas.
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * CSP estricta: la página solo puede cargar scripts y estilos de SU PROPIO origen. No hay 'unsafe-inline'
     * (por eso el frontend no tiene scripts ni estilos en línea): aunque alguien lograra inyectar HTML, el
     * navegador se negaría a ejecutar su script (mitiga XSS). default-src 'none' prohíbe todo lo no listado;
     * frame-ancestors 'none' impide que otra web nos incruste en un iframe (clickjacking); base-uri y
     * form-action cierran vías para redirigir formularios o enlaces relativos.
     */
    static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'; object-src 'none'";

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

    /**
     * CORS restrictivo: el frontend se sirve desde el MISMO origen que la API, así que no hace falta abrir nada.
     * Solo se aceptan peticiones cross-origin del propio origen público (app.base-url) y de los que se listen
     * expresamente en app.cors.allowed-origins (vacío por defecto). Cualquier otro origen recibe 403, también en
     * peticiones "simples" como un POST de formulario, y sin credenciales.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.base-url}") String baseUrl,
                                                    @Value("${app.cors.allowed-origins:}") List<String> extraOrigins) {
        Set<String> origins = new LinkedHashSet<>();
        origins.add(originOf(baseUrl));
        extraOrigins.stream().map(String::trim).filter(o -> !o.isEmpty()).forEach(origins::add);

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.copyOf(origins));
        config.setAllowedMethods(List.of("GET", "POST", "DELETE"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(false); // la cookie de refresh es SameSite=Strict: nunca se envía entre sitios
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    private static String originOf(String url) {
        URI uri = URI.create(url);
        return uri.getScheme() + "://" + uri.getAuthority();
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtService jwtService, UserRepository users,
                                    @Qualifier("corsConfigurationSource") CorsConfigurationSource cors) throws Exception {
        http
                /*
                 * ESTRATEGIA CSRF (desactivado el filtro de Spring, a propósito, y con tres capas que lo sustituyen):
                 *  1. La API autentica con "Authorization: Bearer <access token>" que el JavaScript propio añade a
                 *     mano. El navegador nunca lo adjunta por su cuenta, así que un sitio malicioso no puede
                 *     forzar peticiones autenticadas (eso es lo que explota CSRF con cookies de sesión).
                 *  2. La única cookie (refresh token) es SameSite=Strict y Path=/api/auth: no viaja en peticiones
                 *     originadas desde otros sitios, y solo la usan /refresh y /logout.
                 *  3. CORS rechaza (403) cualquier petición cross-origin de un origen no autorizado.
                 * Un token CSRF clásico (doble envío o sincronizador) solo haría falta si hubiera sesión por cookie.
                 */
                .csrf(AbstractHttpConfigurer::disable)
                .cors(c -> c.configurationSource(cors))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                        // HSTS: obliga al navegador a usar solo HTTPS durante un año. Spring solo lo envía sobre
                        // HTTPS (en http://localhost no aplica); en producción va detrás de TLS.
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                        // X-Content-Type-Options: nosniff ya viene activado por defecto.
                        .frameOptions(f -> f.deny())          // refuerzo de frame-ancestors para navegadores viejos
                        .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER)))
                .authorizeHttpRequests(auth -> auth
                        // Frontend estático (solo lectura): HTML, CSS y JS propios. No contienen datos de nadie.
                        .requestMatchers(HttpMethod.GET, "/", "/*.html", "/css/**", "/js/**").permitAll()
                        // Públicos: no hay identidad previa. /refresh y /logout se apoyan en la cookie, no en el JWT.
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login",
                                "/api/auth/refresh", "/api/auth/logout", "/api/auth/forgot-password",
                                "/api/auth/reset-password", "/api/auth/verify-email",
                                "/api/auth/resend-verification").permitAll()
                        // Barrera de URL para el panel; el controlador la repite con @PreAuthorize.
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // Denegar por defecto: todo lo no listado exige autenticación.
                        .anyRequest().authenticated())
                // Sin identidad válida: 401 con cuerpo vacío (sin pistas sobre por qué falló el token).
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(new JwtAuthFilter(jwtService, users), UsernamePasswordAuthenticationFilter.class)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
