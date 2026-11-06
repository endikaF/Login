package com.example.login.config;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limita el tamaño de los cuerpos de la API. Sin esto, Jackson leería en memoria un JSON de cientos de MB antes de
 * poder rechazarlo (denegación de servicio barata para el atacante). Los cuerpos legítimos son diminutos (la nota
 * más grande ronda los 20 KB), así que el tope por defecto es 64 KB.
 * Se rechaza por la cabecera Content-Length ANTES de leer nada. Las peticiones con Transfer-Encoding: chunked no
 * declaran tamaño, así que se rechazan (411): el JavaScript del navegador siempre envía Content-Length con
 * cuerpos de texto, de modo que no afecta a usuarios reales.
 * Va primera en la cadena (precedencia máxima) para cortar antes que Spring Security o Jackson.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestSizeFilter extends OncePerRequestFilter {

    private final long maxBytes;

    public RequestSizeFilter(@Value("${app.max-body-bytes}") long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        boolean hasBody = method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH
                || method == HttpMethod.DELETE;
        return !hasBody || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getHeader("Transfer-Encoding") != null) {
            response.setStatus(HttpServletResponse.SC_LENGTH_REQUIRED); // 411
            return;
        }
        if (request.getContentLengthLong() > maxBytes) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE); // 413
            return;
        }
        chain.doFilter(request, response);
    }
}
