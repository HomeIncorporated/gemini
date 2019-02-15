package it.at7.gemini.core;

import it.at7.gemini.exceptions.EntityFieldException;
import it.at7.gemini.schema.Entity;
import it.at7.gemini.schema.EntityField;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

import java.util.*;

public class EntityRecord implements RecordBase {
    private Map<String, Object> store;
    private Set<EntityField> fields;
    private Entity entity;

    public EntityRecord(Entity entity) {
        Assert.notNull(entity, "Entity required for Entity DynamicRecord");
        this.store = new HashMap<>();
        this.fields = new HashSet<>();
        this.entity = entity;
    }

    public Set<EntityField> getEntityFields() {
        return Collections.unmodifiableSet(fields);
    }

    @Override
    public Map<String, Object> getStore() {
        return store;
    }

    public boolean put(String fieldName, Object value) {
        try {
            EntityField field = getEntityFieldFrom(fieldName);
            return put(field, value);
        } catch (EntityFieldException e) {
            return false;
        }
    }

    public boolean put(EntityField field, Object value) throws EntityFieldException {
        if (!(this.entity.getSchemaEntityFields().contains(field) || this.entity.getIdEntityField().equals(field))) {
            throw EntityFieldException.ENTITYFIELD_NOT_FOUND(field);
        }
        Object convertedValue = FieldConverters.getConvertedFieldValue(field, value);
        fields.add(field);
        store.put(field.getName().toLowerCase(), convertedValue);
        return true;
    }

    public Entity getEntity() {
        return entity;
    }

    public Set<EntityFieldValue> getLogicalKeyValue() {
        Set<EntityField> logicalKey = entity.getLogicalKey().getLogicalKeySet();
        return getEntityFieldValue(logicalKey);
    }

    /**
     * Get values and fields for all the fields available in the Entity Schema. This means
     * that if a new Entity DynamicRecord is created withGeminiSearchString only a subset of fields the remaining fields
     * are extracted withGeminiSearchString a default value.
     *
     * @return
     */
    public Set<EntityFieldValue> getAllSchemaEntityFieldValues() {
        return getEntityFieldValue(entity.getSchemaEntityFields());
    }

    public Set<EntityFieldValue> getOnlyModifiedEntityFieldValue() {
        return getEntityFieldValue(fields);
    }

    public EntityFieldValue getEntityFieldValue(EntityField field) {
        Object value = get(field);
        EntityFieldValue fieldValue = EntityFieldValue.create(field, value);
        return fieldValue;
    }

    /**
     * Get a subset of Entity Fields
     *
     * @param fields filter fields
     * @return
     */
    public Set<EntityFieldValue> getEntityFieldValue(Set<EntityField> fields) {
        Set<EntityFieldValue> fieldValues = new HashSet<>();
        for (EntityField field : fields) {
            fieldValues.add(getEntityFieldValue(field));
        }
        return fieldValues;
    }

    private EntityField getEntityFieldFrom(String fieldName) throws EntityFieldException {
        return this.entity.getField(fieldName);
    }

    public void update(EntityRecord rec) {
        Assert.isTrue(entity == rec.entity, "Records mus belong to the same Entity");
        for (EntityFieldValue fieldValue : rec.getOnlyModifiedEntityFieldValue()) {
            EntityField field = fieldValue.getEntityField();
            Object value = rec.get(field);
            try {
                put(field, value);
            } catch (EntityFieldException e) {
                // no exception here
                throw new RuntimeException("Critical bug here");
            }
        }
    }

    @Nullable
    public Object getID() {
        return get(getEntity().getIdEntityField());
    }

    public EntityFieldValue getIDEntityFieldValueType() {
        Object value = get(getEntity().getIdEntityField());
        return EntityFieldValue.create(getEntity().getIdEntityField(), value);
    }

    public static class EntityFieldValue extends DynamicRecord.FieldValue {
        public EntityFieldValue(EntityField field, Object value) {
            super(field, value);
        }

        public EntityField getEntityField() {
            return (EntityField) getField();
        }

        public static EntityFieldValue create(EntityField field, Object value) {
            return new EntityFieldValue(field, value);
        }

        public static EntityFieldValue create(Entity entity, DynamicRecord.FieldValue value) throws EntityFieldException {
            EntityField field = entity.getField(value.getField().getName().toLowerCase());
            return new EntityFieldValue(field, value.getValue());
        }
    }
}
