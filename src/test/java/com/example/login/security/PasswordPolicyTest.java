package com.example.login.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.login.user.WeakPasswordException;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    @Test
    void acceptsExactlyMinimumLength() {
        assertThatCode(() -> PasswordPolicy.validate("a".repeat(12))).doesNotThrowAnyException();
    }

    @Test
    void acceptsExactlyMaximumLength() {
        assertThatCode(() -> PasswordPolicy.validate("a".repeat(128))).doesNotThrowAnyException();
    }

    @Test
    void acceptsPassphraseWithoutCompositionRules() {
        assertThatCode(() -> PasswordPolicy.validate("correct horse battery")).doesNotThrowAnyException();
    }

    @Test
    void rejectsShortPassword() {
        assertThatThrownBy(() -> PasswordPolicy.validate("a".repeat(11))).isInstanceOf(WeakPasswordException.class);
    }

    @Test
    void rejectsTooLongPassword() {
        assertThatThrownBy(() -> PasswordPolicy.validate("a".repeat(129))).isInstanceOf(WeakPasswordException.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> PasswordPolicy.validate(null)).isInstanceOf(WeakPasswordException.class);
    }
}
