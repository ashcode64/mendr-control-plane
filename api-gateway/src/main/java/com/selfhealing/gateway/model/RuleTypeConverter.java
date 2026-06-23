package com.selfhealing.gateway.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Maps legacy/composite AI rule types (e.g. {@code FIELD_RENAME|TYPE_COERCE}) to a known enum value.
 */
@Converter(autoApply = false)
public class RuleTypeConverter implements AttributeConverter<TransformationRule.RuleType, String> {

    @Override
    public String convertToDatabaseColumn(TransformationRule.RuleType attribute) {
        return attribute != null ? attribute.name() : null;
    }

    @Override
    public TransformationRule.RuleType convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return TransformationRule.RuleType.NESTED_TRANSFORM;
        }
        String normalized = dbData.toUpperCase().trim();
        if (normalized.contains("|") || normalized.contains(",")) {
            return TransformationRule.RuleType.NESTED_TRANSFORM;
        }
        try {
            return TransformationRule.RuleType.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            return TransformationRule.RuleType.NESTED_TRANSFORM;
        }
    }
}
