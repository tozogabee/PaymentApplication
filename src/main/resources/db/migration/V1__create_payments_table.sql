CREATE TABLE payments (
    id               UUID                     PRIMARY KEY,
    amount           NUMERIC(19, 4)           NOT NULL,
    currency         VARCHAR(3)               NOT NULL,
    debtor_account   VARCHAR(255)             NOT NULL,
    creditor_account VARCHAR(255)             NOT NULL,
    status           VARCHAR(20)              NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_payments_status ON payments (status);