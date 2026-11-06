package com.example.login.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Email SIMULADO: imprime el mensaje en la consola del servidor en lugar de enviarlo.
 * Simplificación deliberada de proyecto de aprendizaje. El mensaje incluye enlaces con tokens de un solo uso,
 * que son secretos: en producción NUNCA se escriben en logs, y se usaría un servicio SMTP real.
 */
@Service
public class ConsoleEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(ConsoleEmailService.class);

    @Override
    public void send(String to, String subject, String textBody) {
        log.info("""

                ======== EMAIL SIMULADO ========
                Para:   {}
                Asunto: {}

                {}
                ================================""", to, subject, textBody);
    }
}
