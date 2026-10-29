package dev.challenge.activation.api;

import dev.challenge.activation.application.ActivationRequestNotFoundException;
import dev.challenge.activation.application.ExternalIdConflictException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.List;

@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    record InvalidField(String field, String message) {
    }

    @ExceptionHandler(ActivationRequestNotFoundException.class)
    ProblemDetail handleNotFound(ActivationRequestNotFoundException exception, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, ProblemType.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(ExternalIdConflictException.class)
    ProblemDetail handleConflict(ExternalIdConflictException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, ProblemType.EXTERNAL_ID_CONFLICT, exception.getMessage(), request);
    }

    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class,
            TransientDataAccessException.class})
    ProblemDetail handleDatabaseUnavailable(Exception exception, HttpServletRequest request) {
        log.error("Banco de dados indisponível ao processar {} {}", request.getMethod(), request.getRequestURI(),
                exception);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, ProblemType.SERVICE_UNAVAILABLE,
                "Serviço temporariamente indisponível. Tente novamente em instantes.", request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Erro inesperado ao processar {} {}", request.getMethod(), request.getRequestURI(), exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ProblemType.INTERNAL_ERROR,
                "Ocorreu um erro inesperado. Informe o traceId ao suporte.", request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<InvalidField> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new InvalidField(error.getField(), error.getDefaultMessage()))
                .toList();
        return validationProblem(exception, errors, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException exception,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        List<InvalidField> errors = exception.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new InvalidField(result.getMethodParameter().getParameterName(),
                                error.getDefaultMessage())))
                .toList();
        return validationProblem(exception, errors, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException exception, HttpHeaders headers,
                                                        HttpStatusCode status, WebRequest request) {
        List<InvalidField> errors = List.of(new InvalidField(exception.getPropertyName(), "valor inválido"));
        return validationProblem(exception, errors, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "O corpo da requisição está ausente ou não é um JSON válido.");
        describe(problem, ProblemType.MALFORMED_REQUEST);
        return handleExceptionInternal(exception, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode statusCode,
                                                          WebRequest request) {
        if (body instanceof ProblemDetail problem && request instanceof ServletWebRequest servletRequest) {
            addContext(problem, servletRequest.getRequest());
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private ResponseEntity<Object> validationProblem(Exception exception, List<InvalidField> errors,
                                                     HttpHeaders headers, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Um ou mais campos não passaram na validação.");
        describe(problem, ProblemType.VALIDATION_ERROR);
        problem.setProperty("errors", errors);
        return handleExceptionInternal(exception, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    private static ProblemDetail problem(HttpStatus status, ProblemType type, String detail,
                                         HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        describe(problem, type);
        addContext(problem, request);
        return problem;
    }

    private static void describe(ProblemDetail problem, ProblemType type) {
        problem.setType(type.uri());
        problem.setTitle(type.title());
    }

    private static void addContext(ProblemDetail problem, HttpServletRequest request) {
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("traceId", CorrelationIdFilter.current());
    }
}
