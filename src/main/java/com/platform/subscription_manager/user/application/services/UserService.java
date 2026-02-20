package com.platform.subscription_manager.user.application.services;

import com.platform.subscription_manager.shared.config.ConflictException;
import com.platform.subscription_manager.user.UserFacade;
import com.platform.subscription_manager.user.application.dtos.CreateUserDTO;
import com.platform.subscription_manager.user.domain.entity.User;
import com.platform.subscription_manager.user.domain.repositories.UserRepository;
import com.platform.subscription_manager.user.application.dtos.UserResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService implements UserFacade {

	private final UserRepository userRepository;

	@Transactional
	public UserResponseDTO create(CreateUserDTO payload) {
		if (userRepository.existsByDocumentOrEmail(payload.document(), payload.email())) {
			throw new ConflictException("User with this document or email already exists");
		}

		User user = new User(payload.name(), payload.document(), payload.email());
		User savedUser = userRepository.save(user);

		return new UserResponseDTO(
			savedUser.getId(),
			savedUser.getName(),
			savedUser.getDocument(),
			savedUser.getEmail(),
			savedUser.getCreatedAt()
		);
	}

	// Método que o módulo vizinho (Subscription) vai chamar!
	public boolean exists(UUID userId) {
		return userRepository.existsById(userId);
	}
}