package com.yoox.great.context.swagger;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.GroupedOpenApi;
import org.springdoc.core.SpringDocConfigProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
@OpenAPIDefinition(security = {@SecurityRequirement(name = "default")})
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info().title("YOOX Cloud GCS API")
                        .description("YOOX Cloud GCS HTTP interfaces.")
                        .license(new License().name("Internal distribution"))
                        .version("1.0.0")).components(components());
    }

    @Bean
    public SecurityScheme securityScheme() {
        return new SecurityScheme().type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("x-auth-token");
    }

    @Bean
    public Components components() {
        return new Components()
                .addSecuritySchemes("default", securityScheme());
    }

    @Bean
    public GroupedOpenApi sdkOpenApi() {
        return GroupedOpenApi.builder().group("Cloud Api")
                .packagesToScan("com.yoox").build();
    }

    @Bean
    public SpringDocConfigProperties springDocConfigProperties(SpringDocConfigProperties properties) {
        properties.setDefaultFlatParamObject(false);
        properties.setDefaultSupportFormData(true);
        properties.setDefaultProducesMediaType("application/json");
        return properties;
    }
}
