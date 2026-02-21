package com.platform.subscription_manager.shared.domain.exceptions;

public class UnprocessableEntityException extends RuntimeException {
	public UnprocessableEntityException(String message) {
		super(message);
	}
}

