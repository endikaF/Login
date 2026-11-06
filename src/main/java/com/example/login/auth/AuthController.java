package com.example.login.auth;

import java.time.Duration;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    static final String REFRESH_COOKIE = "refresh_token";

    private final AuthService auth;
    private final RefreshTokenService refreshTokens;
    private final boolean secureCookie;

    public AuthController(AuthService auth, RefreshTokenService refreshTokens,
                          @Value("${app.cookie.secure}") boolean secureCookie) {
        this.auth = auth;
        this.refreshTokens = refreshTokens;
        this.secureCookie = secureCookie;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        return tokenResponse(auth.login(request.email(), request.password()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        try {
            return tokenResponse(auth.refresh(refreshToken));
        } catch (InvalidRefreshTokenException e) {
            // Además de 401, se borra la cookie: ya no sirve y no tiene sentido que el navegador la siga enviando.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
                    .body(Map.of("error", "UNAUTHORIZED", "message", "Sesión no válida."));
        }
    }

    /** Público a propósito: solo necesita la cookie. Siempre 204, tenga o no sesión (idempotente). */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        auth.logout(refreshToken);
        return clearedCookie();
    }

    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(@AuthenticationPrincipal AuthenticatedUser user) {
        auth.logoutAll(user.id());
        return clearedCookie();
    }

    /** Devuelve la identidad del token; sirve para que el frontend sepa quién es y qué rol tiene. */
    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of("email", user.email(), "role", user.role().name());
    }

    private ResponseEntity<Map<String, Object>> tokenResponse(AuthService.Tokens tokens) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie(tokens.refreshToken(), refreshTokens.ttl()).toString())
                .body(Map.of("accessToken", tokens.accessToken(), "tokenType", "Bearer",
                        "expiresIn", tokens.expiresInSeconds()));
    }

    private ResponseEntity<Void> clearedCookie() {
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
    }

    /**
     * Refresh token en cookie:
     *  - HttpOnly: el JavaScript de la página no puede leerla (un XSS no la roba).
     *  - Secure: solo viaja por HTTPS.
     *  - SameSite=Strict: el navegador no la envía en peticiones originadas desde otros sitios (anti-CSRF).
     *  - Path=/api/auth: solo se envía a los endpoints que la necesitan, no a toda la API.
     * El access token, en cambio, vive en memoria del frontend y va en la cabecera Authorization.
     */
    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true).secure(secureCookie).sameSite("Strict").path("/api/auth").maxAge(maxAge).build();
    }
}
