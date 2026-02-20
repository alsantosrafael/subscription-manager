package com.platform.subscription_manager.user;

import java.util.UUID;

public interface UserFacade {
	boolean exists(UUID userId);
}
