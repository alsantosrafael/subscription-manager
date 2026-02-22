/**
 * Shared module — marked OPEN so all sub-packages (domain, infrastructure, config)
 * are treated as part of the module's public API by Spring Modulith.
 * This allows billing, subscription and user modules to freely use types in
 * shared.domain (Plan, SubscriptionStatus, BillingHistoryStatus, PaymentTokenPort)
 * without triggering "depends on non-exposed type" violations.
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
package com.platform.subscription_manager.shared;

import org.springframework.modulith.ApplicationModule;

