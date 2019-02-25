package it.at7.gemini.core.persistence;

import it.at7.gemini.schema.FieldType;

public class FieldTypePersistenceUtility {

    public static boolean oneToOneType(FieldType type) {
        switch (type) {
            case PK:
            case TEXT:
            case NUMBER:
            case LONG:
            case DOUBLE:
            case BOOL:
            case TIME:
            case DATE:
            case DATETIME:
            case TRANSL_TEXT:
            case TEXT_ARRAY:
                return true;
            case ENTITY_REF:
            case ENTITY_EMBEDED:
            case GENERIC_ENTITY_REF:
            case RECORD:
                return false;
        }
        return false;
    }


    public static boolean entityType(FieldType type) {
        switch (type) {
            case PK:
            case TEXT:
            case NUMBER:
            case LONG:
            case DOUBLE:
            case BOOL:
            case TIME:
            case DATE:
            case DATETIME:
            case TRANSL_TEXT:
            case TEXT_ARRAY:
            case RECORD:
                return false;
            case ENTITY_REF:
            case ENTITY_EMBEDED:
            case GENERIC_ENTITY_REF:
                return true;
        }
        return false;
    }

}
