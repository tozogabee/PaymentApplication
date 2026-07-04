CREATE TABLE payments (
    id               UUID                     PRIMARY KEY,
    amount           NUMERIC(19, 4)           NOT NULL,
    currency         VARCHAR(3)               NOT NULL,
    debtor_account   VARCHAR(255)             NOT NULL,
    creditor_account VARCHAR(255)             NOT NULL,
    status           VARCHAR(20)              NOT NULL,
    -- Optimistic-locking version: Hibernate checks and increments it on every update, so two
    -- concurrent updates to the same payment cannot silently overwrite each other (lost update).
    version          BIGINT                   NOT NULL DEFAULT 0,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_payments_status ON payments (status);