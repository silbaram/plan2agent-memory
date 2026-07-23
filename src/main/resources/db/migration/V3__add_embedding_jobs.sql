CREATE TABLE embedding_jobs (
    embedding_job_id uuid PRIMARY KEY,
    chunk_id uuid NOT NULL REFERENCES document_chunks (chunk_id) ON DELETE CASCADE,
    embedding_set_id uuid NOT NULL REFERENCES embedding_sets (embedding_set_id) ON DELETE CASCADE,
    status text NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,
    lease_owner text,
    lease_generation bigint NOT NULL DEFAULT 0,
    lease_expires_at timestamptz,
    last_error_code text,
    last_error_message text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz,
    started_at timestamptz,
    last_attempt_at timestamptz,
    completed_at timestamptz,
    CONSTRAINT uq_embedding_jobs_chunk_embedding_set
        UNIQUE (chunk_id, embedding_set_id),
    CONSTRAINT ck_embedding_jobs_status
        CHECK (status IN ('pending', 'running', 'retrying', 'succeeded', 'permanently_failed')),
    CONSTRAINT ck_embedding_jobs_attempt_count_non_negative
        CHECK (attempt_count >= 0),
    CONSTRAINT ck_embedding_jobs_lease_generation_non_negative
        CHECK (lease_generation >= 0),
    CONSTRAINT ck_embedding_jobs_lease_owner_not_blank
        CHECK (lease_owner IS NULL OR btrim(lease_owner) <> ''),
    CONSTRAINT ck_embedding_jobs_error_code
        CHECK (
            last_error_code IS NULL
            OR last_error_code IN (
                'provider_unavailable',
                'provider_contract_invalid',
                'content_invalid',
                'max_attempts_exhausted',
                'lease_expired'
            )
        ),
    CONSTRAINT ck_embedding_jobs_error_message
        CHECK (
            last_error_message IS NULL
            OR (
                btrim(last_error_message) <> ''
                AND char_length(last_error_message) <= 512
                AND last_error_message !~ '[[:cntrl:]]'
            )
        ),
    CONSTRAINT ck_embedding_jobs_error_fields
        CHECK (
            (last_error_code IS NULL AND last_error_message IS NULL)
            OR (last_error_code IS NOT NULL AND last_error_message IS NOT NULL)
        ),
    CONSTRAINT ck_embedding_jobs_state_leases
        CHECK (
            (status = 'running' AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL AND completed_at IS NULL)
            OR (
                status IN ('pending', 'retrying', 'succeeded', 'permanently_failed')
                AND lease_owner IS NULL
                AND lease_expires_at IS NULL
            )
        ),
    CONSTRAINT ck_embedding_jobs_completed_states
        CHECK (
            (status IN ('pending', 'running', 'retrying') AND completed_at IS NULL)
            OR (status IN ('succeeded', 'permanently_failed') AND completed_at IS NOT NULL)
        ),
    CONSTRAINT ck_embedding_jobs_permanent_failure_error
        CHECK (status <> 'permanently_failed' OR last_error_code IS NOT NULL)
);

CREATE INDEX idx_embedding_jobs_claimable
    ON embedding_jobs (status, next_attempt_at, created_at, embedding_job_id);

CREATE INDEX idx_embedding_jobs_expired_leases
    ON embedding_jobs (status, lease_expires_at)
    WHERE status = 'running';
