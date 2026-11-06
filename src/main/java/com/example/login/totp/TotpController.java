package com.example.login.totp;

import java.util.List;
import java.util.Map;

import com.example.login.auth.AuthenticatedUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Gestión del segundo factor por el propio usuario (requiere sesión; setup y baja piden además la contraseña). */
@RestController
@RequestMapping("/api/auth/totp")
public class TotpController {

    public record SetupRequest(@NotNull @Size(max = 200) String password) {
        @Override
        public String toString() {
            return "SetupRequest[password=***]";
        }
    }

    public record EnableRequest(@NotBlank @Size(max = 40) String code) {
        @Override
        public String toString() {
            return "EnableRequest[code=***]";
        }
    }

    public record DisableRequest(@NotNull @Size(max = 200) String password, @NotBlank @Size(max = 40) String code) {
        @Override
        public String toString() {
            return "DisableRequest[password=***, code=***]";
        }
    }

    private final TotpService totp;

    public TotpController(TotpService totp) {
        this.totp = totp;
    }

    @PostMapping("/setup")
    public Map<String, String> setup(@AuthenticationPrincipal AuthenticatedUser me,
                                     @Valid @RequestBody SetupRequest request, HttpServletRequest http) {
        var setup = totp.setup(me, request.password(), http.getRemoteAddr());
        return Map.of("secret", setup.secret(), "otpauthUri", setup.otpauthUri());
    }

    @PostMapping("/enable")
    public Map<String, List<String>> enable(@AuthenticationPrincipal AuthenticatedUser me,
                                            @Valid @RequestBody EnableRequest request, HttpServletRequest http) {
        // Los códigos de recuperación solo se muestran ahora; después únicamente existe su hash.
        return Map.of("recoveryCodes", totp.enable(me, request.code(), http.getRemoteAddr()));
    }

    @PostMapping("/disable")
    public ResponseEntity<Void> disable(@AuthenticationPrincipal AuthenticatedUser me,
                                        @Valid @RequestBody DisableRequest request, HttpServletRequest http) {
        totp.disable(me, request.password(), request.code(), http.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }
}
