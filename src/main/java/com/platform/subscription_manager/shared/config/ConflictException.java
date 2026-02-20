package com.platform.subscription_manager.shared.config;

/** Thrown when a uniqueness constraint is violated. Maps to HTTP 409. */
public class ConflictException extends RuntimeException {
	public ConflictException(String message) {
		super(message);
	}
}
