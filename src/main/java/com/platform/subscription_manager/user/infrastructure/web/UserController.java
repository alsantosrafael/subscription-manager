package com.platform.subscription_manager.user.infrastructure.web;

import com.platform.subscription_manager.user.application.dtos.CreateUserDTO;
import com.platform.subscription_manager.user.application.dtos.UserResponseDTO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/v1/users")
public class UserController {

	@PostMapping
	public ResponseEntity<UserResponseDTO> create(@RequestBody CreateUserDTO userPayload) {
		// TODO: check if a user with the same document or email already exists -> 409
		// TODO: persist user
		return ResponseEntity.status(201).body(new UserResponseDTO(
			UUID.randomUUID(),
			userPayload.name(),
			userPayload.document(),
			userPayload.email(),
			LocalDateTime.now()
		));
	}

}
