package com.tradingbot.config;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class SwaggerGlobalHeaderConfig {

    @Bean
    public OpenApiCustomizer globalUserHeaderCustomizer() {
        return openApi -> {
            if (openApi == null || openApi.getPaths() == null) return;

            openApi.getPaths().forEach((path, pathItem) -> {
                if (pathItem == null) return;

                List<Operation> operations = new ArrayList<>();
                if (pathItem.getGet() != null) operations.add(pathItem.getGet());
                if (pathItem.getPost() != null) operations.add(pathItem.getPost());
                if (pathItem.getPut() != null) operations.add(pathItem.getPut());
                if (pathItem.getDelete() != null) operations.add(pathItem.getDelete());
                if (pathItem.getOptions() != null) operations.add(pathItem.getOptions());
                if (pathItem.getHead() != null) operations.add(pathItem.getHead());
                if (pathItem.getPatch() != null) operations.add(pathItem.getPatch());
                if (pathItem.getTrace() != null) operations.add(pathItem.getTrace());

                for (Operation operation : operations) {
                    if (operation == null) continue;

                    Parameter userHeader = new Parameter()
                            .in("header")
                            .name("X-User-Id")
                            .description("User context identifier for multi-user support")
                            .schema(new StringSchema())
                            .required(false);

                    List<Parameter> params = operation.getParameters();
                    if (params == null) {
                        params = new ArrayList<>();
                        operation.setParameters(params);
                    }
                    boolean exists = params.stream()
                            .anyMatch(p -> "X-User-Id".equals(p.getName()) && "header".equals(p.getIn()));
                    if (!exists) {
                        params.add(userHeader);
                    }
                }
            });
        };
    }
}
