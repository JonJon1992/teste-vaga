package dev.challenge.activation.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;

final class Problems {

    private Problems() {
    }

    static ProblemDetail of(HttpStatus status, ProblemType type, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type.uri());
        problem.setTitle(type.title());
        return problem;
    }

    static ProblemDetail of(HttpStatus status, ProblemType type, String detail, HttpServletRequest request) {
        return withRequestContext(of(status, type, detail), request);
    }

    static ProblemDetail withRequestContext(ProblemDetail problem, HttpServletRequest request) {
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("traceId", CorrelationIdFilter.current());
        return problem;
    }
}
