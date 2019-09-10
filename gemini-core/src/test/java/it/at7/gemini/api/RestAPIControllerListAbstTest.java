package it.at7.gemini.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.at7.gemini.core.EntityRecord;
import it.at7.gemini.core.Services;
import it.at7.gemini.core.entitymanager.TestData;
import org.junit.Assert;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static it.at7.gemini.api.ApiUtility.GEMINI_API_META_TYPE;
import static it.at7.gemini.api.ApiUtility.GEMINI_HEADER;
import static it.at7.gemini.api.MockMVCUtils.API_PATH;
import static it.at7.gemini.api.MockMVCUtils.mockMvc;
import static it.at7.gemini.core.FilterContextBuilder.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class RestAPIControllerListAbstTest {

    @Test
    public void n1_getList() throws Exception {
        // lets save 10 entity records
        for (int i = 1; i <= 10; i++) {
            EntityRecord entityRecord = TestData.getTestDataTypeForFilterEntityRecord("logKey-" + i);
            Services.getEntityManager().putIfAbsent(entityRecord);
        }
        MvcResult result = mockMvc.perform(get(API_PATH + "/TestDataTypeFilter")
                .contentType(APPLICATION_JSON)
                .accept(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        String stringResponseBody = result.getResponse().getContentAsString();
        List<Map<String, Object>> listRecord = new ObjectMapper().readValue(stringResponseBody,
                new TypeReference<List<Map<String, Object>>>() {
                });
        Assert.assertEquals(10, listRecord.size());

        // with gemini API Data Type - default limit
        mockMvc.perform(get(API_PATH + "/TestDataTypeFilter")
                .header(GEMINI_HEADER, GEMINI_API_META_TYPE)
                .contentType(APPLICATION_JSON)
                .accept(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content()
                        .json("{'meta':{'limit': 100}}"));
    }

    @Test
    public void n2_getListDefaultLimit() throws Exception {
        // lets save other 100 records
        for (int i = 100; i < 200; i++) {
            EntityRecord entityRecord = TestData.getTestDataTypeForFilterEntityRecord("logKey-" + i);
            Services.getEntityManager().putIfAbsent(entityRecord);
        }
        MvcResult result = mockMvc.perform(get(API_PATH + "/TestDataTypeFilter")
                .contentType(APPLICATION_JSON)
                .accept(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        String stringResponseBody = result.getResponse().getContentAsString();
        List<Map<String, Object>> listRecord = new ObjectMapper().readValue(stringResponseBody,
                new TypeReference<List<Map<String, Object>>>() {
                });
        Assert.assertEquals(100, listRecord.size());

        // no need to check the limit - done in n1
    }

    @Test
    public void n3_getListLimitParameter() throws Exception {
        for (int i = 2000; i < 2050; i++) {
            EntityRecord entityRecord = TestData.getTestDataTypeForFilterEntityRecord("logKey-" + i);
            entityRecord.put("numberLong", i);
            Services.getEntityManager().putIfAbsent(entityRecord);
        }
        MvcResult result = mockMvc.perform(get(API_PATH + "/TestDataTypeFilter")
                .param(LIMIT_PARAMETER, "30")
                .contentType(APPLICATION_JSON)
                .accept(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        String stringResponseBody = result.getResponse().getContentAsString();
        List<Map<String, Object>> listRecord = new ObjectMapper().readValue(stringResponseBody,
                new TypeReference<List<Map<String, Object>>>() {
                });
        Assert.assertEquals(30, listRecord.size());

        // gemini Api Meta - have limit
        mockMvc.perform(get(API_PATH + "/TestDataTypeFilter")
                .param(LIMIT_PARAMETER, "30")
                .header(GEMINI_HEADER, GEMINI_API_META_TYPE)
                .contentType(APPLICATION_JSON)
                .accept(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content()
                        .json("{'meta':{'limit': 30}}"));
    }

    @Test
    public void n4_getLisNoLimit() throws Exception {
        MvcResult result = mockMvc.perform(get(API_PATH + "/TestDataTypeFilter")
                .param(LIMIT_PARAMETER, "0")
                .contentType(APPLICATION_JSON)
                .accept(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        String stringResponseBody = result.getResponse().getContentAsString();
        List<Map<String, Object>> listRecord = new ObjectMapper().readValue(stringResponseBody,
                new TypeReference<List<Map<String, Object>>>() {
                });

        // the sum of the previously inserted records
        Assert.assertEquals(160, listRecord.size());

        // gemini Api Meta - no limit
        mockMvc.perform(get(API_PATH + "/TestDataTypeFilter")
                .param(LIMIT_PARAMETER, "0")
                .header(GEMINI_HEADER, GEMINI_API_META_TYPE)
                .contentType(APPLICATION_JSON)
                .accept(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content()
                        .json("{'meta':{}}"));
    }


    @Test
    public void n5_getLisLimitPlusStart() throws Exception {
        MvcResult result = mockMvc.perform(get(API_PATH + "/TestDataTypeFilter")
                .param(LIMIT_PARAMETER, "50")
                .param(START_PARAMETER, "150")
                .contentType(APPLICATION_JSON)
                .accept(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        String stringResponseBody = result.getResponse().getContentAsString();
        List<Map<String, Object>> listRecord = new ObjectMapper().readValue(stringResponseBody,
                new TypeReference<List<Map<String, Object>>>() {
                });

        // the sum of the previously inserted records - from 150 to 160 are 10
        Assert.assertEquals(10, listRecord.size());

        // gemini Api Meta
        mockMvc.perform(get(API_PATH + "/TestDataTypeFilter")
                .param(LIMIT_PARAMETER, "50")
                .param(START_PARAMETER, "150")
                .header(GEMINI_HEADER, GEMINI_API_META_TYPE)
                .contentType(APPLICATION_JSON)
                .accept(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content()
                        .json("{'meta':{'limit': 50, 'start': 150}}"));
    }

    @Test
    public void n6_getLisLimitPlusStartAndOrderBy() throws Exception {

        // DESCENDING
        MvcResult result = mockMvc.perform(get(API_PATH + "/TestDataTypeFilter")
                .param(ORDER_BY_PARAMETER, "-numberLong")
                .contentType(APPLICATION_JSON)
                .accept(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        String stringResponseBody = result.getResponse().getContentAsString();
        List<Map<String, Object>> listRecord = new ObjectMapper().readValue(stringResponseBody,
                new TypeReference<List<Map<String, Object>>>() {
                });
        // the default limit
        Assert.assertEquals(100, listRecord.size());
        for (int i = 0; i < 50; i++) {
            Map<String, Object> theRec = listRecord.get(i);
            int numberLong = (int) theRec.get("numberLong");
            Assert.assertEquals(2049 - i, numberLong);
        }

        mockMvc.perform(get(API_PATH + "/TestDataTypeFilter")
                .param(ORDER_BY_PARAMETER, "-numberLong")
                .header(GEMINI_HEADER, GEMINI_API_META_TYPE)
                .contentType(APPLICATION_JSON)
                .accept(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content()
                        .json("{'meta':{'limit': 100, orderBy: ['-numberLong']}}"));


        // ASCENDING
        result = mockMvc.perform(get(API_PATH + "/TestDataTypeFilter")
                .param(ORDER_BY_PARAMETER, "numberLong")
                .param(LIMIT_PARAMETER, "0")
                .contentType(APPLICATION_JSON)
                .accept(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        stringResponseBody = result.getResponse().getContentAsString();
        listRecord = new ObjectMapper().readValue(stringResponseBody,
                new TypeReference<List<Map<String, Object>>>() {
                });
        // no limit
        Assert.assertEquals(160, listRecord.size());
        for (int i = 110; i < 160; i++) {
            Map<String, Object> theRec = listRecord.get(i);
            int numberLong = (int) theRec.get("numberLong");
            // from 2000 to 2049 (inserted in n3)
            Assert.assertEquals(2000 + i - 110, numberLong);
        }

        mockMvc.perform(get(API_PATH + "/TestDataTypeFilter")
                .param(ORDER_BY_PARAMETER, "numberLong")
                .header(GEMINI_HEADER, GEMINI_API_META_TYPE)
                .contentType(APPLICATION_JSON)
                .accept(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content()
                        .json("{'meta':{orderBy: ['numberLong']}}"));
    }

}
