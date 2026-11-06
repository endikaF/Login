package com.example.login.admin;

import java.util.List;

import com.example.login.admin.AdminExceptions.SelfModificationException;
import com.example.login.admin.AdminExceptions.UserNotFoundException;
import com.example.login.admin.AdminRepository.AdminUserView;
import com.example.login.audit.AuditEvent;
import com.example.login.audit.AuditService;
import com.example.login.auth.RefreshTokenService;
import com.example.login.totp.TotpService;
import com.example.login.user.Role;
import com.example.login.user.UserRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Acciones de administración. Cada una se audita con el id del admin que la ejecuta (detail="admin=<id>"),
 * y el controlador ya exige rol ADMIN; aquí además se aplican las reglas de negocio.
 */
@Service
public class AdminService {

    private final AdminRepository view;
    private final UserRepository users;
    private final RefreshTokenService refreshTokens;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final TotpService totp;

    public AdminService(AdminRepository view, UserRepository users, RefreshTokenService refreshTokens,
                        AuditService audit, TransactionTemplate tx, TotpService totp) {
        this.totp = totp;
        this.view = view;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.audit = audit;
        this.tx = tx;
    }

    public List<AdminUserView> listUsers() {
        return view.findAllUsers();
    }

    public void setEnabled(long adminId, long targetId, boolean enabled, String ip) {
        requireExists(targetId);
        // Un admin no puede desactivarse: se bloquearía a sí mismo y podría dejar el sistema sin administradores.
        if (adminId == targetId && !enabled) {
            throw new SelfModificationException();
        }
        tx.executeWithoutResult(status -> {
            users.setEnabled(targetId, enabled);
            if (!enabled) {
                // Desactivar también cierra sus sesiones: refresh tokens fuera y access token invalidados.
                forceLogoutInternal(targetId);
            }
        });
        audit.record(enabled ? AuditEvent.ADMIN_USER_ENABLED : AuditEvent.ADMIN_USER_DISABLED, targetId, ip,
                "admin=" + adminId);
    }

    public void setRole(long adminId, long targetId, Role role, String ip) {
        requireExists(targetId);
        // Un admin no puede quitarse su propio rol (mismo motivo que arriba).
        if (adminId == targetId && role != Role.ADMIN) {
            throw new SelfModificationException();
        }
        // El rol se lee de la BD en cada petición, así que el cambio vale desde la siguiente.
        users.setRole(targetId, role);
        audit.record(AuditEvent.ADMIN_ROLE_CHANGED, targetId, ip, "admin=" + adminId + ",role=" + role.name());
    }

    public void forceLogout(long adminId, long targetId, String ip) {
        requireExists(targetId);
        tx.executeWithoutResult(status -> forceLogoutInternal(targetId));
        audit.record(AuditEvent.ADMIN_FORCE_LOGOUT, targetId, ip, "admin=" + adminId);
    }

    /**
     * Quita el 2FA a un usuario que perdió el dispositivo y los códigos de recuperación. El reseteo de contraseña por
     * email NO lo hace a propósito (si bastara el correo, un correo robado anularía el segundo factor), así que esta
     * es la vía de recuperación y queda auditada.
     */
    public void resetTotp(long adminId, long targetId, String ip) {
        requireExists(targetId);
        totp.reset(targetId);
        audit.record(AuditEvent.ADMIN_TOTP_RESET, targetId, ip, "admin=" + adminId);
    }

    private void forceLogoutInternal(long userId) {
        refreshTokens.revokeAll(userId);
        users.incrementTokenVersion(userId);
    }

    private void requireExists(long id) {
        if (users.findById(id).isEmpty()) {
            throw new UserNotFoundException();
        }
    }
}
