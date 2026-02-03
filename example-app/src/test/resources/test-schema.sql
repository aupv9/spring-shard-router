-- Test schema for sharding integration tests
-- This will be applied to each test database container

-- Accounts table
CREATE TABLE IF NOT EXISTS accounts (
    account_id BIGINT PRIMARY KEY,
    balance DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT positive_balance CHECK (balance >= 0)
);

-- Transactions table  
CREATE TABLE IF NOT EXISTS transactions (
    transaction_id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL,
    amount DECIMAL(19,2) NOT NULL,
    description VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    
    CONSTRAINT valid_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED'))
);

-- Indexes for better performance
CREATE INDEX IF NOT EXISTS idx_transactions_account_created ON transactions(account_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_transactions_status_created ON transactions(status, created_at);

-- Update trigger for accounts.updated_at
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

DROP TRIGGER IF EXISTS update_accounts_updated_at ON accounts;
CREATE TRIGGER update_accounts_updated_at 
    BEFORE UPDATE ON accounts 
    FOR EACH ROW 
    EXECUTE FUNCTION update_updated_at_column();

-- Sample test data
INSERT INTO accounts (account_id, balance) VALUES 
    (1001, 1000.00),
    (1002, 2000.00),
    (1003, 1500.00),
    (10001, 50000.00) -- VIP account
ON CONFLICT (account_id) DO NOTHING;