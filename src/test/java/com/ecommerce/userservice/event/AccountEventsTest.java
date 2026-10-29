package com.ecommerce.userservice.event;

import com.ecommerce.userservice.config.events.EventsProperties;
import com.ecommerce.userservice.entity.AuthProvider;
import com.ecommerce.userservice.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/** The body fields must be the ones in docs/mvp/07-events.md, because notification-service reads them by name. */
@ExtendWith(MockitoExtension.class)
class AccountEventsTest {

    private static final EventsProperties EVENTS = new EventsProperties(
            "app.events", "user.registered", "user.password.reset", "user.password.changed");

    @Mock
    private EventPublisher publisher;

    @Captor
    private ArgumentCaptor<Map<String, Object>> bodyCaptor;

    private AccountEvents events() {
        return new AccountEvents(publisher, EVENTS);
    }

    private User user(Long id, String name, AuthProvider provider) {
        return User.builder().id(id).name(name).email("nimal@example.com").authProvider(provider).build();
    }

    @Test
    @DisplayName("registered: userId, email, name and provider")
    void registeredBody() {
        events().registered(user(42L, "Nimal Perera", AuthProvider.LOCAL));

        verify(publisher).publish(eq("user.registered"), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).containsOnly(
                Map.entry("userId", "42"),
                Map.entry("email", "nimal@example.com"),
                Map.entry("name", "Nimal Perera"),
                Map.entry("provider", "LOCAL"));
    }

    @Test
    @DisplayName("registered: a Google account without a name gets an empty name, not null")
    void registeredWithoutName() {
        events().registered(user(7L, null, AuthProvider.GOOGLE));

        verify(publisher).publish(eq("user.registered"), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).containsEntry("name", "").containsEntry("provider", "GOOGLE");
    }

    @Test
    @DisplayName("password reset: resetLink and expiresInMinutes are added")
    void passwordResetBody() {
        events().passwordReset(user(42L, "Nimal Perera", AuthProvider.LOCAL), "http://localhost/reset-password?token=abc", 30);

        verify(publisher).publish(eq("user.password.reset"), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue())
                .containsEntry("userId", "42")
                .containsEntry("resetLink", "http://localhost/reset-password?token=abc")
                .containsEntry("expiresInMinutes", 30L);
    }

    @Test
    @DisplayName("password changed: changedAt is a UTC time")
    void passwordChangedBody() {
        events().passwordChanged(user(42L, "Nimal Perera", AuthProvider.LOCAL));

        verify(publisher).publish(eq("user.password.changed"), bodyCaptor.capture());
        Object changedAt = bodyCaptor.getValue().get("changedAt");
        assertThat(Instant.parse((String) changedAt)).isBeforeOrEqualTo(Instant.now());
        assertThat(bodyCaptor.getValue()).containsKeys("userId", "email", "name");
    }
}
