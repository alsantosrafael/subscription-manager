package com.platform.subscription_manager.subscription.domain.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import java.math.BigDecimal;

@Getter
@RequiredArgsConstructor
public enum Plan {
	BASICO(new BigDecimal("19.90")),
	PREMIUM(new BigDecimal("39.90")),
	FAMILIA(new BigDecimal("59.90"));

	private final BigDecimal price;
}
