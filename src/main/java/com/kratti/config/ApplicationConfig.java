package com.kratti.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/** Включает фоновую обработку и настраивает доступ к API MVP без сессионной аутентификации. */
@Configuration
@EnableScheduling
public class ApplicationConfig {
    /** Отклоняет нестроковые имена и ключи вместо неявного преобразования JSON-чисел в строки. */
    @Bean
    public JsonMapperBuilderCustomizer strictJsonStrings() {
        return builder -> builder.withCoercionConfig(LogicalType.Textual, coercion -> coercion
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }

    /** Разрешает API и статический макет, отключая CSRF для API без cookie-аутентификации. */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
                .authorizeHttpRequests(access -> access.anyRequest().permitAll()).build();
    }
}
