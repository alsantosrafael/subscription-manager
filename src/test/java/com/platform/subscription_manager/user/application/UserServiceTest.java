package com.platform.subscription_manager.user.application;

import com.platform.subscription_manager.shared.domain.exceptions.ConflictException;
import com.platform.subscription_manager.user.application.dtos.CreateUserDTO;
import com.platform.subscription_manager.user.application.dtos.UserResponseDTO;
import com.platform.subscription_manager.user.application.services.UserService;
import com.platform.subscription_manager.user.domain.entity.User;
import com.platform.subscription_manager.user.domain.repositories.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService")
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserService userService;

    private static final CreateUserDTO VALID_PAYLOAD =
        new CreateUserDTO("John Doe", "12345678900", "john@example.com");

    @Nested
    @DisplayName("create()")
    class Create {

        @Test
        @DisplayName("Returns a populated UserResponseDTO on success")
        void happyPath() {
            var saved = new User(VALID_PAYLOAD.name(), VALID_PAYLOAD.document(), VALID_PAYLOAD.email());

            when(userRepository.existsByDocumentOrEmail(VALID_PAYLOAD.document(), VALID_PAYLOAD.email()))
                .thenReturn(false);
            when(userRepository.save(any(User.class))).thenReturn(saved);

            UserResponseDTO response = userService.create(VALID_PAYLOAD);

            assertNotNull(response);
            assertEquals(VALID_PAYLOAD.name(),     response.name());
            assertEquals(VALID_PAYLOAD.document(), response.document());
            assertEquals(VALID_PAYLOAD.email(),    response.email());
            assertNotNull(response.createdAt());
        }

        @Test
        @DisplayName("Persists the user exactly once on success")
        void savesOnce() {
            var saved = new User(VALID_PAYLOAD.name(), VALID_PAYLOAD.document(), VALID_PAYLOAD.email());

            when(userRepository.existsByDocumentOrEmail(any(), any())).thenReturn(false);
            when(userRepository.save(any(User.class))).thenReturn(saved);

            userService.create(VALID_PAYLOAD);

            verify(userRepository).save(any(User.class));
        }

        @Test
        @DisplayName("Throws ConflictException when document or email is already taken")
        void duplicateDocumentOrEmail() {
            when(userRepository.existsByDocumentOrEmail(VALID_PAYLOAD.document(), VALID_PAYLOAD.email()))
                .thenReturn(true);

            assertThrows(ConflictException.class, () -> userService.create(VALID_PAYLOAD));
            verify(userRepository, never()).save(any());
        }

    }

    @Nested
    @DisplayName("exists()")
    class Exists {

        @Test
        @DisplayName("Returns true when the user is found")
        void returnsTrueWhenFound() {
            UUID userId = UUID.randomUUID();
            when(userRepository.existsById(userId)).thenReturn(true);

            assertTrue(userService.exists(userId));
        }

        @Test
        @DisplayName("Returns false when the user is not found")
        void returnsFalseWhenNotFound() {
            UUID userId = UUID.randomUUID();
            when(userRepository.existsById(userId)).thenReturn(false);

            assertFalse(userService.exists(userId));
        }
    }
}

