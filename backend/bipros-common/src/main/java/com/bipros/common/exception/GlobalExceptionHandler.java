package com.bipros.common.exception;

import com.bipros.common.dto.ApiError;
import com.bipros.common.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * When true, the generic 500 handler includes the exception class + message + top stack frame
     * in the response body. Enable in dev via:
     *   bipros.errors.include-detail: true
     * Leave false in production so stack traces don't leak.
     */
    @Value("${bipros.errors.include-detail:false}")
    private boolean includeExceptionDetail;

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(ValidationException ex) {
        log.warn("Validation error: {}", ex.getMessage());
        List<ApiError.FieldError> details = ex.getFieldErrors().stream()
                .map(fe -> new ApiError.FieldError(fe.field(), fe.message()))
                .toList();
        var error = new ApiError("VALIDATION_ERROR", ex.getMessage(), details);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(error));
    }

    /** Duplicate-code / already-exists guard (e.g. LabourDesignationService). Returns 409 Conflict. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException ex) {
        log.warn("Conflict: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("DUPLICATE_RESOURCE", ex.getMessage()));
    }

    /** Validation guard (e.g. code-prefix / category mismatch). Returns 400 Bad Request. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("VALIDATION_ERROR", ex.getMessage()));
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessRule(BusinessRuleException ex) {
        log.warn("Business rule violation [{}]: {}", ex.getRuleCode(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ex.getRuleCode(), ex.getMessage()));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        log.warn("Optimistic lock failure: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("CONFLICT", "Resource was modified by another user. Please refresh and retry."));
    }

    @ExceptionHandler(ConcurrencyException.class)
    public ResponseEntity<ApiResponse<Void>> handleConcurrency(ConcurrencyException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("CONFLICT", ex.getMessage()));
    }

    @ExceptionHandler({AuthorizationDeniedException.class, AccessDeniedException.class})
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(RuntimeException ex) {
        // Log with username + a request hint so the audit trail can correlate denials with the
        // request URI (the request URI itself is captured by the access log; we keep this
        // handler dependency-free of the AuditService to avoid a bipros-common → bipros-* cycle).
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        String who = auth == null ? "anonymous" : auth.getName();
        log.warn("ACCESS_DENIED user={} reason={}", who, ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("FORBIDDEN", "Access denied"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgNotValid(MethodArgumentNotValidException ex) {
        List<ApiError.FieldError> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        var error = new ApiError("VALIDATION_ERROR", "Request validation failed", details);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(error));
    }

    /** Client sent syntactically invalid JSON (e.g. unescaped quote, trailing comma). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMalformedRequest(HttpMessageNotReadableException ex) {
        Throwable mostSpecific = ex.getMostSpecificCause();
        // Jackson surfaces invalid enum values as InvalidFormatException. Format a clean message
        // that lists the valid values rather than leaking package-qualified Java class names.
        if (mostSpecific instanceof com.fasterxml.jackson.databind.exc.InvalidFormatException ife
            && ife.getTargetType() != null && ife.getTargetType().isEnum()) {
            String fieldPath = ife.getPath().stream()
                .map(com.fasterxml.jackson.databind.JsonMappingException.Reference::getFieldName)
                .filter(java.util.Objects::nonNull)
                .reduce((a, b) -> a + "." + b)
                .orElse("value");
            String allowed = java.util.Arrays.stream(ife.getTargetType().getEnumConstants())
                .map(Object::toString)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
            String msg = String.format("Invalid value for '%s': must be one of [%s]", fieldPath, allowed);
            log.warn("Enum deserialization failed: {}", msg);
            List<ApiError.FieldError> details = List.of(new ApiError.FieldError(fieldPath, msg));
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(new ApiError("VALIDATION_ERROR", msg, details)));
        }
        String cause = mostSpecific != null ? mostSpecific.getMessage() : ex.getMessage();
        log.warn("Malformed request body: {}", cause);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("MALFORMED_JSON",
                        "Request body could not be parsed: " + cause));
    }

    /**
     * Path variable or query parameter of the wrong type — most commonly "Failed to convert value
     * 'summary' to UUID" when a collection endpoint and a resource-by-id endpoint live on the
     * same path prefix and the more specific literal route isn't matched.
     */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {
        String expectedType = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "expected type";
        String msg = String.format("Parameter '%s' has invalid value '%s' (expected %s)",
            ex.getName(), ex.getValue(), expectedType);
        log.warn("Argument type mismatch: {}", msg);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("INVALID_PARAMETER", msg));
    }

    /** Required query/form parameter was not provided. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
        log.warn("Missing request parameter: {} ({})", ex.getParameterName(), ex.getParameterType());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("MISSING_PARAMETER",
                        "Required parameter '" + ex.getParameterName() + "' is missing"));
    }

    /**
     * Database constraint violation (unique, FK, check). Unique violations are reported as
     * DUPLICATE_RESOURCE so callers can distinguish duplicate-code errors from other integrity
     * failures.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(DataIntegrityViolationException ex) {
        String root = rootMessage(ex);
        boolean unique = root != null && root.toLowerCase().contains("unique constraint");
        String code = unique ? "DUPLICATE_RESOURCE" : "DATA_INTEGRITY";
        log.warn("Data integrity violation [{}]: {}", code, root);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(code,
                        unique ? "Duplicate value violates " + root : "Database constraint violation: " + root));
    }

    /** Request path doesn't match any controller — Spring's default 404 for missing routes. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex) {
        log.warn("No resource: {}", ex.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("NOT_FOUND",
                        "No endpoint exists at " + ex.getResourcePath()));
    }

    /** Controller exists but this HTTP method isn't supported. Returns Allow header per RFC 7231. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("Method not supported: {} (allowed: {})", ex.getMethod(), ex.getSupportedHttpMethods());
        var builder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (ex.getSupportedMethods() != null) {
            for (String m : ex.getSupportedMethods()) builder.header("Allow", m);
        }
        return builder.body(ApiResponse.error("METHOD_NOT_ALLOWED",
                "HTTP method '" + ex.getMethod() + "' is not supported for this endpoint"));
    }

    /**
     * Client closed the SSE / async response mid-stream (browser tab closed,
     * Playwright killed, network drop, server-side timeout). Spring wraps the
     * underlying Tomcat ClientAbortException in AsyncRequestNotUsableException
     * and re-enters the dispatcher; if we let that propagate to {@link
     * #handleGeneral} it tries to write an {@code ApiResponse} JSON body to a
     * response whose Content-Type is already {@code text/event-stream}, which
     * fails with {@code HttpMessageNotWritableException} and pollutes the log
     * with two stack traces per disconnect.
     *
     * Returning {@code void} from a handler skips response-body negotiation
     * entirely. The TCP connection is gone — there is no client to write to.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncResponseClosed(AsyncRequestNotUsableException ex) {
        if (log.isDebugEnabled()) {
            log.debug("SSE/async client disconnected before stream completed: {}", ex.getMessage());
        }
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneral(Exception ex, HttpServletResponse response) {
        // If the response is already committed (e.g. SSE has flushed
        // headers + some events) we can't change the status or write a JSON
        // body — the underlying socket has half-written content. Trying
        // would produce "HttpMessageNotWritableException: No converter for
        // [class ApiResponse] with preset Content-Type 'text/event-stream'".
        // Log once and bail.
        if (response.isCommitted()) {
            log.warn("Unexpected error after response committed (suppressing body write): {}", ex.toString());
            return null;
        }
        log.error("Unexpected error", ex);
        String message = "An unexpected error occurred";
        if (includeExceptionDetail) {
            StackTraceElement top = ex.getStackTrace() != null && ex.getStackTrace().length > 0
                    ? ex.getStackTrace()[0] : null;
            message = ex.getClass().getSimpleName() + ": " + ex.getMessage()
                    + (top != null ? " @ " + top : "");
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("INTERNAL_ERROR", message));
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage();
    }
}
