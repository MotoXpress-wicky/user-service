package com.ecommerce.userservice.event;

import com.ecommerce.userservice.config.events.EventsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EventPublisherTest {

    private static final EventsProperties EVENTS = new EventsProperties(
            "app.events", "user.registered", "user.password.reset", "user.password.changed");

    @Mock
    private ApplicationEventPublisher spring;
    @Mock
    private RabbitTemplate rabbit;

    private EventPublisher publisher() {
        return new EventPublisher(spring, rabbit, EVENTS);
    }

    @Test
    @DisplayName("publish() only hands the event to Spring. The send waits for the commit")
    void publishDoesNotSendYet() {
        publisher().publish("user.registered", Map.of("email", "nimal@example.com"));

        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(spring).publishEvent(sent.capture());
        assertThat(sent.getValue()).isInstanceOf(EventPublisher.PendingEvent.class);
    }

    @Test
    @DisplayName("after the commit: sent to the exchange with the event name and a messageId header")
    void sendsToTheExchangeWithMessageId() {
        EventPublisher publisher = publisher();
        EventPublisher.PendingEvent event = new EventPublisher.PendingEvent(
                "user.registered", java.util.UUID.randomUUID(), "req-1", Map.of("email", "nimal@example.com"));

        publisher.send(event);

        ArgumentCaptor<MessagePostProcessor> post = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbit).convertAndSend(eq("app.events"), eq("user.registered"), eq(event.body()), post.capture());
        Message message = post.getValue().postProcessMessage(new Message(new byte[0], new MessageProperties()));
        assertThat(message.getMessageProperties().<String>getHeader("messageId")).isEqualTo(event.messageId().toString());
        assertThat(message.getMessageProperties().<String>getHeader("traceId")).isEqualTo("req-1");
    }

    @Test
    @DisplayName("no traceId header when there is no request id")
    void noTraceIdHeaderWhenMissing() {
        EventPublisher.PendingEvent event = new EventPublisher.PendingEvent(
                "user.registered", java.util.UUID.randomUUID(), null, Map.of());

        publisher().send(event);

        ArgumentCaptor<MessagePostProcessor> post = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbit).convertAndSend(eq("app.events"), eq("user.registered"), any(Map.class), post.capture());
        Message message = post.getValue().postProcessMessage(new Message(new byte[0], new MessageProperties()));
        assertThat(message.getMessageProperties().getHeaders()).doesNotContainKey("traceId");
    }

    @Test
    @DisplayName("RabbitMQ down: the error is logged and the user request is not broken")
    void brokerDownDoesNotThrow() {
        doThrow(new AmqpConnectException(new RuntimeException("down")))
                .when(rabbit).convertAndSend(any(String.class), any(String.class), any(Object.class), any(MessagePostProcessor.class));
        EventPublisher.PendingEvent event = new EventPublisher.PendingEvent(
                "user.registered", java.util.UUID.randomUUID(), null, Map.of());

        assertThatCode(() -> publisher().send(event)).doesNotThrowAnyException();
    }
}
