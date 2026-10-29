package com.ecommerce.userservice.config.events;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ setup. user-service only publishes. It declares the exchange (same name, type topic, durable,
 * like every service). The listener declares its own queue and bindings (docs/mvp/07-events.md).
 */
@Configuration
public class RabbitConfig {

    /** Event bodies are JSON. */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean
    public TopicExchange appEvents(EventsProperties events) {
        return new TopicExchange(events.exchange(), true, false);
    }
}
