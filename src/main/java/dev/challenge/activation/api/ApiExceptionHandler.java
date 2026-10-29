package dev.challenge.activation.api;

import dev.challenge.activation.application.ActivationRequestNotFoundException;
import dev.challenge.activation.application.ExternalIdConflictException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.NestedExceptionUtils;
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

import java.util.List;

@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    record InvalidField(String field, String message) {
    }

    @ExceptionHandler(ActivationRequestNotFoundException.class)
    ProblemDetail handleNotFound(ActivationRequestNotFoundException exception, HttpServletRequest request) {
        return Problems.of(HttpStatus.NOT_FOUND, ProblemType.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(ExternalIdConflictException.class)
    ProblemDetail handleConflict(ExternalIdConflictException exception, HttpServletRequest request) {
        return Problems.of(HttpStatus.CONFLICT, ProblemType.EXTERNAL_ID_CONFLICT, exception.getMessage(), request);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<ProblemDetail> handleRateLimit(RateLimitExceededException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(exception.retryAfter().toSeconds()))
                .body(Problems.of(HttpStatus.TOO_MANY_REQUESTS, ProblemType.TOO_MANY_REQUESTS,
                        exception.getMessage(), request));
    }

    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class,
            TransientDataAccessException.class})
    ProblemDetail handleDatabaseUnavailable(Exception exception, HttpServletRequest request) {
        log.error("Banco de dados indisponível ao processar {} {}", request.getMethod(), request.getRequestURI(),
                exception);
        return Problems.of(HttpStatus.SERVICE_UNAVAILABLE, ProblemType.SERVICE_UNAVAILABLE,
                "Serviço temporariamente indisponível. Tente novamente em instantes.", request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Erro inesperado ao processar {} {}", request.getMethod(), request.getRequestURI(), exception);
        return Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, ProblemType.INTERNAL_ERROR,
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
        if (NestedExceptionUtils.getMostSpecificCause(exception) instanceof RequestBodyTooLargeException tooLarge) {
            ProblemDetail problem = Problems.of(HttpStatus.PAYLOAD_TOO_LARGE, ProblemType.PAYLOAD_TOO_LARGE,
                    tooLarge.getMessage());
            return handleExceptionInternal(exception, problem, headers, HttpStatus.PAYLOAD_TOO_LARGE, request);
        }
        ProblemDetail problem = Problems.of(HttpStatus.BAD_REQUEST, ProblemType.MALFORMED_REQUEST,
                "O corpo da requisição está ausente ou não é um JSON válido.");
        return handleExceptionInternal(exception, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode statusCode,
                                                          WebRequest request) {
        if (body instanceof ProblemDetail problem && request instanceof ServletWebRequest servletRequest) {
            Problems.withRequestContext(problem, servletRequest.getRequest());
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private ResponseEntity<Object> validationProblem(Exception exception, List<InvalidField> errors,
                                                     HttpHeaders headers, WebRequest request) {
        ProblemDetail problem = Problems.of(HttpStatus.BAD_REQUEST, ProblemType.VALIDATION_ERROR,
                "Um ou mais campos não passaram na validação.");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(exception, problem, headers, HttpStatus.BAD_REQUEST, request);
    }
}
