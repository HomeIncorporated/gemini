package it.at7.gemini.core.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.jirutka.rsql.parser.RSQLParser;
import cz.jirutka.rsql.parser.ast.Node;
import it.at7.gemini.conf.State;
import it.at7.gemini.core.*;
import it.at7.gemini.core.type.Password;
import it.at7.gemini.exceptions.*;
import it.at7.gemini.schema.*;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

import static it.at7.gemini.core.FieldConverters.createEntityReferenceRecordFromER;
import static it.at7.gemini.core.FieldConverters.logicalKeyFromObject;
import static it.at7.gemini.core.persistence.FieldTypePersistenceUtility.*;

@Service
public class PersistenceEntityManagerImpl implements PersistenceEntityManager, StateListener {
    private static final Logger logger = LoggerFactory.getLogger(PersistenceEntityManagerImpl.class);

    private final SchemaManager schemaManager;
    private FilterVisitor filterVisitor;

    @Autowired
    public PersistenceEntityManagerImpl(@Lazy SchemaManager schemaManager,
                                        StateManager stateManager,
                                        FilterVisitor filterVisitor) {
        this.schemaManager = schemaManager;
        this.filterVisitor = filterVisitor;
        stateManager.register(this);
    }

    @Override
    public void onChange(State previous, State actual, Optional<Transaction> transaction) throws GeminiException {
        switch (actual) {
            case FRAMEWORK_SCHEMA_RECORDS_INITIALIZED:
                // we have Entity Recors initialized with ID values so we can initialize entities here
                FieldTypePersistenceUtility.initEntities(this.schemaManager.getAllEntities());
                break;
        }
    }

    @Override
    public Optional<EntityRecord> getEntityRecordByLogicalKey(EntityRecord record, Transaction transaction) throws GeminiException {
        Set<EntityFieldValue> logicalKeyValues = record.getLogicalKeyValue();
        return getRecordByLogicalKeyInner(transaction, record.getEntity(), logicalKeyValues);
    }

    @Override
    public Optional<EntityRecord> getEntityRecordByLogicalKey(Entity entity, Collection<? extends FieldValue> logicalKeyValues, Transaction transaction) throws GeminiException {
        return getRecordByLogicalKeyInner(transaction, entity, logicalKeyValues);
    }

    @Override
    public EntityRecord createNewEntityRecord(EntityRecord record, Transaction transaction) throws GeminiException {
        TransactionImpl transactionImpl = (TransactionImpl) transaction;
        record.setUUID(getUUIDforEntityRecord(record));
        QueryWithParams queryWithParams = makeInsertQuery(record, transaction);
        try {
            long recordId = transactionImpl.executeInsert(queryWithParams.getSql(), queryWithParams.getParams());
            Optional<EntityRecord> insertedRecord = getEntityRecordById(record.getEntity(), recordId, transactionImpl);
            checkInsertedRecord(recordId, record, insertedRecord);
            return insertedRecord.get();
        } catch (GeminiException e) {
            logger.error("createNewEntityRecord SQL Exception", e);
            throw e;
        }
    }

    @Override
    public EntityRecord updateEntityRecordByID(EntityRecord record, Transaction transaction) throws GeminiException {
        TransactionImpl transactionImpl = (TransactionImpl) transaction;
        Object id = record.getID();
        if (id == null) {
            throw IdFieldException.ID_FIELD_REQUIRED("update", record);
        }
        QueryWithParams queryWithParams = makeModifyQueryFromID(record, transaction);
        transactionImpl.executeUpdate(queryWithParams.getSql(), queryWithParams.getParams());
        Optional<EntityRecord> recordById = getEntityRecordById(record.getEntity(), (long) id, transactionImpl);
        assert recordById.isPresent();
        return recordById.get();
    }

    @Override
    public void deleteEntityRecordByID(EntityRecord record, Transaction transaction) throws GeminiException {
        TransactionImpl transactionImpl = (TransactionImpl) transaction;
        Object id = record.getID();
        if (id == null) {
            throw IdFieldException.ID_FIELD_REQUIRED("delete", record);
        }
        QueryWithParams queryWithParams = makeDeleteQueryByID(record, transaction);
        transactionImpl.executeUpdate(queryWithParams.getSql(), null);
    }

    @Override
    public EntityRecord createOrUpdateEntityRecord(EntityRecord entityRecord, Transaction transaction) throws GeminiException {
        Optional<EntityRecord> recordByLogicalKey = getEntityRecordByLogicalKey(entityRecord, transaction);
        if (recordByLogicalKey.isPresent()) {
            EntityRecord persistedEntityRecord = recordByLogicalKey.get();
            // TODO check modified
            if (!sameOf(entityRecord, persistedEntityRecord, transaction)) {
                //    EntityField idField = entityRecord.getEntity().getIdEntityField();
                //    Object persistedID = persistedEntityRecord.get(idField);
                setALLpersistenceIDs(entityRecord, persistedEntityRecord);
                //    entityRecord.put(idField, persistedID);
                persistedEntityRecord = updateEntityRecordByID(entityRecord, transaction);
            }
            return persistedEntityRecord;
        } else {
            return createNewEntityRecord(entityRecord, transaction);
        }
    }

    private void setALLpersistenceIDs(EntityRecord entityRecord, EntityRecord persistedRecord) throws EntityFieldException {
        EntityField idField = entityRecord.getEntity().getIdEntityField();
        Object persistedID = persistedRecord.get(idField);
        entityRecord.put(idField, persistedID);
        for (EntityField field : entityRecord.getEntity().getDataEntityFields()) {
            if (field.getType().equals(FieldType.ENTITY_EMBEDED) && entityRecord.get(field) != null) {
                setALLpersistenceIDs(entityRecord.get(field), persistedRecord.get(field));
            }
        }
    }

    @Override
    public List<EntityRecord> getEntityRecordsMatching(Entity entity, Collection<? extends FieldValue> filterFielValue, EntityResolutionContext resolutionContext, Transaction transaction) throws GeminiException {
        TransactionImpl transactionImpl = (TransactionImpl) transaction;
        Set<EntityFieldValue> filter = convertToEntityFieldValues(entity, filterFielValue);
        try {
            QueryWithParams queryWithParams = makeSelectQueryFilteringFiledValue(entity, filter, transaction);
            return transactionImpl.executeQuery(queryWithParams.getSql(), queryWithParams.getParams(), resultSet -> {
                return fromResultSetToEntityRecord(resultSet, entity, resolutionContext, transaction);
            });
        } catch (SQLException e) {
            throw GeminiGenericException.wrap(e);
        }
    }

    @Override
    public List<EntityRecord> getEntityRecordsMatching(Entity entity, FilterContext filterContext, EntityResolutionContext resolutionContext, Transaction transaction) throws GeminiException {
        TransactionImpl transactionImpl = (TransactionImpl) transaction;
        try {
            QueryWithParams query = createSelectQueryFor(entity);
            addFilter(query, filterContext, entity);
            addOrderBy(query, filterContext, entity);
            addLimit(query, filterContext);
            addOffset(query, filterContext);
            return transactionImpl.executeQuery(query.getSql(), query.getParams(), resultSet -> {
                return fromResultSetToEntityRecord(resultSet, entity, resolutionContext, transaction);
            });
        } catch (SQLException e) {
            throw GeminiGenericException.wrap(e);
        }
    }

    @Override
    public Optional<EntityRecord> getEntityRecordById(Entity entity, long recordId, Transaction transaction) throws GeminiException {
        TransactionImpl transactionImpl = (TransactionImpl) transaction;
        try {
            QueryWithParams query = createSelectQueryFor(entity);
            addIdCondition(entity, recordId, query);
            return executeOptionalEntityRecordQuery(entity, transactionImpl, query);
        } catch (SQLException e) {
            throw GeminiGenericException.wrap(e);
        }
    }

    @Override
    public Optional<EntityRecord> getEntityRecordByUUID(Entity entity, UUID uuid, Transaction transaction) throws GeminiException {
        TransactionImpl transactionImpl = (TransactionImpl) transaction;
        try {
            QueryWithParams query = createSelectQueryFor(entity);
            addUUIDCondition(entity, uuid, query);
            return executeOptionalEntityRecordQuery(entity, transactionImpl, query);
        } catch (SQLException e) {
            throw GeminiGenericException.wrap(e);
        }
    }

    @Override
    public EntityRecord getEntityRecordSingleton(Entity entity, Transaction transaction) throws GeminiException {
        TransactionImpl transactionImpl = (TransactionImpl) transaction;
        try {
            QueryWithParams query = createSelectQueryFor(entity);
            Optional<EntityRecord> entityRecord = executeOptionalEntityRecordQuery(entity, transactionImpl, query);
            if (!entityRecord.isPresent()) {
                throw EntityRecordException.ONERECORD_ENTITY_MUSTEXIST(entity);
            }
            return entityRecord.get();
        } catch (SQLException e) {
            throw GeminiGenericException.wrap(e);
        }
    }

    private Optional<EntityRecord> executeOptionalEntityRecordQuery(Entity entity, TransactionImpl transactionImpl, QueryWithParams query) throws SQLException, GeminiException {
        return transactionImpl.executeQuery(query.getSql(), query.getParams(), resultSet -> {
            List<EntityRecord> entityRecords = fromResultSetToEntityRecord(resultSet, entity, null, transactionImpl);
            assert entityRecords.size() <= 1;
            return entityRecords.size() == 0 ? Optional.empty() : Optional.of(entityRecords.get(0));
        });
    }

    private void addIdCondition(Entity entity, long recordId, QueryWithParams query) {
        query.addToSql(String.format("WHERE \"%s\".\"%s\" = %d", entity.getName().toLowerCase(), entity.getIdEntityField().getName().toLowerCase(), recordId));
    }

    private void addUUIDCondition(Entity entity, UUID uuid, QueryWithParams query) {
        query.addToSql(String.format("WHERE \"%s\".\"%s\" = '%s'", entity.getName().toUpperCase().toLowerCase(), Field.UUID_NAME.toLowerCase(), uuid.toString()));
    }

    private void addFilter(QueryWithParams query, FilterContext filterContext, Entity entity) {
        String sqlFilter = "";
        FilterContext.FilterType filterType = filterContext.getFilterType();
        if (filterType == FilterContext.FilterType.GEMINI && !filterContext.getSearchString().isEmpty()) {
            Node rootNode = new RSQLParser().parse(filterContext.getSearchString());
            sqlFilter += " WHERE " + rootNode.accept(filterVisitor, entity);
        }
        if (filterType == FilterContext.FilterType.PERSISTENCE) {
            sqlFilter += " WHERE " + filterContext.getSearchString();
        }
        query.addToSql(sqlFilter);
    }

    private void addOrderBy(QueryWithParams query, FilterContext filterContext, Entity entity) {
        String[] orderBy = filterContext.getOrderBy();
        if (orderBy != null && orderBy.length > 0) {
            StringJoiner oby = new StringJoiner(", ");
            for (String obElem : orderBy) {
                obElem = obElem.toLowerCase();
                if (obElem.charAt(0) == '-') {
                    oby.add(wrapDoubleQuotes(obElem.substring(1)) + " DESC");
                } else {
                    oby.add(wrapDoubleQuotes(obElem) + " ASC");
                }
            }
            query.addToSql(" ORDER BY " + oby.toString());
        } else {
            // deterministic order by API
            // TODO need order by update field
            Entity.LogicalKey logicalKey = entity.getLogicalKey();
            StringJoiner sj = new StringJoiner(", ");
            for (EntityField field : logicalKey.getLogicalKeyList()) {
                sj.add(wrapDoubleQuotes(field.getName().toLowerCase()));
            }
            if (sj.length() > 0) {
                query.addToSql(" ORDER BY " + sj.toString());
            }
        }
    }

    private void addLimit(QueryWithParams query, FilterContext filterContext) {
        if (filterContext.getLimit() > 0) {
            query.addToSql(String.format(" LIMIT %d", filterContext.getLimit()));
        }
    }

    private void addOffset(QueryWithParams query, FilterContext filterContext) {
        if (filterContext.getStart() > 0) {
            query.addToSql(String.format(" OFFSET %d", filterContext.getStart()));
        }
    }

    private String createSelectQuerySQLFor(Entity entity) {
        String entityName = entity.getName().toLowerCase();
        return String.format("SELECT %1$s.* FROM %1$s ", wrapDoubleQuotes(entityName));
    }

    private QueryWithParams createSelectQueryFor(Entity entity) {
        String select = createSelectQuerySQLFor(entity);
        return new QueryWithParams(select);
    }

    private Set<EntityFieldValue> convertToEntityFieldValues(Entity entity, Collection<? extends FieldValue> filterFielValueType) throws EntityFieldException {
        Set<EntityFieldValue> filter = new HashSet<>();
        for (FieldValue fieldValue : filterFielValueType) {
            if (fieldValue instanceof EntityFieldValue) {
                EntityFieldValue ef = (EntityFieldValue) fieldValue;
                filter.add(ef);
            } else {
                filter.add(EntityFieldValue.create(entity, fieldValue));
            }
        }
        return filter;
    }


    private void checkInsertedRecord(long recordId, EntityRecord record, Optional<EntityRecord> insertedRecord) throws EntityRecordException {
        if (!insertedRecord.isPresent()) {
            throw EntityRecordException.INSERTED_RECORD_NOT_FOUND(record.getEntity(), record.getLogicalKeyValue());
        }
        EntityRecord ins = insertedRecord.get();
        long idField = ins.get(ins.getEntity().getIdEntityField());
        assert recordId == idField;
        // TODO check values is not easy... for Entity Ref and recursive logical keys
        /* for (DynamicRecord.FieldValue fvt : record.getFieldValues()) {
            Field field = fvt.get();
            Object value = fvt.getValue();
            Object persistedValue = ins.get(field);
            --> here we have some problems withGeminiSearchString entityReferences assert value.equals(persistedValue);
        } */
    }

    private boolean sameOf(@NotNull EntityRecord entityRecord, @NotNull EntityRecord persistedEntityRecord, Transaction transaction) throws GeminiException {
        if ((entityRecord != null && persistedEntityRecord == null) || (entityRecord == null && persistedEntityRecord != null)) {
            return true; // base case
        }

        for (EntityFieldValue fieldValue : entityRecord.getOnlyModifiedEntityFieldValue()) {
            EntityField field = fieldValue.getEntityField();
            if (fieldCanBeIgnoredInSameOf(field))
                continue;
            if (fieldValue.getValue() == null && persistedEntityRecord.get(field) == null) {
                continue;
            }
            FieldType type = field.getType();
            if (type.equals(FieldType.ENTITY_EMBEDED)) {
                EntityRecord embE = entityRecord.get(field);
                EntityRecord embPE = persistedEntityRecord.get(field);
                if (!sameOf(embE, embPE, transaction)) {
                    return false;
                }
            } else if (type.equals(FieldType.PASSWORD)) {
                // password is an Object that implements the equals
                if (!fieldValue.getValue().equals(persistedEntityRecord.get(field))) {
                    return false;
                }
            } else {
                // todo stiamo razzando via la roba non voluta
                Object valueRec = fromFieldToPrimitiveValue(fieldValue, /* TODO  */ Map.of(), transaction);
                EntityFieldValue persistedFVT = persistedEntityRecord.getEntityFieldValue(field);
                Object persistedValue = fromFieldToPrimitiveValue(persistedFVT, /* TODO  */ Map.of(), transaction);
                if (!((valueRec == null && persistedValue == null) || valueRec.equals(persistedValue))) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean fieldCanBeIgnoredInSameOf(Field field) {
        /* if (field.getType() == FieldType.ENTITY_COLLECTION_REF)
            return true; */
        return false;
    }

    private Optional<EntityRecord> getRecordByLogicalKeyInner(Transaction transaction, Entity entity, Collection<? extends FieldValue> logicalKeyValues) throws GeminiException {
        if (logicalKeyValues.isEmpty()) {
            return Optional.empty();
        }
        List<EntityRecord> recordsMatching = getEntityRecordsMatching(entity, logicalKeyValues, transaction);
        int size = recordsMatching.size();
        Assert.isTrue(recordsMatching.isEmpty() || size == 1, String.format("Logical Key must have 0 or 1 records found %s - Database is not consistent withGeminiSearchString schema", size));
        return size == 0 ? Optional.empty() : Optional.of(recordsMatching.get(0));
    }

    private List<EntityRecord> fromResultSetToEntityRecord(ResultSet rs, Entity entity, EntityResolutionContext resolutionContext, Transaction transaction) throws SQLException, GeminiException {
        List<EntityRecord> ret = new ArrayList<>();
        while (rs.next()) {
            EntityRecord er = new EntityRecord(entity);
            if (!entity.isEmbedable()) {
                er.setUUID(rs.getObject(Field.UUID_NAME, UUID.class));
            }
            for (EntityField field : entity.getALLEntityFields()) {
                FieldType type = field.getType();
                String fieldName = fieldName(field, false);
                boolean handled = false;
                if (oneToOneType(type)) {
                    Class specificType = typeClass(type);
                    Object object;
                    if (specificType != null && !specificType.isArray()) {
                        object = rs.getObject(fieldName, specificType);
                    } else if (specificType != null && specificType.isArray()) {
                        Array array = rs.getArray(fieldName);
                        assert array != null;
                        object = specificType.cast(array.getArray());
                    } else {
                        // try the default resolution
                        object = rs.getObject(fieldName);
                    }
                    er.put(field, object == null ? handleNullValueForField(field.getType()) : object);
                    handled = true;
                }
                if (type.equals(FieldType.ENTITY_REF)) {
                    EntityReferenceRecord entityReferenceRecord = null;
                    Object pkValue = rs.getObject(fieldName);
                    if (pkValue != null && (Number.class.isAssignableFrom(pkValue.getClass()) && ((Long) pkValue) != 0)) {
                        EntityRecord lkEntityRecord = getEntityRecordByPersistedID(transaction, field, pkValue);
                        entityReferenceRecord = createEntityReferenceRecordFromER(field.getEntityRef(), pkValue, lkEntityRecord);
                    }
                    er.put(field, entityReferenceRecord);
                    handled = true;
                }
                if (type.equals(FieldType.ENTITY_EMBEDED)) {
                    Object pkValue = rs.getObject(fieldName);
                    EntityRecord recordEmbeded = null;
                    if (pkValue != null && (Number.class.isAssignableFrom(pkValue.getClass()) && ((Long) pkValue) != 0)) {
                        recordEmbeded = getEntityRecordByPersistedID(transaction, field, pkValue);
                    }
                    er.put(field, recordEmbeded);
                    handled = true;
                }
                /* if (type.equals(FieldType.ENTITY_COLLECTION_REF)) {
                    if (resolutionContext.getCollection().equals(EntityResolutionContext.Strategy.NONE)) {
                        handled = true;
                    } else {
                        throw new RuntimeException(String.format("Field %s of type %s not handled - strategy must be implemented", field.getName(), field.getType()));
                    }
                } */
                if (type.equals(FieldType.ENTITY_REF_ARRAY)) {
                    Array array = rs.getArray(fieldName);
                    if (array == null) {
                        er.put(field, List.<EntityRef>of());
                    } else {
                        ResultSet rsArray = array.getResultSet(); // TODO ENTITY RESOLUTION CONTEXT
                        List<EntityReferenceRecord> erList = new ArrayList<>();
                        while (rsArray.next()) {
                            long entityID = rsArray.getLong(2);// index 2 contains the value (JDBC spec)
                            Optional<EntityRecord> entityRecordById = getEntityRecordById(field.getEntityRef(), entityID, transaction);
                            if (entityRecordById.isPresent()) {
                                erList.add(EntityReferenceRecord.fromEntityRecord(entityRecordById.get()));
                            } else {
                                throw new RuntimeException("TODO -- Critical exception, Inconsistent DB");
                            }
                        }
                        er.put(field, erList);
                    }
                    // TODO query effective resolution
                    handled = true;
                }
                if (type == FieldType.PASSWORD) {
                    handled = true;
                    String jsonST = rs.getString(fieldName);
                    Password pwd = null;
                    if (jsonST != null) {
                        ObjectMapper objectMapper = new ObjectMapper();
                        try {
                            pwd = objectMapper.readValue(jsonST, Password.class);
                        } catch (IOException e) {
                            throw new GeminiRuntimeException("Unable to convert Password from DB");
                        }
                    }
                    er.put(field, pwd);
                }
                if (type == FieldType.GENERIC_ENTITY_REF) {
                    handled = true;
                    Object entityIdValue = rs.getObject(genericRefEntityFieldName(field, false));
                    EntityReferenceRecord entityReferenceRecord = null;
                    if (entityIdValue != null && (Number.class.isAssignableFrom(entityIdValue.getClass()) && ((Long) entityIdValue) != 0)) {
                        Entity targetEntity = getEntityByID((Long) entityIdValue);
                        Object refId = rs.getObject(genericRefActualRefFieldName(field, false));
                        Optional<EntityRecord> entityRecordByPersistedID = getEntityRecordByPersistedID(transaction, targetEntity, refId);
                        // TODO resolutions -- if the entityrecord is not found probably the target record was deleted
                        // TODO             but the field was generic and we cannot statically resolve deletion at delete time
                        if (entityRecordByPersistedID.isPresent()) {
                            entityReferenceRecord = createEntityReferenceRecordFromER(targetEntity, refId, entityRecordByPersistedID.get());
                        } else {
                            entityReferenceRecord = null;
                        }
                    }
                    er.put(field, entityReferenceRecord);
                }
                if (!handled) {
                    throw new RuntimeException(String.format("Field %s of type %s not handled", field.getName(), field.getType()));
                }
            }
            er.put(entity.getIdEntityField(), rs.getLong(entity.getIdEntityField().getName()));
            ret.add(er);
        }
        return ret;
    }

    private EntityRecord getEntityRecordByPersistedID(Transaction transaction, EntityField field, Object pkValue) throws
            GeminiException {
        Entity entityRef = field.getEntityRef();
        FieldValue fieldValue = EntityFieldValue.create(entityRef.getIdEntityField(), pkValue);
        List<EntityRecord> recordsMatching = getEntityRecordsMatching(entityRef, Set.of(fieldValue), transaction);
        assert recordsMatching.size() == 1;
        return recordsMatching.get(0);
    }

    private Optional<EntityRecord> getEntityRecordByPersistedID(Transaction transaction, Entity entity, Object pkValue) throws
            GeminiException {
        return getEntityRecordById(entity, (Long) pkValue, transaction);
    }

    private QueryWithParams makeInsertQuery(EntityRecord record, Transaction transaction) throws GeminiException {
        Entity entity = record.getEntity();

        Map<EntityField, EntityRecord> embededEntityRecords = checkAndCreateEmbededEntity(record, transaction);

        StringBuilder sql = new StringBuilder(String.format("INSERT INTO %s", wrapDoubleQuotes(entity.getName().toLowerCase())));
        Map<String, Object> params = new HashMap<>();
        List<EntityFieldValue> sortedFields = sortFields(record.getALLEntityFieldValues());
        boolean first = true;
        if (!entity.isEmbedable()) {
            sql.append("(").append(Field.UUID_NAME);
            first = false;
        }
        for (EntityFieldValue field : sortedFields) {
            boolean fieldHandled = false;
            FieldType type = field.getField().getType();
            EntityField entityField = field.getEntityField();
            if (oneToOneType(type) || entityType(type) || passwordType(type)) {
                fieldHandled = true;
                sql.append(first ? "(" : ",");
                first = false;
                sql.append(fieldName(entityField, true));
            }
            if (genericEntityRefType(type)) {
                fieldHandled = true;
                sql.append(first ? "(" : ",");
                first = false;
                sql.append(genericRefEntityFieldName(entityField, true))
                        .append(", ")
                        .append(genericRefActualRefFieldName(entityField, true));
            }
            if (!fieldHandled) {
                throw new GeminiRuntimeException(String.format("Insert Query - Column for Field %s with type %s not handled", entityField.getName(), type));
            }
        }
        sql.append(") VALUES ");
        first = true;
        if (!entity.isEmbedable()) {
            first = false;
            sql.append("(:").append(Field.UUID_NAME);
            params.put(Field.UUID_NAME, record.getUUID());
        }
        for (EntityFieldValue field : sortedFields) {
            boolean fieldHandled = false;
            FieldType type = field.getField().getType();
            EntityField entityField = field.getEntityField();
            if (oneToOneType(type) || entityType(type) || passwordType(type)) {
                fieldHandled = true;
                String columnName = field.getField().getName().toLowerCase();
                sql.append(first ? "(" : ",");
                first = false;
                sql.append(":").append(columnName);
                if (type == FieldType.PASSWORD) {
                    sql.append("::JSON");
                }
                params.put(columnName, fromFieldToPrimitiveValue(field, embededEntityRecords, transaction));
            }
            if (genericEntityRefType(type)) {
                fieldHandled = true;
                sql.append(first ? "(" : ",");
                String entityRefParam = genericRefEntityFieldName(entityField, false).toLowerCase();
                String actualRefParam = genericRefActualRefFieldName(entityField, false).toLowerCase();
                sql.append(":").append(entityRefParam);
                sql.append(", ");
                sql.append(":").append(actualRefParam);
                GenericEntityRecPrimValue entityWithRef = (GenericEntityRecPrimValue) fromFieldToPrimitiveValue(field, embededEntityRecords, transaction);
                params.put(entityRefParam, entityWithRef.entityId);
                params.put(actualRefParam, entityWithRef.refId);
            }
            if (!fieldHandled) {
                throw new GeminiRuntimeException(String.format("Insert Query - Value for Field %s with type %s not handled", entityField.getName(), type));
            }
        }
        sql.append(")");
        return new QueryWithParams(sql.toString(), params);
    }

    private Map<EntityField, EntityRecord> checkAndCreateEmbededEntity(EntityRecord record, Transaction transaction) throws
            GeminiException {
        Map<EntityField, EntityRecord> results = new HashMap<>();
        for (EntityField entityField : record.getModifiedFields()) {
            if (entityField.getType().equals(FieldType.ENTITY_EMBEDED)) {
                EntityRecord embededRec = record.get(entityField);
                embededRec = createNewEntityRecord(embededRec, transaction);
                results.put(entityField, embededRec);
            }
        }
        return results;
    }

    private QueryWithParams makeModifyQueryFromID(EntityRecord record, Transaction transaction) throws
            GeminiException {
        Entity entity = record.getEntity();
        Map<EntityField, EntityRecord> embededEntityRecords = checkAndModifyEmbededEntyRecords(record, transaction);
        StringBuilder sql = new StringBuilder(String.format("UPDATE %s SET ", entity.getName().toLowerCase()));
        Map<String, Object> params = new HashMap<>();
        if (!record.getEntity().isEmbedable() && record.getUUID() != null) { // uuis should be updated only if it is provided
            sql.append(String.format(" %s = :%s , ", Field.UUID_NAME, Field.UUID_NAME));
            params.put(Field.UUID_NAME, record.getUUID());
        }
        List<EntityFieldValue> sortedFields = sortFields(record.getOnlyModifiedEntityFieldValue());
        for (int i = 0; i < sortedFields.size(); i++) {
            EntityFieldValue field = sortedFields.get(i);
            EntityField entityField = field.getEntityField();
            String columnName = fieldName(entityField, true);
            FieldType type = field.getField().getType();
            if (oneToOneType(type) || entityType(type) || passwordType(type)) {
                sql.append(String.format(" %s = :%s", columnName, entityField.getName().toLowerCase()));
                if (type == FieldType.PASSWORD) {
                    sql.append("::JSON");
                }
                params.put(entityField.getName().toLowerCase(), fromFieldToPrimitiveValue(field, embededEntityRecords, transaction));
            } else if (genericEntityRefType(type)) {
                String entityRefParam = genericRefEntityFieldName(entityField, false).toLowerCase();
                String actualRefParam = genericRefActualRefFieldName(entityField, false).toLowerCase();
                sql.append(String.format(" %s = :%s", genericRefEntityFieldName(entityField, true), entityRefParam));
                sql.append(", ");
                sql.append(String.format(" %s = :%s", genericRefActualRefFieldName(entityField, true), actualRefParam));
                GenericEntityRecPrimValue entityWithRef = (GenericEntityRecPrimValue) fromFieldToPrimitiveValue(field, embededEntityRecords, transaction);
                params.put(entityRefParam, entityWithRef.entityId);
                params.put(actualRefParam, entityWithRef.refId);
            } else {
                throw new GeminiRuntimeException(String.format("Modify entity record - type %s not handled", type));
            }
            sql.append(i == sortedFields.size() - 1 ? " " : " , ");

        }
        sql.append(String.format(" WHERE %s = %s", Field.ID_NAME, record.get(record.getEntity().getIdEntityField(), Long.class)));
        return new QueryWithParams(sql.toString(), params);
    }

    private Map<EntityField, EntityRecord> checkAndModifyEmbededEntyRecords(EntityRecord record, Transaction transaction) throws GeminiException {
        Map<EntityField, EntityRecord> results = new HashMap<>();
        for (EntityField entityField : record.getModifiedFields()) {
            if (entityField.getType().equals(FieldType.ENTITY_EMBEDED)) {
                EntityRecord embededRec = record.get(entityField);
                if (embededRec != null) {
                    if (embededRec.getID() != null) {
                        embededRec = updateEntityRecordByID(embededRec, transaction);
                    } else {
                        embededRec = createNewEntityRecord(embededRec, transaction);
                    }
                    results.put(entityField, embededRec);
                }
            }
        }
        return results;
    }

    private QueryWithParams makeSelectQueryFilteringFiledValue(Entity entity, Set<EntityFieldValue> filterValues, Transaction transaction) throws SQLException, GeminiException {
        String entityName = entity.getName().toLowerCase();
        String sql = String.format("SELECT %1$s.* FROM %1$s WHERE ", wrapDoubleQuotes(entityName));
        Map<String, Object> params = new HashMap<>();
        boolean needAnd = false;
        for (EntityFieldValue filterValue : filterValues) {
            EntityField field = filterValue.getEntityField();
            FieldType type = field.getType();
            sql += needAnd ? "AND" : "";
            if (oneToOneType(type) || type.equals(FieldType.ENTITY_REF)) {
                sql += handleSingleColumnSelectBasicType(entityName, params, filterValue, transaction);
            }
            needAnd = true;
        }
        return new QueryWithParams(sql, params);
    }

    private String handleSingleColumnSelectBasicType(String entityName, Map<String, Object> params, EntityFieldValue fieldValue, Transaction transaction) throws GeminiException {
        String colName = fieldValue.getField().getName();
        Object value = fromFieldToPrimitiveValue(fieldValue, /* TODO  */ Map.of(), transaction);
        String res = String.format(" \"%s\".\"%s\" = :%s ", entityName.toLowerCase(), fieldName(fieldValue.getEntityField(), false), colName.toLowerCase());
        params.put(colName.toLowerCase(), value);
        return res;
    }

    private QueryWithParams makeDeleteQueryByID(EntityRecord record, Transaction transaction) throws GeminiException {
        Entity entity = record.getEntity();
        checkAndDeleteEmbededEntity(record, transaction);
        String sql = String.format("DELETE FROM %s WHERE %s = %s", wrapDoubleQuotes(entity.getName().toLowerCase()), Field.ID_NAME, record.get(record.getEntity().getIdEntityField(), Long.class));
        return new QueryWithParams(sql, null);
    }

    private void checkAndDeleteEmbededEntity(EntityRecord record, Transaction transaction) throws GeminiException {
        for (EntityField entityField : record.getModifiedFields()) {
            if (entityField.getType().equals(FieldType.ENTITY_EMBEDED)) {
                EntityRecord embededRec = record.get(entityField);
                deleteEntityRecordByID(embededRec, transaction);
            }
        }
    }

    private Object fromFieldToPrimitiveValue(FieldValue fieldValue, Map<EntityField, EntityRecord> embededEntityRecords, Transaction transaction) throws GeminiException {
        Object value = fieldValue.getValue();
        Field field = fieldValue.getField();
        FieldType type = field.getType();
        if (value == null) {
            return handleNullValueForField(type);
        }
        if (oneToOneType(type)) {
            return value;
        }
        if (type.equals(FieldType.PASSWORD)) {
            if (Password.class.isAssignableFrom(value.getClass())) {
                return toJSONValue(value);
            }
        }
        if (type.equals(FieldType.ENTITY_REF)) {
            EntityReferenceRecord refRecord;
            if (EntityRecord.class.isAssignableFrom(value.getClass())) {
                EntityRecord er = (EntityRecord) value;
                refRecord = createEntityReferenceRecordFromER(field.getEntityRef(), er.getID(), er);
            } else if (!EntityReferenceRecord.class.isAssignableFrom(value.getClass())) {
                refRecord = logicalKeyFromObject(field.getEntityRef(), value);
            } else {
                refRecord = (EntityReferenceRecord) value;
            }
            if (refRecord.hasPrimaryKey()) {
                return refRecord.getPrimaryKey();
            } else {
                if (refRecord.hasLogicalKey()) {
                    DynamicRecord lkValue = refRecord.getLogicalKeyRecord();
                    Entity entityRef = field.getEntityRef();
                    Set<FieldValue> lkFieldValuesType = lkValue.getFieldValues(entityRef.getLogicalKey().getLogicalKeySet());

                    List<EntityRecord> lkRecords = getEntityRecordsMatching(entityRef, lkFieldValuesType, transaction);
                    if (lkRecords.isEmpty()) {
                        throw EntityRecordException.LK_NOTFOUND(entityRef, lkFieldValuesType);
                    }
                    if (lkRecords.size() != 1) {
                        throw EntityRecordException.MULTIPLE_LK_FOUND(refRecord);
                    }
                    EntityRecord entityRecord = lkRecords.get(0);
                    return entityRecord.get(entityRef.getIdEntityField());
                }
                throw new RuntimeException(String.format("fromFieldToPrimitiveValue %s - EntityRef without Pk or LK", field.getName()));
            }
        }
        if (type.equals(FieldType.ENTITY_EMBEDED)) {
            EntityRecord embededEntity = embededEntityRecords.get(field);
            return embededEntity.get(embededEntity.getEntity().getIdEntityField());
        }
        if (type.equals(FieldType.ENTITY_REF_ARRAY)) {
            if (Collection.class.isAssignableFrom(value.getClass())) {
                Collection<Object> genColl = (Collection) value;
                if (genColl.isEmpty()) {
                    return null;
                }
                Object[] elements = genColl.toArray();
                Object firstElem = elements[0];

                Long[] entityRefIds = new Long[elements.length];
                int i = 0;
                if (EntityRecord.class.isAssignableFrom(firstElem.getClass())) {
                    Collection<EntityRecord> entityRecords = (Collection<EntityRecord>) value;
                    for (EntityRecord entityRecord : entityRecords) {
                        if (entityRecord.getID() != null) {
                            entityRefIds[i++] = (long) entityRecord.getID();
                        } else {
                            Optional<EntityRecord> queryedEntityRec = getEntityRecordByLogicalKey(entityRecord, transaction);
                            if (queryedEntityRec.isPresent()) {
                                entityRefIds[i++] = (long) queryedEntityRec.get().getID();
                            } else {
                                throw EntityRecordException.LK_NOTFOUND(entityRecord.getEntity(), entityRecord.getLogicalKeyValue());
                            }
                        }
                    }
                }

                TransactionImpl tImp = (TransactionImpl) transaction;
                Connection connection = tImp.getConnection();
                try {
                    return connection.createArrayOf("BIGINT", entityRefIds);
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
        if (type.equals(FieldType.GENERIC_ENTITY_REF)) {
            if (EntityRecord.class.isAssignableFrom(value.getClass())) {
                EntityRecord er = (EntityRecord) value;
                EntityRecord fullEr = er;
                if (!er.hasID()) {
                    Optional<EntityRecord> erbyLk = getEntityRecordByLogicalKey(er, transaction);
                    if (erbyLk.isPresent()) {
                        fullEr = erbyLk.get();
                    } else {
                        throw EntityRecordException.LK_NOTFOUND(er.getEntity(), er.getLogicalKeyValue());
                    }
                }
                GenericEntityRecPrimValue grec = new GenericEntityRecPrimValue();
                grec.entityId = (Long) er.getEntity().getIDValue();
                grec.refId = (Long) er.getID();
                return grec;
            }
            if (EntityReferenceRecord.class.isAssignableFrom(value.getClass())) {
                EntityReferenceRecord erf = (EntityReferenceRecord) value;
                if (!erf.hasPrimaryKey()) {
                    Optional<EntityRecord> erbyLk = getEntityRecordByLogicalKey(erf.getEntity(), erf.getLogicalKeyRecord().getFieldValues(), transaction);
                    if (erbyLk.isPresent()) {
                        return GenericEntityRecPrimValue.from((Long) erf.getEntity().getIDValue(), (Long) erbyLk.get().getID());
                    } else {
                        throw EntityRecordException.LK_NOTFOUND(erf.getEntity(), erf.getLogicalKeyRecord().getFieldValues());
                    }
                }
                if (erf.hasPrimaryKey()) {
                    return GenericEntityRecPrimValue.from((Long) erf.getEntity().getIDValue(), (Long) erf.getPrimaryKey());
                }
            }
            throw new RuntimeException(String.format("fromFieldToPrimitiveValue - For %s - Implemented only EntityRecord", field.getType()));
        }
        throw new RuntimeException(String.format("fromFieldToPrimitiveValue - Not implemented %s", field.getType()));
    }

    private Object toJSONValue(Object value) {
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new GeminiRuntimeException(String.format("Unable to Stringigy JSON value %s", value));
        }
    }

    @Override
    public UUID getUUIDforEntityRecord(EntityRecord record) {
        // uuid: EntityName + LogicalKey --> it should be unique
        StringBuilder uuidString = new StringBuilder(record.getEntity().getName());
        Set<EntityFieldValue> logicalKeyValues = record.getLogicalKeyValue();
        for (EntityFieldValue lkValue : logicalKeyValues) {
            uuidString.append(fromEntityFieldToUUID(lkValue));
        }
        if (logicalKeyValues.isEmpty() && !record.getEntity().isOneRecord()) {
            uuidString.append(System.currentTimeMillis());
        }
        return UUID.nameUUIDFromBytes(uuidString.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String fromEntityFieldToUUID(EntityFieldValue lkValue) {
        EntityField field = lkValue.getEntityField();
        FieldType type = field.getType();
        Object value = lkValue.getValue();
        if (value == null) {
            return "null";
        }
        if (oneToOneType(type)) {
            return value.toString();
        }
        if (type.equals(FieldType.ENTITY_REF)) {
            EntityReferenceRecord refRecord;
            Entity entityRef = field.getEntityRef();
            if (!EntityReferenceRecord.class.isAssignableFrom(value.getClass())) {
                refRecord = logicalKeyFromObject(entityRef, value);
            } else {
                refRecord = (EntityReferenceRecord) value;
            }
            if (refRecord.hasLogicalKey()) {
                DynamicRecord logicalKeyRecord = refRecord.getLogicalKeyRecord();
                List<EntityField> lkv = entityRef.getLogicalKey().getLogicalKeyList();
                StringBuilder res = new StringBuilder();
                for (EntityField lkPieceField : lkv) {
                    EntityFieldValue entityFieldValue = EntityFieldValue.create(lkPieceField, logicalKeyRecord.getFieldValue(lkPieceField));
                    res.append(fromEntityFieldToUUID(entityFieldValue));
                }
                return res.toString();
            } else {
                logger.warn(String.format("Using primary key to generate the UUID for %s.%s - may be an error", field.getEntity().getName(), field.getName()));
            }
        }
        throw new RuntimeException(String.format("fromEntityFieldToUUID - Not implemented %s", field.getType()));
    }

    private Object handleNullValueForField(FieldType type) {
        switch (type) {
            case NUMBER:
                return 0;
            case PK:
                return 0;
            case TEXT:
                return "";
            case LONG:
                return 0L;
            case DOUBLE:
                return 0.;
            case BOOL:
                return false;
            case TIME:
            case DATE:
            case DATETIME:
            case ENTITY_REF_ARRAY:
            case PASSWORD:
                return null;
            case ENTITY_REF:
                return 0;
            case TEXT_ARRAY:
                return new String[]{};
            case ENTITY_EMBEDED:
                return 0;
            case RECORD:
                break;
            case GENERIC_ENTITY_REF:
                return GenericEntityRecPrimValue.NULL;
        }
        throw new RuntimeException(String.format("NO Null Value for type %s", type.name()));
    }

    private Class typeClass(FieldType type) {
        switch (type) {
            case NUMBER:
                return null;
            case PK:
                return BigInteger.class;
            case TEXT:
            case PASSWORD:
                return String.class;
            case LONG:
                return Long.class;
            case DOUBLE:
                return Double.class;
            case BOOL:
                return Boolean.class;
            case TIME:
                return LocalTime.class;
            case DATE:
                return LocalDate.class;
            case DATETIME:
                return LocalDateTime.class;
            case ENTITY_REF:
                return BigInteger.class;
            case TEXT_ARRAY:
                return String[].class;
            case RECORD:
                break;
        }
        throw new RuntimeException(String.format("No Class for type %s", type.name()));
    }

    private <T extends FieldValue> List<T> sortFields(Collection<T> fieldValue) {
        return fieldValue.stream().sorted(Comparator.comparing(f -> f.getField().getName())).collect(Collectors.toList());
    }

    class QueryWithParams {
        private StringBuilder sqlBuilder;
        private Map<String, Object> params;

        public QueryWithParams(String sql) {
            this.sqlBuilder = new StringBuilder(sql);
            this.params = new HashMap<>();
        }

        public QueryWithParams(String sql, Map<String, Object> params) {
            this.sqlBuilder = new StringBuilder(sql);
            this.params = params;
        }

        public QueryWithParams addToSql(String sql) {
            sqlBuilder.append(sql);
            return this;
        }

        public String getSql() {
            return sqlBuilder.toString();
        }

        public Map<String, Object> getParams() {
            return params;
        }
    }

    static class GenericEntityRecPrimValue {
        Long entityId;
        Long refId;

        static GenericEntityRecPrimValue from(Long entityId, Long refID) {
            GenericEntityRecPrimValue ret = new GenericEntityRecPrimValue();
            ret.entityId = entityId;
            ret.refId = refID;
            return ret;
        }

        static GenericEntityRecPrimValue NULL = from(0L, 0L);
    }
}
