package com.securebank.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.securebank.config.CryptoProperties;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class AesEncryptorTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private final AesEncryptor encryptor = new AesEncryptor(new CryptoProperties(KEY));

    @Test
    void roundTrips() {
        String ct = encryptor.encrypt("ABCDE1234F");
        assertThat(ct).isNotEqualTo("ABCDE1234F");
        assertThat(encryptor.decrypt(ct)).isEqualTo("ABCDE1234F");
    }

    @Test
    void usesRandomIvSoCiphertextsDiffer() {
        assertThat(encryptor.encrypt("same")).isNotEqualTo(encryptor.encrypt("same"));
    }

    @Test
    void detectsTampering() {
        byte[] raw = Base64.getDecoder().decode(encryptor.encrypt("secret"));
        raw[raw.length - 1] ^= 1;
        String tampered = Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> encryptor.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsWrongKeyLength() {
        CryptoProperties bad = new CryptoProperties(Base64.getEncoder().encodeToString(new byte[16]));
        assertThatThrownBy(() -> new AesEncryptor(bad)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void converterHandlesNulls() {
        AesAttributeConverter c = new AesAttributeConverter(encryptor);
        assertThat(c.convertToDatabaseColumn(null)).isNull();
        assertThat(c.convertToEntityAttribute(null)).isNull();
        assertThat(c.convertToEntityAttribute(c.convertToDatabaseColumn("x"))).isEqualTo("x");
    }
}
