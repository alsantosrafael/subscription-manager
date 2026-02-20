package com.platform.subscription_manager.shared.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

	/** Resource not found → 404 */
	@ExceptionHandler(ResourceNotFoundException.class)
	public ProblemDetail handleNotFound(ResourceNotFoundException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
		problem.setTitle("Not Found");
		return problem;
	}

	/** Uniqueness constraint violated → 409 */
	@ExceptionHandler(ConflictException.class)
	public ProblemDetail handleConflict(ConflictException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
		problem.setTitle("Conflict");
		return problem;
	}

	/** Business invariant violated → 422 */
	@ExceptionHandler(UnprocessableEntityException.class)
	public ProblemDetail handleUnprocessable(UnprocessableEntityException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
		problem.setTitle("Unprocessable Entity");
		return problem;
	}
}

