package com.example.login.mail;

/**
 * Punto de enchufe para enviar correo. Hoy lo implementa {@link ConsoleEmailService} (escribe en la consola);
 * para producción bastaría otra implementación (SMTP, SES, SendGrid...) sin tocar el resto del código.
 */
public interface EmailService {

    void send(String to, String subject, String textBody);
}
