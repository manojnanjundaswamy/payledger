package dev.payledger.core.common;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;

/** Renders every error as RFC 9457 "application/problem+json". */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ProblemDetail handle(ApiException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        problem.setType(URI.create("https://github.com/payledger/problems/" + e.code()));
        problem.setTitle(e.code());
        problem.setProperty("code", e.code());
        return problem;
    }
}
