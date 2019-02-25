package it.at7.gemini.api;

import it.at7.gemini.core.EntityManager;
import it.at7.gemini.core.EntityRecord;
import it.at7.gemini.core.FilterContext;
import it.at7.gemini.core.RecordConverters;
import it.at7.gemini.exceptions.EntityException;
import it.at7.gemini.exceptions.GeminiException;
import it.at7.gemini.exceptions.InvalidRequesException;
import it.at7.gemini.schema.Entity;
import it.at7.gemini.schema.EntityField;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.*;

@RestController
@RequestMapping("/api/{entity}")
public class RestAPIController {
    public static final String SEARCH_PARAMETER = "search";
    public static final String GEMINI_HEADER = "Gemini";

    public static final String GEMINI_SIMPLE_DATA_TYPE = "gemini.api.nometa";
    public static final String GEMINI_DATA_TYPE = "gemini.api";


    private EntityManager entityManager;

    @Autowired
    public RestAPIController(EntityManager entityManager) {
        this.entityManager = entityManager;
    }


    @RequestMapping(value = "/**")
    @ResponseStatus(HttpStatus.OK)
    Object allRequestHandler(@PathVariable String entity,
                             @RequestBody(required = false) Object body,
                             HttpServletRequest request,
                             HttpServletResponse response) throws GeminiException {

        Object results = requestHandler(entity, body, request, response);
        List<String> geminiHeaderValues = getGeminiHeader(request); // request.getHeader(GEMINI_ACCEPT_TYPE);
        if (geminiClassicResponse(geminiHeaderValues)) {
            return results;
        }
        return handleGeminiDataTypeResponse(results, request, response);
    }

    private Object handleGeminiDataTypeResponse(Object results, HttpServletRequest request, HttpServletResponse response) throws InvalidRequesException {
        response.setHeader(GEMINI_HEADER, GEMINI_DATA_TYPE);
        if (results instanceof EntityRecord) {
            return GeminiWrappers.EntityRecordApiType.of((EntityRecord) results);
        }
        if (results instanceof GeminiWrappers.EntityRecordsList) {
            return GeminiWrappers.EntityRecordListApiType.of((GeminiWrappers.EntityRecordsList) results);
        }
        throw InvalidRequesException.CANNOT_HANDLE_REQUEST();
    }

    private List<String> getGeminiHeader(HttpServletRequest request) {
        String header = request.getHeader(GEMINI_HEADER);
        return header == null ? Collections.emptyList() : Arrays.asList(header.split(","));
    }

    private boolean geminiClassicResponse(List<String> geminiAcceptTypeHeader) {
        if (geminiAcceptTypeHeader == null || geminiAcceptTypeHeader.isEmpty() || geminiAcceptTypeHeader.contains(GEMINI_SIMPLE_DATA_TYPE)) {
            return true;
        }
        return false;
    }

    private Object requestHandler(String entity, Object body, HttpServletRequest request, HttpServletResponse response) throws GeminiException {
        Entity e = checkEntity(entity.toUpperCase());
        UriComponents uriComponents = UriComponentsBuilder.fromUriString(request.getRequestURI()).build();
        List<String> paths = uriComponents.getPathSegments();
        ensurePathsAreConsistent(paths, e);
        String method = request.getMethod();
        Map<String, String[]> parameters = request.getParameterMap(); // query string params
        if (paths.size() == 2) {
            // this is a root entity requet - METHOD ALLOWED POST AND GET (for list)
            switch (method) {
                case "POST":
                    if (body == null) {
                        throw InvalidRequesException.BODY_REQUIRED();
                    }
                    return handleInsertRecord(e, body);
                case "GET":
                    return handleGetEntityList(e, parameters);
                default:
                    throw InvalidRequesException.INVALID_METHOD_FOR_REQUEST(method);
            }
        }

        if (paths.size() > 2) {
            int requestLkLenght = paths.size() - 2;
            Entity.LogicalKey logicalKey = e.getLogicalKey();
            List<EntityField> logicalKeyList = logicalKey.getLogicalKeyList();
            if (logicalKeyList.size() < requestLkLenght) {
                throw InvalidRequesException.CANNOT_HANDLE_REQUEST();
            }
            if (logicalKeyList.size() == requestLkLenght) {
                // the entity.. we can update or delete
                List<String> lkStrings = paths.subList(2, paths.size());
                String[] lkStringsArray = lkStrings.toArray(new String[lkStrings.size()]);
                switch (method) {
                    case "GET":
                        return handleGetRecord(e, lkStringsArray);
                    case "PUT":
                        return handleUpdateRecord(e, body, lkStringsArray);
                    case "DELETE":
                        return handleDeleteRecord(e, lkStringsArray);
                    default:
                        throw InvalidRequesException.INVALID_METHOD_FOR_REQUEST(method);
                }
            }
            if (logicalKeyList.size() > requestLkLenght) {
                // we are quering a subfield or a subfield list
                throw InvalidRequesException.CANNOT_HANDLE_REQUEST();
            }
        }
        throw InvalidRequesException.CANNOT_HANDLE_REQUEST();
    }

    private GeminiWrappers.EntityRecordsList handleGetEntityList(Entity e, Map<String, String[]> parameters) throws GeminiException {
        String searchString = getSearchFromParameters(parameters.get(SEARCH_PARAMETER));
        FilterContext filterContext = FilterContext.BUILDER()
                .withGeminiSearchString(searchString)
                .build();
        List<EntityRecord> recordList = entityManager.getRecordsMatching(e, filterContext);
        return GeminiWrappers.EntityRecordsList.of(recordList);
    }

    private String getSearchFromParameters(String[] searchParams) {
        if (searchParams != null && searchParams.length > 0) {
            return searchParams[0]; // only the first supporterd
        }
        return "";
    }

    private Object handleInsertRecord(Entity e, Object body) throws GeminiException {
        if (Map.class.isAssignableFrom(body.getClass()))
            return handleInsertRecord((Map<String, Object>) body, e);
        if (List.class.isAssignableFrom(body.getClass())) {
            return handleInsertRecords((List<Map<String, Object>>) body, e);
        }
        return null;
    }

    private EntityRecord handleInsertRecord(Map<String, Object> body, Entity e) throws GeminiException {
        EntityRecord rec = RecordConverters.entityRecordFromMap(e, body);
        return entityManager.putIfAbsent(rec);
    }

    private Object handleInsertRecords(List<Map<String, Object>> body, Entity e) throws GeminiException {
        List<EntityRecord> records = new ArrayList<>();
        for (Map<String, Object> record : body) {
            records.add(RecordConverters.entityRecordFromMap(e, record));
        }
        Collection<EntityRecord> entityRecords = entityManager.putIfAbsent(records);
        return GeminiWrappers.EntityRecordsList.of(entityRecords);
    }


    private Object handleUpdateRecord(Entity e, Object body, String... logicalKey) throws GeminiException {
        if (Map.class.isAssignableFrom(body.getClass()))
            return handleUpdateRecord(e, (Map<String, Object>) body, logicalKey);
        throw InvalidRequesException.INVALID_BODY();
    }

    private EntityRecord handleUpdateRecord(Entity e, Map<String, Object> body, String... logicalKey) throws GeminiException {
        EntityRecord rec = RecordConverters.entityRecordFromMap(e, body);
        List<EntityRecord.EntityFieldValue> logicalKeyValues = RecordConverters.logicalKeyFromStrings(e, logicalKey);
        return entityManager.update(rec, logicalKeyValues);
    }

    private EntityRecord handleDeleteRecord(Entity e, String... logicalKey) throws GeminiException {
        List<EntityRecord.EntityFieldValue> logicalKeyValues = RecordConverters.logicalKeyFromStrings(e, logicalKey);
        return entityManager.delete(e, logicalKeyValues);
    }

    private EntityRecord handleGetRecord(Entity e, String... logicalKey) throws GeminiException {
        List<EntityRecord.EntityFieldValue> logicalKeyValues = RecordConverters.logicalKeyFromStrings(e, logicalKey);
        return entityManager.get(e, logicalKeyValues);
    }

    /* private EntityRecord handleGetRecord(Entity e, String logicalKey) throws SQLException, GeminiException {
        Set<DynamicRecord.FieldValue> logicalKeyValue = Set.of(Field.RecordConverters.logicalKeyFromStrings(e, logicalKey));
        return entityManager.get(e, logicalKeyValue);
    } */

    private Entity checkEntity(String entityStr) throws EntityException {
        Entity entity = entityManager.getEntity(entityStr);
        if (entity == null) {
            throw EntityException.ENTITY_NOT_FOUND(entityStr.toUpperCase());
        }
        if (entity.isEmbedable()) {
            throw EntityException.API_NOT_ALLOWED_ON_EMBEDABLE(entityStr.toUpperCase());
        }
        return entity;
    }

    private void ensurePathsAreConsistent(List<String> paths, Entity e) {
        assert e != null;
        assert paths.size() >= 2;
        assert paths.get(0).equals("api");
        assert paths.get(1).toUpperCase().equals(e.getName().toUpperCase());
    }
}
