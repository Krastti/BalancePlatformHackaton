package com.kratti.config;

import org.hibernate.cfg.MappingSettings;
import org.hibernate.type.format.jackson.Jackson3JsonFormatMapper;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Сохраняет десятичную точность признаков, правил и трасс при обмене JSONB с PostgreSQL. */
@Configuration
public class HibernateJsonConfig {
    /** Передаёт Hibernate настроенный JSON-преобразователь с чтением дробных чисел как BigDecimal. */
    @Bean
    public HibernatePropertiesCustomizer preciseJsonFormatMapper(JsonMapper jsonMapper) {
        JsonMapper preciseMapper = jsonMapper.rebuild()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .build();
        Jackson3JsonFormatMapper formatMapper = new Jackson3JsonFormatMapper(preciseMapper);
        return properties -> properties.put(MappingSettings.JSON_FORMAT_MAPPER, formatMapper);
    }
}
