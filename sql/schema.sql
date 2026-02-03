-- Database schema for payment system sharding example
-- This schema should be applied to each shard database

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
    
    CONSTRAINT valid_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    INDEX idx_account_created (account_id, created_at DESC),
    INDEX idx_status_created (status, created_at)
);

-- Update trigger for accounts.updated_at
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_accounts_updated_at 
    BEFORE UPDATE ON accounts 
    FOR EACH ROW 
    EXECUTE FUNCTION update_updated_at_column();

-- Sample data for testing
INSERT INTO accounts (account_id, balance) VALUES 
    (1001, 1000.00),
    (1002, 2000.00),
    (1003, 1500.00),
    (10001, 50000.00), -- VIP account
    (10002, 75000.00)  -- VIP account
ON CONFLICT (account_id) DO NOTHING;