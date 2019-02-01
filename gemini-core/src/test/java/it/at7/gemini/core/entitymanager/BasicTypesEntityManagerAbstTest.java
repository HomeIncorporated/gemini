package it.at7.gemini.core.entitymanager;

import it.at7.gemini.core.EntityRecord;
import it.at7.gemini.core.Record;
import it.at7.gemini.core.Services;
import it.at7.gemini.exceptions.EntityRecordException;
import it.at7.gemini.exceptions.GeminiException;
import it.at7.gemini.schema.Entity;
import org.junit.Assert;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public abstract class BasicTypesEntityManagerAbstTest {

    @Test
    public void n1_putIfAbsent() throws GeminiException {
        // put with a simple logical key (string) -- others values are default
        EntityRecord entityRecord = TestData.getTestDataTypeEntityRecord("logKey");
        EntityRecord testEntity = Services.getEntityManager().putIfAbsent(entityRecord);
        assertEquals("logKey", testEntity.get("text")); // real field
        assertEquals(0, (long) testEntity.get("numberLong")); // default
        assertEquals(0, (long) testEntity.get("numberDouble")); // default
        assertEquals(0., testEntity.get("double"), 0.001); // default
        assertEquals(0, (long) testEntity.get("long")); // default
        assertEquals(false, testEntity.get("bool")); // default
        assertNull(testEntity.get("date")); // default
        assertNull(testEntity.get("time")); // default
        assertNull(testEntity.get("datetime")); // default
    }

    @Test(expected = EntityRecordException.class)
    public void n1_putIfAbsent_ExceptionExist() throws GeminiException {
        EntityRecord entityRecord = TestData.getTestDataTypeEntityRecord("logKey");
        Services.getEntityManager().putIfAbsent(entityRecord);
    }

    @Test
    public void n2_putWithAllBasicTypes() throws GeminiException {
        EntityRecord entityRecord = TestData.getTestDataTypeEntityRecord("logKey-allBasicTypes");
        entityRecord.put("numberLong", 10);
        entityRecord.put("numberDouble", 11.1);
        entityRecord.put("bool", true);
        entityRecord.put("date", LocalDate.of(1989, 6, 9));
        entityRecord.put("time", LocalTime.of(7, 7, 7));
        entityRecord.put("datetime", LocalDateTime.of(1989, 6, 9, 7, 7, 7));
        EntityRecord testEntity = Services.getEntityManager().putIfAbsent(entityRecord);
        assertEquals("logKey-allBasicTypes", testEntity.get("text")); // real field
        assertEquals(10, (long) testEntity.get("numberLong"));
        assertEquals(11.1, testEntity.get("numberDouble"), 0.01);
        assertEquals(LocalDate.of(1989, 6, 9), testEntity.get("date"));
        assertEquals(LocalTime.of(7, 7, 7), testEntity.get("time"));
        assertEquals(LocalDateTime.of(1989, 6, 9, 7, 7, 7), testEntity.get("datetime"));
        assertEquals(true, testEntity.get("bool")); // default
    }

    @Test
    public void n3_update() throws GeminiException {
        EntityRecord entityRecord = TestData.getTestDataTypeEntityRecord("logKey");
        entityRecord.put("numberLong", 10);
        Set<EntityRecord.EntityFieldValue> logicalKey = entityRecord.getLogicalKeyValue();
        EntityRecord updated = Services.getEntityManager().update(entityRecord, logicalKey);
        Assert.assertEquals(10L, (long) updated.get("numberLong"));

        EntityRecord updatedByGet = Services.getEntityManager().get(entityRecord.getEntity(), logicalKey);
        Assert.assertEquals(updated.getID(), updatedByGet.getID());
    }

    @Test(expected = EntityRecordException.class)
    public void n4_updateAlsoLogicalKey() throws GeminiException {
        EntityRecord entityRecord = TestData.getTestDataTypeEntityRecord("logKey");
        Set<EntityRecord.EntityFieldValue> logicalKey = entityRecord.getLogicalKeyValue();
        entityRecord.put("text", "anotherLogicalKey");
        EntityRecord updated = Services.getEntityManager().update(entityRecord, logicalKey);
        Assert.assertEquals(10L, (long) updated.get("numberLong")); // previous update
        assertEquals("anotherLogicalKey", updated.get("text")); // new logical Key
        Services.getEntityManager().get(entityRecord.getEntity(), logicalKey); // not found the previous logical key
    }

    @Test(expected = EntityRecordException.class)
    public void n5_delete() throws GeminiException {
        EntityRecord entityRecord = TestData.getTestDataTypeEntityRecord("anotherLogicalKey");
        Set<EntityRecord.EntityFieldValue> logicalKey = entityRecord.getLogicalKeyValue();
        EntityRecord deleted = Services.getEntityManager().delete(entityRecord.getEntity(), entityRecord.getLogicalKeyValue());
        assertEquals("anotherLogicalKey", deleted.get("text")); // delete the new logical key record
        Services.getEntityManager().get(entityRecord.getEntity(), logicalKey); // deleted record should not be found
    }

    @Test
    public void n6_putOrUpdate() throws GeminiException {
        EntityRecord entityRecord = TestData.getTestDataTypeEntityRecord("logKey");
        entityRecord.put("numberLong", 10);
        EntityRecord testEntity = Services.getEntityManager().putOrUpdate(entityRecord);
        assertEquals("logKey", testEntity.get("text"));
        assertEquals(Long.valueOf(10), testEntity.get("numberLong"));
        assertEquals(Long.valueOf(0), testEntity.get("numberDouble"));
        assertEquals(false, testEntity.get("bool"));
        testEntity.put("numberDouble", 10.5);
        EntityRecord testEntityUpdate = Services.getEntityManager().putOrUpdate(testEntity);
        assertEquals("logKey", testEntityUpdate.get("text"));
        assertEquals(Long.valueOf(10), testEntityUpdate.get("numberLong"));
        assertEquals(Double.valueOf(10.5), testEntityUpdate.get("numberDouble"));
        assertEquals(false, testEntityUpdate.get("bool"));
    }

    @Test
    public void n7_getRecordMatching() throws GeminiException {
        Entity testDataTypeEntity = TestData.getTestDataTypeEntity();
        Record record = new Record();
        record.put("text", "logKey");
        List<EntityRecord> recordsMatching = Services.getEntityManager().getRecordsMatching(testDataTypeEntity, record);
        assertEquals(1, recordsMatching.size());
        EntityRecord entityRecord = recordsMatching.get(0);
        assertEquals("logKey", entityRecord.get("text"));
        assertEquals(Long.valueOf(10), entityRecord.get("numberLong"));
        assertEquals(Double.valueOf(10.5), entityRecord.get("numberDouble"));
        assertEquals(false, entityRecord.get("bool"));
    }

}