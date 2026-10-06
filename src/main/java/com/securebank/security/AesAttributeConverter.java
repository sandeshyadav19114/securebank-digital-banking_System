package com.securebank.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/** Transparent column-level encryption for PII fields (PAN, Aadhaar, address). */
@Converter
@Component
public class AesAttributeConverter implements AttributeConverter<String, String> {

    private final AesEncryptor encryptor;

    public AesAttributeConverter(AesEncryptor encryptor) {
        this.encryptor = encryptor;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return attribute == null ? null : encryptor.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return dbData == null ? null : encryptor.decrypt(dbData);
    }
}
