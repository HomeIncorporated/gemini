package it.at7.gemini.api;

import it.at7.gemini.exceptions.EntityRecordException;
import it.at7.gemini.exceptions.GeminiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice("it.at7.gemini.api")
public class APIExceptionHandlerController {

    @ExceptionHandler(EntityRecordException.class)
    public ResponseEntity<?> handleEntityFoundException(EntityRecordException exception) {
        HttpStatus httpStatus = HttpStatus.INTERNAL_SERVER_ERROR;
        switch (exception.getErrorCode()) {
            case MULTIPLE_LK_FOUND:
                httpStatus = HttpStatus.CONFLICT;
                break;
            case LK_NOTFOUND:
            case INSERTED_RECORD_NOT_FOUND:
                httpStatus = HttpStatus.NOT_FOUND;
                break;
        }
        return new ResponseEntity<>(new ApiError(httpStatus, exception.getErrorCodeName(), exception.getMessage()), httpStatus);
    }

    @ExceptionHandler(GeminiException.class)
    public ResponseEntity<?> handleRestoCheckedException(GeminiException exception) {
        return new ResponseEntity<>(new ApiError(HttpStatus.INTERNAL_SERVER_ERROR, exception.getErrorCodeName(), exception.getMessage()), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleException(Exception exception) {
        return new ResponseEntity<>(new ApiError(HttpStatus.INTERNAL_SERVER_ERROR, "CRITICAL_EXCEPTION", exception.getMessage()), HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
