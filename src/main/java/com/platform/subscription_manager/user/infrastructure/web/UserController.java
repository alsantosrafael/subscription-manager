package com.platform.subscription_manager.user.infrastructure.web;

import com.platform.subscription_manager.user.application.dtos.CreateUserDTO;
import com.platform.subscription_manager.user.application.dtos.UserResponseDTO;
import com.platform.subscription_manager.user.application.services.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/v1/users")
@RequiredArgsConstructor
public class UserController {
	private final UserService userService;
	@PostMapping
	public ResponseEntity<UserResponseDTO> create(@Valid @RequestBody CreateUserDTO userPayload) {
		return ResponseEntity.status(201).body(userService.create(userPayload));
	}

}
