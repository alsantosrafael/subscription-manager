package com.platform.subscription_manager.user.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false, unique = true, length = 20)
	private String document;

	@Column(nullable = false, unique = true)
	private String email;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	public User(String name, String document, String email) {
		this.name = name;
		this.document = document;
		this.email = email;
		this.createdAt = LocalDateTime.now();
	}
}
