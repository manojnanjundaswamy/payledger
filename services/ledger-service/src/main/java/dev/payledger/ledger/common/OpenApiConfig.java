package dev.payledger.ledger.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Interactive API docs at /swagger-ui.html; the "Authorize" button takes the API key. */
@Configuration
class OpenApiConfig {

    @Bean
    OpenAPI payLedgerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("PayLedger API")
                        .version("v2")
                        .description("""
                                Generic double-entry engine behind PayLedger: books, typed accounts, balanced
                                entries, reversals and period totals. Internal: called by other services, not browsers.
                                Amounts are signed integer minor units (positive = debit; 1050 = 10.50).
                                Writes require an `Idempotency-Key` header; retries with the same key return the
                                original entry with `Idempotent-Replayed: true`."""))
                .components(new Components().addSecuritySchemes("apiKey", new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name(ApiKeyFilter.HEADER)))
                .addSecurityItem(new SecurityRequirement().addList("apiKey"));
    }
}
