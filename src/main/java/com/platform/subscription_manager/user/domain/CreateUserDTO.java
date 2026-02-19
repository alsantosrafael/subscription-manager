package com.platform.subscription_manager.user.domain;


public record CreateUserDTO(
	String name,
	String document,
	String email
) {
}
