package com.platform.subscription_manager.shared.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

	@Bean
	public OpenAPI subscriptionManagerOpenAPI() {
		return new OpenAPI()
			.info(new Info()
				.title("Subscription Manager API")
				.description("""
					Sistema de gestão de assinaturas recorrentes para streaming.

					## Fluxo principal
					1. Crie um usuário: `POST /v1/users`
					2. Crie uma assinatura com um token de pagamento: `POST /v1/subscriptions`
					3. O scheduler renova automaticamente no vencimento — use `POST /v1/admin/billing/trigger-sweep` para acionar manualmente sem esperar 1 minuto

					## Tokens de teste (WireMock — perfil `local`)
					| Token | Comportamento |
					|---|---|
					| `tok_test_success` | Sempre aprovado ✅ |
					| `tok_test_always_fail` | Sempre recusado → suspensão após 3 tentativas do sweeper ❌ |

					## Autenticação simulada
					O header `X-User-Id` representa o UUID do usuário autenticado.
					Em produção este valor seria injetado pelo BFF / API Gateway antes de chegar aqui.

					## Estados de assinatura
					| Status | Acesso ao serviço | Renovação automática |
					|---|---|---|
					| `ACTIVE` | ✅ Sim | ✅ Sim (se autoRenew=true) |
					| `CANCELED` | ✅ Até expiringDate | ❌ Não |
					| `SUSPENDED` | ❌ Não | ❌ Não — aguarda reativação manual |
					| `INACTIVE` | ❌ Não | ❌ Não |
					""")
				.version("1.0.0")
				.contact(new Contact()
					.name("Subscription Manager")
					.email("dev@subscription-manager.local"))
				.license(new License().name("Private — Desafio Técnico")))
			.servers(List.of(
				new Server().url("http://localhost:8080").description("Local development")
			));
	}
}

