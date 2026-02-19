package com.platform.subscription_manager.user.application.dtos;

import java.time.LocalDateTime;
import java.util.UUID;

public record UserResponseDTO(
	UUID id,
	String name,
	String document,
	String email,
	LocalDateTime createdAt
) {
}
