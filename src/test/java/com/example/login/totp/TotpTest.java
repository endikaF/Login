package com.example.login.totp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;

class TotpTest {

    /** Clave de los vectores de prueba del RFC 6238 (apéndice B): el ASCII de "12345678901234567890". */
    private static final byte[] RFC_KEY = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    private static String rfcCode(long unixTime) {
        return Totp.code(RFC_KEY, unixTime / 30, 8); // el RFC lista los códigos de 8 dígitos
    }

    @Test
    void matchesTheOfficialRfc6238TestVectors() {
        assertThat(rfcCode(59L)).isEqualTo("94287082");
        assertThat(rfcCode(1111111109L)).isEqualTo("07081804");
        assertThat(rfcCode(1111111111L)).isEqualTo("14050471");
        assertThat(rfcCode(1234567890L)).isEqualTo("89005924");
        assertThat(rfcCode(2000000000L)).isEqualTo("69279037");
        assertThat(rfcCode(20000000000L)).isEqualTo("65353130");
    }

    @Test
    void sixDigitCodeIsTheTruncationOfTheEightDigitOne() {
        assertThat(Totp.code(RFC_KEY, Instant.ofEpochSecond(59))).isEqualTo("287082");
    }

    @Test
    void base32MatchesTheOfficialRfc4648Vectors() {
        assertThat(Totp.encodeBase32("f".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MY");
        assertThat(Totp.encodeBase32("fo".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXQ");
        assertThat(Totp.encodeBase32("foo".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6");
        assertThat(Totp.encodeBase32("foob".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YQ");
        assertThat(Totp.encodeBase32("fooba".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTB");
        assertThat(Totp.encodeBase32("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
        assertThat(new String(Totp.decodeBase32("MZXW6YTBOI"), StandardCharsets.US_ASCII)).isEqualTo("foobar");
        assertThat(new String(Totp.decodeBase32("mzxw 6ytb-oi=="), StandardCharsets.US_ASCII)).isEqualTo("foobar");
    }

    @Test
    void base32RejectsInvalidCharacters() {
        assertThatThrownBy(() -> Totp.decodeBase32("MZXW1")).isInstanceOf(IllegalArgumentException.class); // '1' no existe
    }

    @Test
    void newSecretsAreRandomAndRoundTrip() {
        String a = Totp.newSecretBase32();
        String b = Totp.newSecretBase32();
        assertThat(a).isNotEqualTo(b).hasSize(32).matches("[A-Z2-7]+");
        assertThat(Totp.decodeBase32(a)).hasSize(20);
    }

    @Test
    void verifyAcceptsCurrentAndAdjacentStepsOnly() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        long step = Totp.stepAt(now);
        assertThat(Totp.verify(RFC_KEY, Totp.code(RFC_KEY, step, 6), now, 0)).hasValue(step);
        assertThat(Totp.verify(RFC_KEY, Totp.code(RFC_KEY, step - 1, 6), now, 0)).hasValue(step - 1);
        assertThat(Totp.verify(RFC_KEY, Totp.code(RFC_KEY, step + 1, 6), now, 0)).hasValue(step + 1);
        assertThat(Totp.verify(RFC_KEY, Totp.code(RFC_KEY, step - 2, 6), now, 0)).isEmpty();
        assertThat(Totp.verify(RFC_KEY, Totp.code(RFC_KEY, step + 2, 6), now, 0)).isEmpty();
    }

    @Test
    void verifyRejectsReplayOfAnAlreadyUsedStepAndOlderOnes() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        long step = Totp.stepAt(now);
        String code = Totp.code(RFC_KEY, step, 6);
        assertThat(Totp.verify(RFC_KEY, code, now, step)).isEmpty();                    // mismo intervalo ya usado
        assertThat(Totp.verify(RFC_KEY, Totp.code(RFC_KEY, step - 1, 6), now, step)).isEmpty(); // uno anterior
        assertThat(Totp.verify(RFC_KEY, Totp.code(RFC_KEY, step + 1, 6), now, step)).hasValue(step + 1);
    }

    @Test
    void verifyRejectsMalformedInput() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        for (String bad : new String[] {null, "", "12345", "1234567", "abcdef", "12 456", "١٢٣٤٥٦"}) {
            assertThat(Totp.verify(RFC_KEY, bad, now, 0)).isEqualTo(OptionalLong.empty());
        }
    }

    @Test
    void otpauthUriHasTheStandardShape() {
        String uri = Totp.otpauthUri("Tablero de notas", "ana+test@example.com", "JBSWY3DPEHPK3PXP");
        assertThat(uri).startsWith("otpauth://totp/Tablero%20de%20notas:ana%2Btest%40example.com?")
                .contains("secret=JBSWY3DPEHPK3PXP", "issuer=Tablero%20de%20notas", "algorithm=SHA1", "digits=6", "period=30");
    }
}
