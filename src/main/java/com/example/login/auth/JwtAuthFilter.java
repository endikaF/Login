package com.example.login.auth;

import java.io.IOException;
import java.util.List;

import com.example.login.user.User;
import com.example.login.user.UserRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Autentica cada petición a partir de "Authorization: Bearer <jwt>".
 * Tras comprobar la firma consulta la BD: el usuario debe existir, estar activo y su token_version
 * debe coincidir con el del token. Así "cerrar sesión en todos los dispositivos", desactivar una cuenta
 * o cambiar un rol tienen efecto inmediato sin esperar a que el JWT caduque.
 * [TRADE-OFF] Una consulta por petición a cambio de revocación inmediata; con SQLite local es barata.
 * Cualquier fallo deja la petición sin autenticar (el entry point responde 401 genérico).
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository users;

    public JwtAuthFilter(JwtService jwtService, UserRepository users) {
        this.jwtService = jwtService;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            authenticate(header.substring(PREFIX.length()).trim());
        }
        chain.doFilter(request, response);
    }

    private void authenticate(String token) {
        Jwt jwt;
        long userId;
        long version;
        try {
            jwt = jwtService.decode(token);
            // Solo los access token autentican. El token del segundo paso del login (typ=mfa) se rechaza aquí.
            if (!JwtService.TYPE_ACCESS.equals(jwt.getClaimAsString("typ"))) {
                return;
            }
            userId = Long.parseLong(jwt.getSubject());
            Object ver = jwt.getClaim("ver");
            if (!(ver instanceof Number number)) {
                return;
            }
            version = number.longValue();
        } catch (JwtException | NumberFormatException e) {
            return; // firma inválida, caducado, manipulado... se trata como "sin autenticar"
        }
        users.findById(userId)
                .filter(User::enabled)
                .filter(u -> u.tokenVersion() == version)
                .ifPresent(u -> {
                    var authentication = new UsernamePasswordAuthenticationToken(
                            new AuthenticatedUser(u.id(), u.email(), u.role()), null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + u.role().name())));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                });
    }
}
