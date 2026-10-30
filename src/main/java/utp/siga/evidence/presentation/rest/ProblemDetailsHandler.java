package utp.siga.evidence.presentation.rest;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import utp.siga.evidence.application.exception.EvidenceException;

@RestControllerAdvice
public class ProblemDetailsHandler {
    @ExceptionHandler(EvidenceException.class)
    ResponseEntity<ProblemResponse> evidence(EvidenceException exception, HttpServletRequest request) {
        return response(exception.status(), exception.code(), exception.getMessage(), request);
    }

    @ExceptionHandler({MissingRequestHeaderException.class, MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemResponse> invalidRequest(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "La solicitud de evidencia no es válida", request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemResponse> tooLarge(MaxUploadSizeExceededException exception, HttpServletRequest request) {
        return response(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "El archivo supera el límite permitido", request);
    }

    private ResponseEntity<ProblemResponse> response(HttpStatus status, String code, String detail,
            HttpServletRequest request) {
        UUID correlation = correlation(request.getHeader("X-Correlation-Id"));
        ProblemResponse body = new ProblemResponse("https://siga.local/problems/" + code.toLowerCase().replace('_', '-'),
                status.getReasonPhrase(), status.value(), detail, request.getRequestURI(), code, Instant.now(), correlation);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    private static UUID correlation(String value) {
        try { return value == null ? UUID.randomUUID() : UUID.fromString(value); }
        catch (IllegalArgumentException ignored) { return UUID.randomUUID(); }
    }

    record ProblemResponse(String type, String title, int status, String detail, String instance, String code,
            Instant timestamp, UUID correlationId) { }
}
