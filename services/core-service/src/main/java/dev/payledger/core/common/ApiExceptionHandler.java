package dev.payledger.core.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;

/**
 * Renders every error as RFC 9457 "application/problem+json", with the request id to quote to support, and notes the
 * error's code and message for the request log ({@link RequestLog}).
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ProblemDetail handle(ApiException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        problem.setType(URI.create("https://github.com/payledger/problems/" + e.code()));
        problem.setTitle(e.code());
        problem.setProperty("code", e.code());
        return noted(problem, request, e.code(), e.getMessage());
    }

    /** Anything unexpected: a 500 that says nothing about the inside, and the full story in the log under the request id. */
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e, HttpServletRequest request) {
        log.error("Unexpected error in {} {}", request.getMethod(), request.getRequestURI(), e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong on our side. If it keeps happening, tell support the request id.");
        problem.setTitle("internal-error");
        problem.setProperty("code", "internal-error");
        return noted(problem, request, "internal-error", e.getClass().getName() + ": " + e.getMessage());
    }

    /** Spring's own errors (bad JSON, failed validation, wrong method): the same code and request id, and noted too. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, status, request);
        String code = "http-" + status.value();
        String detail = ex.getMessage();
        if (response != null && response.getBody() instanceof ProblemDetail p) {
            if (p.getProperties() == null || p.getProperties().get("code") == null) {
                p.setProperty("code", code);
            }
            Object id = request.getAttribute(RequestLog.REQUEST_ID, RequestAttributes.SCOPE_REQUEST);
            if (id != null) {
                p.setProperty("requestId", id);
            }
        }
        request.setAttribute(RequestLog.ERROR_CODE, code, RequestAttributes.SCOPE_REQUEST);
        request.setAttribute(RequestLog.ERROR_DETAIL, detail, RequestAttributes.SCOPE_REQUEST);
        return response;
    }

    private static ProblemDetail noted(ProblemDetail problem, HttpServletRequest request, String code, String detail) {
        Object id = request.getAttribute(RequestLog.REQUEST_ID);
        if (id != null) {
            problem.setProperty("requestId", id);
        }
        request.setAttribute(RequestLog.ERROR_CODE, code);
        request.setAttribute(RequestLog.ERROR_DETAIL, detail);
        return problem;
    }
}
