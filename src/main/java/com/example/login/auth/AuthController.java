package com.example.login.auth;

import java.time.Duration;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import com.example.login.totp.TotpService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

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
    private final TotpService totp;
    private final boolean secureCookie;

    public AuthController(AuthService auth, RefreshTokenService refreshTokens, TotpService totp,
                          @Value("${app.cookie.secure}") boolean secureCookie) {
        this.auth = auth;
        this.refreshTokens = refreshTokens;
        this.totp = totp;
        this.secureCookie = secureCookie;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest request,
                                                     HttpServletRequest http) {
        var outcome = auth.login(request.email(), request.password(), http.getRemoteAddr());
        if (outcome.mfaRequired()) {
            // Contraseña correcta pero falta el segundo factor: sin cookie y sin access token.
            return ResponseEntity.ok(Map.of("mfaRequired", true, "mfaToken", outcome.mfaToken(),
                    "expiresIn", JwtService.MFA_TTL.toSeconds()));
        }
        return tokenResponse(outcome.tokens());
    }

    public record MfaLoginRequest(@NotBlank @Size(max = 2000) String mfaToken, @NotBlank @Size(max = 40) String code) {
        @Override
        public String toString() {
            return "MfaLoginRequest[mfaToken=***, code=***]";
        }
    }

    @PostMapping("/login/totp")
    public ResponseEntity<Map<String, Object>> loginTotp(@Valid @RequestBody MfaLoginRequest request,
                                                         HttpServletRequest http) {
        return tokenResponse(auth.completeMfaLogin(request.mfaToken(), request.code(), http.getRemoteAddr()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken, HttpServletRequest http) {
        try {
            return tokenResponse(auth.refresh(refreshToken, http.getRemoteAddr()));
        } catch (InvalidRefreshTokenException e) {
            // Además de 401, se borra la cookie: ya no sirve y no tiene sentido que el navegador la siga enviando.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
                    .body(Map.of("error", "UNAUTHORIZED", "message", "Sesión no válida."));
        }
    }

    /** Público a propósito: solo necesita la cookie. Siempre 204, tenga o no sesión (idempotente). */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
                                       HttpServletRequest http) {
        auth.logout(refreshToken, http.getRemoteAddr());
        return clearedCookie();
    }

    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(@AuthenticationPrincipal AuthenticatedUser user, HttpServletRequest http) {
        auth.logoutAll(user.id(), http.getRemoteAddr());
        return clearedCookie();
    }

    /** Devuelve la identidad del token; sirve para que el frontend sepa quién es y qué rol tiene. */
    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of("email", user.email(), "role", user.role().name(), "totpEnabled", totp.isEnabled(user.id()));
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
