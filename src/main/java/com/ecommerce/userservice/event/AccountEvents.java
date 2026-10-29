package com.ecommerce.userservice.event;

import com.ecommerce.userservice.config.events.EventsProperties;
import com.ecommerce.userservice.entity.User;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * The three account events. The body fields are the ones in docs/mvp/07-events.md.
 * Every body has userId, email and name, because that is what the listener needs to reach the person.
 * The event names come from application.yaml (events.*), not from this class.
 */
@Component
public class AccountEvents {

    private final EventPublisher publisher;
    private final EventsProperties events;

    public AccountEvents(EventPublisher publisher, EventsProperties events) {
        this.publisher = publisher;
        this.events = events;
    }

    /** A new account was created, by the form or by Google. */
    public void registered(User user) {
        Map<String, Object> body = base(user);
        body.put("provider", user.getAuthProvider().name());
        publisher.publish(events.registered(), body);
    }

    /** A reset link was requested. The link is a secret: never write this body to a log. */
    public void passwordReset(User user, String resetLink, long expiresInMinutes) {
        Map<String, Object> body = base(user);
        body.put("resetLink", resetLink);
        body.put("expiresInMinutes", expiresInMinutes);
        publisher.publish(events.passwordReset(), body);
    }

    public void passwordChanged(User user) {
        Map<String, Object> body = base(user);
        body.put("changedAt", Instant.now().toString());
        publisher.publish(events.passwordChanged(), body);
    }

    private Map<String, Object> base(User user) {
        Map<String, Object> body = new HashMap<>();
        body.put("userId", String.valueOf(user.getId()));
        body.put("email", user.getEmail());
        // A Google account can come without a name. The template needs some text.
        body.put("name", user.getName() == null ? "" : user.getName());
        return body;
    }
}
