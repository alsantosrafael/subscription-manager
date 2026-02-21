package com.platform.subscription_manager.shared.domain.exceptions;

public class ConflictException extends RuntimeException {
	public ConflictException(String message) {
		super(message);
	}
}

