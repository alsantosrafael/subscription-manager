package com.platform.subscription_manager.subscription.infrastructure.web;

import com.platform.subscription_manager.subscription.application.dtos.CreateSubscriptionDTO;
import com.platform.subscription_manager.subscription.application.dtos.SubscriptionResponseDTO;
import com.platform.subscription_manager.subscription.application.services.SubscriptionService;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/subscriptions")
@RequiredArgsConstructor
@Tag(name = "Subscriptions", description = "Ciclo de vida de assinaturas — criação, consulta e cancelamento.")
public class SubscriptionController {

	private final SubscriptionService subscriptionService;

	@Operation(
		summary = "Criar / reativar assinatura",
		description = """
			Cria uma nova assinatura e realiza a cobrança inicial imediatamente via gateway de pagamento.

			**Comportamento de idempotência:**
			Se o usuário já possuir uma assinatura em estado não-ACTIVE (`CANCELED`, `SUSPENDED`, `INACTIVE`),
			ela é **reativada** com o novo plano e token informados — nunca cria um segundo registro.
			Retorna `409` se já existe uma assinatura `ACTIVE` para o usuário.

			**Tokens de teste disponíveis (WireMock, perfil `local`):**
			- `tok_test_success` — sempre aprovado
			- `tok_test_always_fail` — sempre recusado → sub suspensa após 3 sweeps
			- `tok_test_fail_first_attempt` — falha na 1ª tentativa, aprovado na 2ª (state machine)
			""",
		responses = {
			@ApiResponse(responseCode = "201", description = "Assinatura criada/reativada e cobrança aprovada",
				content = @Content(schema = @Schema(implementation = SubscriptionResponseDTO.class))),
			@ApiResponse(responseCode = "400", description = "Payload inválido", content = @Content),
			@ApiResponse(responseCode = "404", description = "Usuário não encontrado", content = @Content),
			@ApiResponse(responseCode = "409", description = "Usuário já possui assinatura ACTIVE", content = @Content),
			@ApiResponse(responseCode = "422", description = "Cobrança recusada pelo gateway de pagamento", content = @Content)
		}
	)
	@PostMapping
	public ResponseEntity<SubscriptionResponseDTO> create(@Valid @RequestBody CreateSubscriptionDTO subscriptionPayload) {
		return ResponseEntity.status(201).body(subscriptionService.create(subscriptionPayload));
	}

	@Operation(
		summary = "Consultar assinatura",
		description = """
			Retorna os dados da assinatura do usuário.
			Lê do **Redis** (cache) quando disponível — a leitura do banco é um fallback.
			O header `X-User-Id` é validado contra o `userId` da assinatura para garantir que o usuário
			só acessa seus próprios dados.
			""",
		responses = {
			@ApiResponse(responseCode = "200", description = "Assinatura encontrada",
				content = @Content(schema = @Schema(implementation = SubscriptionResponseDTO.class))),
			@ApiResponse(responseCode = "404", description = "Assinatura não encontrada ou não pertence ao usuário", content = @Content)
		}
	)
	@GetMapping("/{subscriptionId}")
	public ResponseEntity<SubscriptionResponseDTO> get(
		@Parameter(description = "UUID da assinatura", required = true) @PathVariable UUID subscriptionId,
		@Parameter(description = "UUID do usuário autenticado — injetado pelo BFF/Gateway em produção", required = true)
		@RequestHeader("X-User-Id") UUID userId) {
		return subscriptionService.get(subscriptionId, userId)
			.map(ResponseEntity::ok)
			.orElse(ResponseEntity.notFound().build());
	}

	@Operation(
		summary = "Cancelar assinatura",
		description = """
			Cancela a renovação automática. Define `autoRenew=false` e `status=CANCELED`.

			O usuário **mantém acesso** ao serviço até a `expiringDate` atual — não há cobrança adicional.
			O scheduler move a assinatura para `INACTIVE` automaticamente quando o período vencer.

			Apenas assinaturas com `status=ACTIVE` podem ser canceladas.
			""",
		responses = {
			@ApiResponse(responseCode = "204", description = "Cancelamento registrado — sem corpo de resposta"),
			@ApiResponse(responseCode = "404", description = "Assinatura não encontrada ou não pertence ao usuário", content = @Content),
			@ApiResponse(responseCode = "422", description = "Assinatura não está em estado ACTIVE", content = @Content)
		}
	)
	@PatchMapping("/{subscriptionId}/cancel")
	public ResponseEntity<Void> cancel(
		@Parameter(description = "UUID da assinatura", required = true) @PathVariable("subscriptionId") UUID subscriptionId,
		@Parameter(description = "UUID do usuário autenticado — injetado pelo BFF/Gateway em produção", required = true)
		@RequestHeader("X-User-Id") UUID userId) {
		subscriptionService.cancel(subscriptionId, userId);
		return ResponseEntity.noContent().build();
	}
}
