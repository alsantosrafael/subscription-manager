package com.platform.subscription_manager.user.application.dtos;


public record CreateUserDTO(
	String name,
	String document,
	String email
) {
}
