package com.example.login.admin;

import java.util.List;

import com.example.login.admin.AdminRepository.AdminUserView;
import com.example.login.auth.AuthenticatedUser;
import com.example.login.user.Role;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Panel de administración. Doble barrera: la regla de URL en SecurityConfig (/api/admin/** → ADMIN) y este
 * @PreAuthorize. Si una de las dos se rompe por un cambio futuro, la otra sigue protegiendo.
 */
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    public record EnabledRequest(@NotNull Boolean enabled) {
    }

    public record RoleRequest(@NotNull Role role) {
    }

    private final AdminService admin;

    public AdminController(AdminService admin) {
        this.admin = admin;
    }

    @GetMapping
    public List<AdminUserView> list() {
        return admin.listUsers();
    }

    @PostMapping("/{id}/enabled")
    public ResponseEntity<Void> setEnabled(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable long id,
                                           @Valid @RequestBody EnabledRequest request, HttpServletRequest http) {
        admin.setEnabled(me.id(), id, request.enabled(), http.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/role")
    public ResponseEntity<Void> setRole(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable long id,
                                        @Valid @RequestBody RoleRequest request, HttpServletRequest http) {
        admin.setRole(me.id(), id, request.role(), http.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/logout")
    public ResponseEntity<Void> forceLogout(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable long id,
                                            HttpServletRequest http) {
        admin.forceLogout(me.id(), id, http.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }
}
