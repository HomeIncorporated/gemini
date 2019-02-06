package it.at7.gemini.schema;

import it.at7.gemini.core.EntityRecord;
import it.at7.gemini.core.Module;
import it.at7.gemini.core.Record;
import it.at7.gemini.core.Services;
import it.at7.gemini.exceptions.EntityFieldException;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

import java.util.*;
import java.util.stream.Collectors;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.toSet;

public class Entity {
    public static final String ENTITY = "ENTITY";
    public static final String FIELD_RESOLUTION = "FIELDRESOLUTION";

    public static final String NAME = "name";

    private final Module module;
    private final String name;
    private final Map<String, Object> defaultRecord;
    private final Set<EntityField> schemaFields;
    private final LogicalKey logicalKey;
    private final EntityField idField;
    private final Map<String, EntityField> schemaMapFields;
    private Object idValue;

    public Entity(Module module, String name, List<EntityFieldBuilder> fieldsBuilders, @Nullable Object defaultRecord) {
        Assert.notNull(module, "Module must be not null");
        Assert.notNull(name, "Entity name must be not null");
        this.module = module;
        this.name = name;
        this.defaultRecord = defaultRecord == null ? new HashMap<>() : (Map<String, Object>) defaultRecord;
        fieldsBuilders.forEach(f -> f.setEntity(this));
        this.schemaFields = fieldsBuilders.stream().map(EntityFieldBuilder::build).collect(toSet());
        this.schemaMapFields = schemaFields.stream().collect(Collectors.toMap(e -> e.getName().toLowerCase(), e -> e));
        this.logicalKey = extractLogicalKeyFrom(schemaFields);
        this.idField = EntityFieldBuilder.ID(this);
        idValue = null;
    }

    public void addField(EntityField entityField) throws EntityFieldException {
        if (this.schemaMapFields.containsKey(entityField.getName().toLowerCase())) {
            throw EntityFieldException.ENTITYFIELD_ALREADY_FOUND(entityField);
        }
        this.schemaFields.add(entityField);
        this.schemaMapFields.put(entityField.getName().toLowerCase(), entityField);
    }

    public void removeField(EntityField entityField) throws EntityFieldException {
        String key = entityField.getName().toLowerCase();
        EntityField ef = schemaMapFields.get(key);
        if (ef == null) {
            throw EntityFieldException.ENTITYFIELD_NOT_FOUND(entityField);
        }
        this.schemaFields.remove(ef);
        this.schemaMapFields.remove(key);
    }

    public String getName() {
        return name;
    }

    public Module getModule() {
        return module;
    }

    public EntityField getField(String fieldName) throws EntityFieldException {
        fieldName = fieldName.toLowerCase();
        EntityField idField = getIdField();
        if (idField.getName().toLowerCase().equals(fieldName)) {
            return idField; // id is a special field
        }
        EntityField entityField = schemaMapFields.get(fieldName);
        if (entityField == null) {
            throw EntityFieldException.ENTITYFIELD_NOT_FOUND(entityField);
        }
        return entityField;
    }

    @Nullable // TODO this method probably may be removed
    public Object getDefaultEntityRecord() {
        return EntityRecord.Converters.recordFromJSONMap(Services.getSchemaManager().getEntity("ENTITY"), copyDefaultRecord());
    }

    public Set<EntityField> getSchemaEntityFields() {
        return Collections.unmodifiableSet(schemaFields);
    }

    public LogicalKey getLogicalKey() {
        return logicalKey;
    }

    public EntityField getIdField() {
        return idField;
    }

    public EntityRecord toEntityRecord() {
        Map<String, Object> values = copyDefaultRecord();
        values.put("name", name);
        values.put("module", module.getName());
        Entity entity = Services.getSchemaManager().getEntity(ENTITY);
        assert entity != null;
        return EntityRecord.Converters.recordFromJSONMap(entity, values);
    }

    public void setFieldIDValue(Object idValue) {
        this.idValue = idValue;
    }

    private LogicalKey extractLogicalKeyFrom(Set<EntityField> fields) {
        return new LogicalKey(fields.stream().filter(EntityField::isLogicalKey).sorted(comparing(EntityField::getName))
                .collect(collectingAndThen(toSet(), Collections::unmodifiableSet)));
    }

    @Nullable
    public Object getIDValue() {
        return idValue;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Entity entity = (Entity) o;
        return Objects.equals(module, entity.module) &&
                Objects.equals(name, entity.name) &&
                Objects.equals(schemaFields, entity.schemaFields) &&
                Objects.equals(logicalKey, entity.logicalKey) &&
                Objects.equals(idField, entity.idField) &&
                Objects.equals(defaultRecord, entity.defaultRecord);
        // no ID VALUE in Equals
    }

    @Override
    public int hashCode() {
        // no ID Value in Equals
        return Objects.hash(module, name, schemaFields, logicalKey, idField, defaultRecord);
    }

    private Map<String, Object> copyDefaultRecord() {
        return copyRecordInner(defaultRecord);
    }

    private Map<String, Object> copyRecordInner(Map<String, Object> record) {
        Map<String, Object> ret = new HashMap<>();
        for (Map.Entry<String, Object> elem : record.entrySet()) {
            if (Map.class.isAssignableFrom(elem.getValue().getClass()))
                ret.put(elem.getKey(), copyRecordInner((Map<String, Object>) elem.getValue()));
            else ret.put(elem.getKey(), elem.getValue());
        }
        return ret;
    }

    public EntityRecord entityRecordFrom(Map<String, Object> fieldValueMap) {
        return EntityRecord.Converters.recordFromJSONMap(this, fieldValueMap);
    }

    public Record logicalRecordFrom(Map<String, Object> fieldValueMap) {
        Collection<EntityField> logicalKeySet = logicalKey.getLogicalKeyList();
        return Record.Converters.recordFromMap(logicalKeySet, fieldValueMap);
    }


    public class LogicalKey {

        private final Set<EntityField> logicalKeySet;

        LogicalKey(Set<EntityField> logicalKeySet) {
            Assert.notNull(logicalKeySet, String.format("%s: logical Key Must be not NULL", name));
            this.logicalKeySet = logicalKeySet;
        }

        public Set<EntityField> getLogicalKeySet() {
            return logicalKeySet;
        }

        public List<EntityField> getLogicalKeyList() {
            return new ArrayList<>(logicalKeySet);
        }
    }
}
