package com.example.login.web;

import java.util.Map;

import com.example.login.auth.InvalidCredentialsException;
import com.example.login.user.EmailAlreadyRegisteredException;
import com.example.login.user.WeakPasswordException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Gestión de errores centralizada: el cliente solo recibe mensajes fijos y genéricos. Los detalles
 * (trazas, SQL, rutas) se quedan en el log del servidor, nunca en la respuesta (evita fuga de información).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException e) {
        // Solo se indica QUÉ campos fallan, no el valor enviado.
        String fields = String.join(", ", e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField()).distinct().toList());
        return error(HttpStatus.BAD_REQUEST, "Datos no válidos en: " + fields + ".");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "Solicitud mal formada.");
    }

    @ExceptionHandler(WeakPasswordException.class)
    public ResponseEntity<Map<String, String>> weakPassword(WeakPasswordException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ResponseEntity<Map<String, String>> duplicate(EmailAlreadyRegisteredException e) {
        return error(HttpStatus.CONFLICT, "No se pudo completar el registro.");
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<Map<String, String>> badCredentials(InvalidCredentialsException e) {
        // Mensaje idéntico para usuario inexistente, contraseña errónea o cuenta desactivada.
        return error(HttpStatus.UNAUTHORIZED, "Credenciales inválidas.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unexpected(Exception e) {
        log.error("Error no controlado", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno.");
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", status.name(), "message", message));
    }
}
