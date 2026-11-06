package com.example.login;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.login.mail.EmailService;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Sustituye al email simulado en los tests: guarda los mensajes para poder leer el token del enlace. */
@Component
@Primary
public class RecordingEmailService implements EmailService {

    public record Mail(String to, String subject, String body) {
    }

    private static final Pattern TOKEN = Pattern.compile("#token=([A-Za-z0-9_-]+)");

    private final List<Mail> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(String to, String subject, String textBody) {
        sent.add(new Mail(to, subject, textBody));
    }

    public void clear() {
        sent.clear();
    }

    public List<Mail> all() {
        return List.copyOf(sent);
    }

    public Mail last() {
        return sent.get(sent.size() - 1);
    }

    /** Token del enlace del último correo enviado. */
    public String lastToken() {
        Matcher m = TOKEN.matcher(last().body());
        if (!m.find()) {
            throw new IllegalStateException("El último correo no contiene enlace con token");
        }
        return m.group(1);
    }
}
