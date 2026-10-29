package com.ecommerce.userservice.config.events;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * RabbitMQ names under "events:" in application.yaml.
 * The event names are settings, so another project can rename them without changing Java.
 */
@Validated
@ConfigurationProperties(prefix = "events")
public record EventsProperties(
        @NotBlank String exchange,
        @NotBlank String registered,
        @NotBlank String passwordReset,
        @NotBlank String passwordChanged) {
}
