package com.platform.subscription_manager.user.application.dtos;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUserDTO(
	@NotBlank String name,
	@NotBlank @Size(max = 20) String document,
	@NotBlank @Email String email
) {
}
