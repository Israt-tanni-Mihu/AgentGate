package com.agentgate.gateway.shared.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns errors into RFC 9457 Problem Details (Content-Type: application/problem+json), course rule C3.
 *
 * ResponseEntityExceptionHandler already handles Spring MVC errors and every ErrorResponseException
 * (our ToolNotFoundException → 404, DuplicateToolNameException → 409, SpecImportException).
 * We add: a per-field "errors" map for validation failures, and two Spring Data exceptions.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        for (ObjectError globalError : ex.getBindingResult().getGlobalErrors()) {
            errors.putIfAbsent(globalError.getObjectName(), globalError.getDefaultMessage());
        }
        ProblemDetail problem = ex.getBody();
        problem.setDetail("Request validation failed");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    /** Backup for the unique name check: two requests creating the same name at the same moment. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The change conflicts with existing data (for example a duplicate name)");
        problem.setTitle("Conflict");
        return problem;
    }

    /** e.g. GET /api/admin/tools?sort=doesNotExist */
    @ExceptionHandler(PropertyReferenceException.class)
    ProblemDetail handleUnknownSortProperty(PropertyReferenceException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Unknown sort property '" + ex.getPropertyName() + "'");
        problem.setTitle("Bad Request");
        return problem;
    }
}
