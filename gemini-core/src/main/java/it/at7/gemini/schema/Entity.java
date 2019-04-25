package it.at7.gemini.schema;

import it.at7.gemini.core.*;
import it.at7.gemini.core.Module;
import it.at7.gemini.exceptions.EntityFieldException;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.toSet;

public class Entity {
    public static final String ENTITY = "ENTITY";
    public static final String FIELD_RESOLUTION = "FIELDRESOLUTION";
    public static final String CORE_META = "COREMETA";

    public static final String NAME = "name";

    private final Module module;
    private final String name;
    private final Map<String, Object> defaultRecord;
    private final Set<EntityField> dataFields;
    private final Map<String, EntityField> metaFieldsByName;
    private final Map<String, EntityField> dataFieldsByName;
    private final Set<EntityField> metaFields;
    private final LogicalKey logicalKey;
    private final EntityField idField;
    private final boolean embedable;
    private Object idValue;

    public Entity(Module module, String name, boolean embedable, List<EntityFieldBuilder> fieldsBuilders, @Nullable Object defaultRecord) {
        Assert.notNull(module, "Module must be not null");
        Assert.notNull(name, "Entity name must be not null");
        this.module = module;
        this.name = name;
        this.embedable = embedable;
        this.defaultRecord = defaultRecord == null ? new HashMap<>() : (Map<String, Object>) defaultRecord;
        fieldsBuilders.forEach(f -> f.setEntity(this));
        this.dataFields = fieldsBuilders.stream().filter(e -> e.getScope().equals(EntityField.Scope.DATA)).map(EntityFieldBuilder::build).collect(toSet());
        this.dataFieldsByName = dataFields.stream().collect(Collectors.toMap(e -> e.getName().toLowerCase(), e -> e));
        this.metaFields = fieldsBuilders.stream().filter(e -> e.getScope().equals(EntityField.Scope.META)).map(EntityFieldBuilder::build).collect(toSet());
        this.metaFieldsByName = metaFields.stream().collect(Collectors.toMap(e -> e.getName().toLowerCase(), e -> e));
        this.logicalKey = extractLogicalKeyFrom(dataFields);
        this.idField = EntityFieldBuilder.ID(this);
        idValue = null;
    }

    public String getName() {
        return name;
    }

    public Module getModule() {
        return module;
    }

    public boolean isEmbedable() {
        return embedable;
    }

    public EntityField getField(String fieldName) throws EntityFieldException {
        fieldName = fieldName.toLowerCase();
        EntityField idField = getIdEntityField();
        if (idField.getName().toLowerCase().equals(fieldName)) {
            return idField; // id is a special field
        }
        EntityField entityField = dataFieldsByName.get(fieldName);
        if (entityField == null) {
            throw EntityFieldException.ENTITYFIELD_NOT_FOUND(this, fieldName);
        }
        return entityField;
    }

    public EntityField getMetaField(String fieldName) throws EntityFieldException {
        fieldName = fieldName.toLowerCase();
        EntityField entityField = metaFieldsByName.get(fieldName);
        if (entityField == null) {
            throw EntityFieldException.ENTITYMETAFIELD_NOT_FOUND(entityField);
        }
        return entityField;
    }

    @Nullable // TODO this method probably may be removed
    public EntityRecord getDefaultEntityRecord() {
        return RecordConverters.entityRecordFromMap(this, copyDefaultRecord());
    }

    public Set<EntityField> getALLEntityFields() {
        return Stream.concat(dataFields.stream(), metaFields.stream()).collect(collectingAndThen(Collectors.toSet(), Collections::unmodifiableSet));
    }


    public Set<EntityField> getDataEntityFields() {
        return Collections.unmodifiableSet(dataFields);
    }

    public Set<EntityField> getMetaEntityFields() {
        return Collections.unmodifiableSet(metaFields);
    }

    public LogicalKey getLogicalKey() {
        return logicalKey;
    }

    public EntityField getIdEntityField() {
        return idField;
    }


    /* TODO runtime entities ?
    public void addField(EntityField entityField) throws EntityFieldException {
        if (this.dataFieldsByName.containsKey(entityField.getName().toLowerCase())) {
            throw EntityFieldException.ENTITYFIELD_ALREADY_FOUND(entityField);
        }
        this.dataFields.add(entityField);
        this.dataFieldsByName.put(entityField.getName().toLowerCase(), entityField);
    }

    public void removeField(EntityField entityField) throws EntityFieldException {
        String key = entityField.getName().toLowerCase();
        EntityField ef = dataFieldsByName.get(key);
        if (ef == null) {
            throw EntityFieldException.ENTITYFIELD_NOT_FOUND(entityField);
        }
        this.dataFields.remove(ef);
        this.dataFieldsByName.remove(key);
    }
    */

    public EntityRecord toInitializationEntityRecord() {
        Map<String, Object> values = copyDefaultRecord();
        values.put("name", name);
        values.put("module", module.getName());
        values.put("embedable", embedable);
        values.put("displayName", name.substring(0, 1).toUpperCase() + name.substring(1).toLowerCase());
        Entity entity = Services.getSchemaManager().getEntity(ENTITY);
        assert entity != null;
        return RecordConverters.entityRecordFromMap(entity, values);
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
                Objects.equals(embedable, entity.embedable);
    }

    @Override
    public int hashCode() {
        // no ID Value / Fields in Equals
        return Objects.hash(module, name, embedable);
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
