package it.at7.gemini.schema;

import java.util.Optional;

public enum FieldType {
    /**
     * code(isBasic)
     */
    PK,
    TEXT,
    NUMBER,
    LONG( "QUANTITY"),
    DOUBLE( "DECIMAL"),
    BOOL,
    TIME,
    DATE,
    DATETIME,
    ENTITY_REF,
    TRANSL_TEXT,
    GENERIC_ENTITY_REF,
    TEXT_ARRAY("[TEXT]"),
    RECORD;

    private final String[] alias;

    FieldType() {
        alias = new String[0];
    }

    FieldType(String... alias) {
        this.alias = alias;
    }

    public static Optional<FieldType> of(String type) {
        for (FieldType value : values()) {
            if (value.name().equalsIgnoreCase(type)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    public static Optional<FieldType> getAliasOfType(String type) {
        for (FieldType fieldType : values()) {
            for (String a : fieldType.getAlias()) {
                if (a.equals(type)) {
                    return Optional.of(fieldType);
                }
            }
        }
        return Optional.empty();
    }

    public String[] getAlias() {
        return alias;
    }
}
