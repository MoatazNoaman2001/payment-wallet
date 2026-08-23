package com.moataz.paymentwallet.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI paymentWalletOpenApi() {
        return new OpenAPI()
                .components(new Components().addSecuritySchemes("bearer-jwt",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Call POST /api/auth/login, copy accessToken, paste it here.")))
                .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"))
                .info(new Info()
                .title("Payment Wallet API")
                .version("v1")
                .description("""
                        Digital wallet practice project: double-entry ledger, idempotent \
                        transfers, optimistic locking. Phase 1 (identity + accounts) is live; \
                        the transfer engine arrives in Phase 2."""));
    }
}
