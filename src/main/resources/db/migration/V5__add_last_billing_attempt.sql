-- Adiciona a coluna que controla o "Jitter/Delay" -> evitar Stampede de falhas
ALTER TABLE subscriptions
    ADD COLUMN last_billing_attempt TIMESTAMP;

-- Atualiza o índice de controle do Scheduler para contemplar a nova regra de retentativa
DROP INDEX IF EXISTS idx_sub_renewal_sweep;

-- Indexamos apenas assinaturas ATIVAS e com RENOVAÇÃO LIGADA.
-- O banco filtra aquilo que não é relevante
-- e deixa a aplicação decidir quantas tentativas são permitidas.
CREATE INDEX idx_sub_renewal_sweep
    ON subscriptions (expiring_date, last_billing_attempt)
    WHERE auto_renew = true AND status = 'ACTIVE';