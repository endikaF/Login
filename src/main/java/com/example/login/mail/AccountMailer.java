package com.example.login.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Redacta los correos de la cuenta y construye sus enlaces.
 * El token va en el FRAGMENTO de la URL (#token=...), no en la query (?token=...): el navegador no envía el
 * fragmento al servidor, así que no queda en logs de acceso ni se filtra por la cabecera Referer. La página
 * (Fase 5) lo lee con JavaScript y lo manda por POST.
 */
@Component
public class AccountMailer {

    private final EmailService email;
    private final String baseUrl;

    public AccountMailer(EmailService email, @Value("${app.base-url}") String baseUrl) {
        this.email = email;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public void sendVerification(String to, String token) {
        email.send(to, "Verifica tu email", """
                Hola,

                Para activar tu cuenta, abre este enlace (válido durante 24 horas y de un solo uso):

                %s/verify-email.html#token=%s

                Si no has creado ninguna cuenta, ignora este mensaje.""".formatted(baseUrl, token));
    }

    public void sendPasswordReset(String to, String token) {
        email.send(to, "Recupera tu contraseña", """
                Hola,

                Para elegir una contraseña nueva, abre este enlace (válido durante unos minutos y de un solo uso):

                %s/reset-password.html#token=%s

                Si no lo has pedido tú, ignora este mensaje: tu contraseña no cambiará.""".formatted(baseUrl, token));
    }

    /** Aviso tras cambiar la contraseña: si no fue el titular, se entera enseguida de que alguien accedió a su correo. */
    public void sendPasswordChanged(String to) {
        email.send(to, "Tu contraseña ha cambiado", """
                Hola,

                La contraseña de tu cuenta se acaba de cambiar y se han cerrado todas tus sesiones.
                Si no fuiste tú, recupera el acceso cuanto antes desde:

                %s/forgot-password.html

                y revisa la seguridad de tu correo.""".formatted(baseUrl));
    }

    /** Se envía al registrarse con un email que ya tiene cuenta; así el formulario responde igual en ambos casos. */
    public void sendAlreadyRegistered(String to) {
        email.send(to, "Ya tienes una cuenta", """
                Hola,

                Alguien ha intentado registrarse con este email, pero ya existe una cuenta asociada.
                Si fuiste tú y no recuerdas la contraseña, puedes recuperarla aquí:

                %s/forgot-password.html

                Si no fuiste tú, puedes ignorar este mensaje.""".formatted(baseUrl));
    }
}
