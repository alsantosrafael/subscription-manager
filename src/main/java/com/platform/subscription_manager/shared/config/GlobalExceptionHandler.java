package com.platform.subscription_manager.shared.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.platform.subscription_manager.shared.ConflictException;
import com.platform.subscription_manager.shared.ResourceNotFoundException;
import com.platform.subscription_manager.shared.UnprocessableEntityException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
		var errors = ex.getBindingResult().getFieldErrors().stream()
			.collect(Collectors.toMap(
				FieldError::getField,
				fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "invalid value",
				(msg1, msg2) -> msg1 + ", " + msg2
			));
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
		problem.setTitle("Bad Request");
		problem.setProperty("errors", errors);
		return problem;
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ProblemDetail handleNotReadable(HttpMessageNotReadableException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed or missing request body");
		problem.setTitle("Bad Request");
		return problem;
	}

	@ExceptionHandler(ResourceNotFoundException.class)
	public ProblemDetail handleNotFound(ResourceNotFoundException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
		problem.setTitle("Not Found");
		return problem;
	}

	@ExceptionHandler(ConflictException.class)
	public ProblemDetail handleConflict(ConflictException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
		problem.setTitle("Conflict");
		return problem;
	}

	@ExceptionHandler(UnprocessableEntityException.class)
	public ProblemDetail handleUnprocessable(UnprocessableEntityException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(422), ex.getMessage());
		problem.setTitle("Unprocessable Entity");
		return problem;
	}

	@ExceptionHandler(Exception.class)
	public ProblemDetail handleAllUncaughtException(Exception ex) {
		log.error("Unhandled exception", ex);
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
			HttpStatus.INTERNAL_SERVER_ERROR,
			"An unexpected error occurred. Please contact support."
		);
		problem.setTitle("Internal Server Error");
		return problem;
	}
}
