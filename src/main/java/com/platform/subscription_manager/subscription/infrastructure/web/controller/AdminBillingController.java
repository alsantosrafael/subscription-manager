package com.platform.subscription_manager.subscription.infrastructure.web.controller;

import com.platform.subscription_manager.subscription.application.services.RenewalOrchestratorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/billing")
public class AdminBillingController {

	private static final Logger log = LoggerFactory.getLogger(AdminBillingController.class);
	private final RenewalOrchestratorService orchestrator;

	public AdminBillingController(RenewalOrchestratorService orchestrator) {
		this.orchestrator = orchestrator;
	}

	@PostMapping("/trigger-sweep")
	public ResponseEntity<Void> triggerSweep() {
		log.info("⚙️ Gatilho manual de faturamento acionado via API Administrativa.");
		orchestrator.executeDailySweep();
		return ResponseEntity.accepted().build();
	}
}

