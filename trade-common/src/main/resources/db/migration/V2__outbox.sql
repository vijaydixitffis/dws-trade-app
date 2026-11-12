-- Transactional outbox: an event is written in the same transaction as the change it
-- describes, and a relay publishes it to Kafka afterwards. A crash between the two can
-- neither lose the event nor announce a change that was rolled back.
CREATE TABLE outbox (
    id          BIGSERIAL    PRIMARY KEY,
    -- Same event id as before: repeating it (a reprocessed record) is ignored while the row is waiting.
    event_id    VARCHAR(160) NOT NULL UNIQUE,
    topic       VARCHAR(100) NOT NULL,
    message_key VARCHAR(64),
    payload     TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
