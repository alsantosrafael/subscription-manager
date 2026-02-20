package com.platform.subscription_manager.user.domain.repositories;

import com.platform.subscription_manager.user.domain.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

	boolean existsByDocumentOrEmail(String document, String email);
}