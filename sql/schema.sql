-- Database schema for payment system sharding example
-- Apply this script to EACH shard database independently

-- Accounts table
CREATE TABLE IF NOT EXISTS accounts (
    account_id  BIGINT          PRIMARY KEY,
    balance     DECIMAL(19,2)   NOT NULL DEFAULT 0.00,
    created_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT positive_balance CHECK (balance >= 0)
);

-- PostgreSQL does not support inline INDEX inside CREATE TABLE (that is MySQL syntax).
-- Indexes must be created as separate statements.
CREATE INDEX IF NOT EXISTS idx_accounts_created ON accounts (created_at DESC);

-- Transactions table
-- transaction_id uses UUID so IDs are globally unique across shards.
-- BIGSERIAL would generate colliding IDs independently on each shard.
CREATE TABLE IF NOT EXISTS transactions (
    transaction_id  UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      BIGINT          NOT NULL,
    amount          DECIMAL(19,2)   NOT NULL,
    description     VARCHAR(255),
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',

    CONSTRAINT valid_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_transactions_account_created ON transactions (account_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_transactions_status_created  ON transactions (status, created_at);

-- Auto-update trigger for accounts.updated_at
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Drop and recreate trigger to avoid duplicate errors on re-run
DROP TRIGGER IF EXISTS update_accounts_updated_at ON accounts;
CREATE TRIGGER update_accounts_updated_at
    BEFORE UPDATE ON accounts
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- Sample data for testing (only inserted if not already present)
INSERT INTO accounts (account_id, balance) VALUES
    (1001, 1000.00),
    (1002, 2000.00),
    (1003, 1500.00),
    (10001, 50000.00),
    (10002, 75000.00)
ON CONFLICT (account_id) DO NOTHING;
