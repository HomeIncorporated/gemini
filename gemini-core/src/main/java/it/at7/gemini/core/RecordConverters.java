package it.at7.gemini.core;

import it.at7.gemini.exceptions.EntityFieldException;
import it.at7.gemini.exceptions.InvalidLogicalKeyValue;
import it.at7.gemini.exceptions.InvalidTypeForObject;
import it.at7.gemini.schema.Entity;
import it.at7.gemini.schema.EntityField;
import it.at7.gemini.schema.Field;
import it.at7.gemini.schema.FieldType;
import org.springframework.lang.Nullable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

import static it.at7.gemini.core.FieldConverters.Formatter.*;


public class RecordConverters {

    public static EntityRecord entityRecordFromMap(Entity entity, Map<String, Object> rawFields) throws InvalidLogicalKeyValue, InvalidTypeForObject {
        Map<String, Object> insensitiveFields = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        insensitiveFields.putAll(rawFields);
        EntityRecord entityRecord = new EntityRecord(entity);
        for (EntityField field : entity.getSchemaEntityFields()) {
            String key = toFieldName(field).toLowerCase();
            Object objValue = insensitiveFields.get(key);
            if (objValue != null) {
                try {
                    entityRecord.put(field, objValue);
                } catch (EntityFieldException e) {
                    // this sould not happen because of the loop on the entityschemafields - chiamare la Madonna
                    throw new RuntimeException(String.format("record from JSON MAP critical bug: %s - %s", entity.getName(), toFieldName(field)));
                }
            }
        }
        Object idValue = rawFields.get(Field.ID_NAME);
        if (idValue != null) {
            try {
                // this sould not happen because of the loop on the entityschemafields - chiamare la Madonna
                entityRecord.put(entityRecord.getEntity().getIdEntityField(), idValue);
            } catch (EntityFieldException e) {
                throw new RuntimeException("record from JSON MAP critical bug");
            }
        }
        return entityRecord;
    }

    public static DynamicRecord dynamicRecordFromMap(Collection<? extends Field> fields, Map<String, Object> rawFields) throws InvalidTypeForObject {
        DynamicRecord record = new DynamicRecord();
        for (Field field : fields) {
            String key = toFieldName(field);
            Object objValue = rawFields.get(key);
            if (objValue != null) {
                putValueToRecord(record, field, objValue);
            }
        }
        return record;
    }

    public static EntityRecord entityRecordFromDynamicRecord(Entity entity, DynamicRecord record) throws InvalidLogicalKeyValue {
        Map<String, Object> store = record.getStore();
        return entityRecordFromMap(entity, store);
    }

    public static Map<String, Object> toJSONMap(EntityRecord record) {
        Map<String, Object> convertedMap = new HashMap<>();
        for (EntityRecord.EntityFieldValue fieldValue : record.getAllSchemaEntityFieldValues()) {
            convertSingleFieldTOJSONValue(convertedMap, fieldValue);
        }
        return convertedMap;
    }


    public static Map<String, Object> toMap(EntityRecord record) {
        return toMap(record.getAllSchemaEntityFieldValues());
    }

    public static Map<String, Object> toMap(DynamicRecord record) {
        return toMap(record.getFieldValues());
    }

    public static Map<String, Object> toMap(Collection<? extends DynamicRecord.FieldValue> fieldValues) {
        Map<String, Object> convertedMap = new HashMap<>();
        for (DynamicRecord.FieldValue fieldValue : fieldValues) {
            convertSingleFieldTOJSONValue(convertedMap, fieldValue);
        }
        return convertedMap;
    }

    static void putValueToRecord(DynamicRecord r, Field field, @Nullable Object objValue) {
        if (objValue == null) {
            r.put(field, null);
            return;
        }
        r.put(field, objValue);
    }

    static protected void convertSingleFieldTOJSONValue(Map<String, Object> convertedMap, DynamicRecord.FieldValue fieldValue) {
        Field field = fieldValue.getField();
        FieldType fieldType = field.getType();
        String fieldNameLC = toFieldName(field);
        Object value = fieldValue.getValue();
        if (value == null) {
            value = nullToDefault(field);
        }
        switch (fieldType) {
            case PK:
            case LONG:
            case DOUBLE:
            case TEXT:
            case TRANSL_TEXT:
            case NUMBER:
            case BOOL:
            case RECORD:
            case TEXT_ARRAY:
                convertedMap.put(fieldNameLC, value);
                break;
            case TIME:
                if (String.class.isAssignableFrom(value.getClass())) {
                    String stValue = (String) value;
                    if (!stValue.isEmpty()) {
                        // TODO handle check the right format or try to convert
                    }
                    convertedMap.put(fieldNameLC, value);
                }
                if (LocalTime.class.isAssignableFrom(value.getClass())) {
                    // TODO
                    LocalTime ltValue = (LocalTime) value;
                    convertedMap.put(fieldNameLC, ltValue.format(TIME_FORMATTER_OUTPUT));
                }
                break;
            case DATE:
                if (String.class.isAssignableFrom(value.getClass())) {
                    String stValue = (String) value;
                    if (!stValue.isEmpty()) {
                        // TODO handle check the right format or try to convert
                    }
                    convertedMap.put(fieldNameLC, value);
                }
                if (LocalDate.class.isAssignableFrom(value.getClass())) {
                    LocalDate ldValue = (LocalDate) value;
                    convertedMap.put(fieldNameLC, ldValue.format(DATE_FORMATTER_OUTPUT));
                }
                break;
            case DATETIME:
                if (String.class.isAssignableFrom(value.getClass())) {
                    String stValue = (String) value;
                    if (!stValue.isEmpty()) {
                        // TODO handle check the right format or try to convert
                    }
                    convertedMap.put(fieldNameLC, value);
                }
                if (LocalDateTime.class.isAssignableFrom(value.getClass())) {
                    // TODO
                    LocalDateTime ltValue = (LocalDateTime) value;
                    convertedMap.put(fieldNameLC, ltValue.format(DATETIME_FORMATTER_OUTPUT));
                }
                break;
            case ENTITY_REF:
                convertEntityRefToJSONValue(convertedMap, field, value);
                break;
            case ENTITY_EMBEDED:
                convertEntityEmbededTOJsonValue(convertedMap, field, value);
                break;
            case ENTITY_REF_ARRAY:
                convertEntityRefArrayToJSONValue(convertedMap, field, value);
                break;
            default:
                throw new RuntimeException(String.format("No conversion found for fieldtype %s", fieldType));
        }
    }

    private static void convertEntityRefArrayToJSONValue(Map<String, Object> convertedMap, Field field, Object value) {
        List<Object> refArray = new ArrayList<>();
        if (Collection.class.isAssignableFrom(value.getClass())) {
            Collection genericColl = (Collection) value;
            if (genericColl.iterator().hasNext()) {
                Object firstVal = genericColl.iterator().next();
                if (EntityReferenceRecord.class.isAssignableFrom(firstVal.getClass())) {
                    Collection<EntityReferenceRecord> entityReferenceRecords = (Collection<EntityReferenceRecord>) value;
                    for (EntityReferenceRecord entityReferenceRecord : entityReferenceRecords) {
                        refArray.add(entityRefToMap(entityReferenceRecord));
                    }
                } else {
                    // TODO
                    throw new RuntimeException("TODO convertEntityRefArrayToJSONValue for EntityRecord array");
                }
            }
        }
        convertedMap.put(toFieldName(field), refArray);
    }

    private static void convertEntityRefToJSONValue(Map<String, Object> convertedMap, Field field, Object value) {
        String fieldNameLC = toFieldName(field);
        if (EntityReferenceRecord.class.isAssignableFrom(value.getClass())) {
            EntityReferenceRecord pkRefRec = (EntityReferenceRecord) value;
            convertedMap.put(fieldNameLC, entityRefToMap(pkRefRec));
        } else if (EntityRecord.class.isAssignableFrom(value.getClass())) {
            // we have the full reference record here -- we add a map of its fields
            EntityRecord eRValue = (EntityRecord) value;
            convertedMap.put(fieldNameLC, toMap(eRValue));
        }
    }

    private static Object entityRefToMap(EntityReferenceRecord rec) {
        if (rec.equals(EntityReferenceRecord.NO_REFERENCE)) {
            return new HashMap<>();
        } else {
            return toLogicalKey(rec);
        }
    }

    private static void convertEntityEmbededTOJsonValue(Map<String, Object> convertedMap, Field field, Object value) {
        if (EntityRecord.class.isAssignableFrom(value.getClass())) {
            EntityRecord eRValue = (EntityRecord) value;
            convertedMap.put(toFieldName(field), toMap(eRValue));
        } else {
            throw new RuntimeException(String.format("Unsupported OPE"));
        }
    }

    private static Object toLogicalKey(EntityReferenceRecord pkRefRec) {
        if (pkRefRec.hasPrimaryKey() && pkRefRec.getPrimaryKey().equals(0L)) {
            return null; // null value if we have a no key;
        }
        assert pkRefRec.hasLogicalKey();
        DynamicRecord lkValue = pkRefRec.getLogicalKeyRecord();
        Entity.LogicalKey lk = pkRefRec.getEntity().getLogicalKey();
        List<EntityField> lkFields = lk.getLogicalKeyList();
        if (lkFields.size() == 1) {
            Object lkSingleValue = lkValue.get(lkFields.get(0));
            assert lkSingleValue != null;
            return lkSingleValue;
        }
        Map<String, Object> convertedMap = new HashMap<>();
        Entity entity = pkRefRec.getEntity();
        DynamicRecord logicalKeyValue = pkRefRec.getLogicalKeyRecord();
        for (EntityField entityField : entity.getLogicalKey().getLogicalKeyList()) {
            DynamicRecord.FieldValue fieldValue = logicalKeyValue.getFieldValue(entityField);
            convertSingleFieldTOJSONValue(convertedMap, fieldValue);
        }
        return convertedMap;
    }

    public static List<EntityRecord.EntityFieldValue> logicalKeyFromStrings(Entity entity, String... keys) {
        Entity.LogicalKey logicalKey = entity.getLogicalKey();
        List<EntityField> logicalKeyList = logicalKey.getLogicalKeyList();
        assert keys.length == logicalKeyList.size();
        List<EntityRecord.EntityFieldValue> lkFieldValues = new ArrayList<>();
        for (int i = 0; i < logicalKeyList.size(); i++) {
            EntityField field = logicalKeyList.get(i);
            String lkElem = keys[i];
            Object convertedLkElem = FieldConverters.getConvertedFieldValue(field, lkElem);
            lkFieldValues.add(EntityRecord.EntityFieldValue.create(field, convertedLkElem));
        }
        return lkFieldValues;
    }

    public static String toFieldName(Field field) {
        return field.getName();
    }


    static Object nullToDefault(Field field) {
        FieldType fieldType = field.getType();
        switch (fieldType) {
            case PK:
                return 0;
            case TEXT:
            case TIME:
            case DATE:
            case DATETIME:
            case TRANSL_TEXT:
                return "";
            case NUMBER:
                return 0;
            case LONG:
                return 0;
            case DOUBLE:
                return 0.;
            case BOOL:
                return false;
            case ENTITY_REF:
                return EntityReferenceRecord.NO_REFERENCE;
            case RECORD:
                return new Object();
            case TEXT_ARRAY:
                return new String[]{};
        }
        throw new RuntimeException(String.format("No default found for type %s", field));
    }
}
