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
                        .version("v1")
                        .description("""
                                Double-entry wallet ledger. Amounts are integer minor units (1050 = 10.50).
                                Money-moving endpoints require an `Idempotency-Key` header; retries with the
                                same key return the original result with `Idempotent-Replayed: true`."""))
                .components(new Components().addSecuritySchemes("apiKey", new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name(ApiKeyFilter.HEADER)))
                .addSecurityItem(new SecurityRequirement().addList("apiKey"));
    }
}
