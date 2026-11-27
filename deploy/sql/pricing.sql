CREATE TABLE IF NOT EXISTS pricing_quote (
 id UUID PRIMARY KEY,
 passenger_id UUID NOT NULL,
 idempotency_key VARCHAR(255) NOT NULL UNIQUE,
 payload JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Shared persistent cache and global provider rate limits for all app replicas.
CREATE TABLE IF NOT EXISTS map_response_cache (
 cache_key CHAR(64) PRIMARY KEY,
 payload JSONB NOT NULL,
 expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS map_response_cache_expiry ON map_response_cache(expires_at);
CREATE TABLE IF NOT EXISTS map_provider_gate (
 provider VARCHAR(32) PRIMARY KEY,
 next_allowed_at TIMESTAMPTZ NOT NULL
);
