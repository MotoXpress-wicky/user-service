-- Events that must be sent to RabbitMQ. A row is written in the SAME transaction as the change it announces,
-- so the change and its event are saved together or not at all. A sender then publishes the rows, oldest first,
-- and sets sent_at. If RabbitMQ is down, the rows wait and are sent later (docs/mvp/decisions.md, M19 and M46).
-- The body is wiped when the event is sent (it can hold a raw password reset link).
-- Delivery is "at least once": a message can be sent twice. Every listener skips message ids it already handled.

CREATE TABLE outbox_events (
    -- also the "messageId" header of the message, so a retry sends the same id
    id         UUID PRIMARY KEY,
    event_name VARCHAR(100) NOT NULL,
    -- the event body as JSON text
    body       VARCHAR(100000) NOT NULL,
    trace_id   VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    -- empty while the event waits
    sent_at    TIMESTAMP WITH TIME ZONE,
    attempts   INT NOT NULL DEFAULT 0,
    last_error VARCHAR(500)
);

-- finds the waiting events (and the old sent ones to delete) quickly
CREATE INDEX idx_outbox_events_sent_created ON outbox_events (sent_at, created_at);
