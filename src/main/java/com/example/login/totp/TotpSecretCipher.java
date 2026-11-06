package com.example.login.totp;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;

/**
 * Cifra el secreto TOTP antes de guardarlo. A diferencia de una contraseña, el secreto hay que poder LEERLO para
 * calcular códigos, así que no sirve un hash: se cifra con AES-256-GCM (Spring Security Crypto, IV aleatorio
 * por mensaje y autenticación del contenido) usando una clave que vive solo en el entorno (TOTP_ENCRYPTION_KEY).
 * Resultado: quien robe únicamente el archivo de la BD no puede generar códigos.
 * La sal que exige Spring no es secreta: solo separa esta clave derivada de otros usos; la protección real es la clave.
 * [TRADE-OFF] Perder o cambiar la clave invalida todos los TOTP ya activados. En producción: gestor de secretos
 * o KMS, con rotación.
 */
@Component
public class TotpSecretCipher {

    static final int MIN_KEY_LENGTH = 32;
    private static final String SALT = HexFormat.of().formatHex("login-app-totp-v1".getBytes(StandardCharsets.UTF_8));

    private final TextEncryptor encryptor;

    public TotpSecretCipher(@Value("${TOTP_ENCRYPTION_KEY:}") String key) {
        // Fail fast: sin clave (o con una trivial) la app no arranca; nunca hay clave por defecto.
        if (key == null || key.length() < MIN_KEY_LENGTH) {
            throw new IllegalStateException(
                    "TOTP_ENCRYPTION_KEY no definida o demasiado corta (mínimo " + MIN_KEY_LENGTH + " caracteres).");
        }
        this.encryptor = Encryptors.delux(key, SALT); // delux = AES-GCM, salida en hexadecimal
    }

    public String encrypt(String base32Secret) {
        return encryptor.encrypt(base32Secret);
    }

    public String decrypt(String stored) {
        return encryptor.decrypt(stored);
    }
}
