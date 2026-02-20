package com.platform.subscription_manager.shared.config;

/** Thrown when a business invariant is violated. Maps to HTTP 422. */
public class UnprocessableEntityException extends RuntimeException {
	public UnprocessableEntityException(String message) {
		super(message);
	}
}

