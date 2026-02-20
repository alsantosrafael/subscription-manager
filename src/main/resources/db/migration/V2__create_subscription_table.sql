CREATE TABLE subscriptions (
                               id UUID PRIMARY KEY,
                               user_id UUID NOT NULL,
                               plan VARCHAR(20) NOT NULL,
                               status VARCHAR(20) NOT NULL,
                               payment_token VARCHAR(255) NOT NULL,
                               start_date TIMESTAMP NOT NULL,
                               expiring_date TIMESTAMP NOT NULL,
                               auto_renew BOOLEAN NOT NULL,
                               billing_attempts INT DEFAULT 0 NOT NULL,
                               version BIGINT DEFAULT 0,
                               CONSTRAINT fk_subscription_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX idx_sub_renewal_sweep ON subscriptions (status, expiring_date);

CREATE INDEX idx_sub_api_read ON subscriptions (user_id) INCLUDE (status, plan, expiring_date);