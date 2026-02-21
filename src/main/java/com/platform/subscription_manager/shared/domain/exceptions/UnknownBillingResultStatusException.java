package com.platform.subscription_manager.shared.domain.exceptions;

public class UnknownBillingResultStatusException extends RuntimeException {
	public UnknownBillingResultStatusException(String message) {
		super(message);
	}
}
