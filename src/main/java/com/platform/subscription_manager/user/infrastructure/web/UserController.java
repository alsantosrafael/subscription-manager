package com.platform.subscription_manager.user.infrastructure.web;

import com.platform.subscription_manager.user.application.dtos.CreateUserDTO;
import com.platform.subscription_manager.user.application.dtos.UserResponseDTO;
import com.platform.subscription_manager.user.application.services.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;


@RestController
@RequestMapping("/v1/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = "Gerenciamento de usuários. Crie um usuário antes de criar uma assinatura.")
public class UserController {
	private final UserService userService;
	@Operation(
		summary = "Criar usuário",
		description = "Registra um novo usuário. O `id` retornado é necessário para criar uma assinatura.",
		responses = {
			@ApiResponse(responseCode = "201", description = "Usuário criado com sucesso",
				content = @Content(schema = @Schema(implementation = UserResponseDTO.class))),
			@ApiResponse(responseCode = "400", description = "Dados de entrada inválidos (nome, e-mail ou documento ausentes/malformados)", content = @Content),
			@ApiResponse(responseCode = "409", description = "E-mail ou documento já cadastrado", content = @Content)
		}
	)
	@PostMapping
	public ResponseEntity<UserResponseDTO> create(@Valid @RequestBody CreateUserDTO userPayload) {
		return ResponseEntity.status(201).body(userService.create(userPayload));
	}

	@Operation(
		summary = "Buscar usuário por ID",
		responses = {
			@ApiResponse(responseCode = "200", description = "Usuário encontrado",
				content = @Content(schema = @Schema(implementation = UserResponseDTO.class))),
			@ApiResponse(responseCode = "404", description = "Usuário não encontrado", content = @Content)
		}
	)
	@GetMapping("/{id}")
	public ResponseEntity<UserResponseDTO> getUser(
		@Parameter(description = "UUID do usuário", required = true) @PathVariable UUID id) {
		return ResponseEntity.ok(userService.getUserById(id));
	}
}
