CREATE TABLE billing_history (
     id UUID PRIMARY KEY,
     subscription_id UUID NOT NULL,
     idempotency_key VARCHAR(255) NOT NULL,
     status VARCHAR(50) NOT NULL,
     gateway_transaction_id VARCHAR(255),
     processed_at TIMESTAMP NOT NULL,
     created_at TIMESTAMP NOT NULL DEFAULT NOW(),
     updated_at TIMESTAMP NOT NULL DEFAULT NOW(),

     CONSTRAINT uk_billing_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX idx_billing_idempotency ON billing_history (idempotency_key);

CREATE INDEX idx_billing_subscription ON billing_history (subscription_id);