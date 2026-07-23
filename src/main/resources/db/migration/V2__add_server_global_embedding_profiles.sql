ALTER TABLE embedding_sets
    ADD COLUMN scope text NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN profile_fingerprint text,
    ADD COLUMN profile_manifest jsonb;

ALTER TABLE embedding_sets
    DROP CONSTRAINT uq_embedding_sets_model_dimension_version_metric;

ALTER TABLE embedding_sets
    ADD CONSTRAINT ck_embedding_sets_scope
        CHECK (scope IN ('LEGACY', 'SERVER_GLOBAL')),
    ADD CONSTRAINT ck_embedding_sets_profile_manifest_object
        CHECK (profile_manifest IS NULL OR jsonb_typeof(profile_manifest) = 'object'),
    ADD CONSTRAINT ck_embedding_sets_scope_profile
        CHECK (
            (scope = 'LEGACY' AND profile_fingerprint IS NULL AND profile_manifest IS NULL)
            OR
            (
                scope = 'SERVER_GLOBAL'
                AND project_id IS NULL
                AND profile_fingerprint IS NOT NULL
                AND btrim(profile_fingerprint) <> ''
                AND profile_manifest IS NOT NULL
            )
        );

CREATE UNIQUE INDEX uq_embedding_sets_legacy_model_dimension_version_metric
    ON embedding_sets (
        embedding_model,
        embedding_dimension,
        embedding_version,
        distance_metric
    )
    WHERE scope = 'LEGACY';

CREATE UNIQUE INDEX uq_embedding_sets_server_global_profile_fingerprint
    ON embedding_sets (profile_fingerprint)
    WHERE scope = 'SERVER_GLOBAL';

CREATE OR REPLACE FUNCTION prevent_server_global_embedding_set_identity_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.scope = 'SERVER_GLOBAL' AND (
        NEW.scope IS DISTINCT FROM OLD.scope
        OR NEW.project_id IS DISTINCT FROM OLD.project_id
        OR NEW.embedding_model IS DISTINCT FROM OLD.embedding_model
        OR NEW.embedding_dimension IS DISTINCT FROM OLD.embedding_dimension
        OR NEW.embedding_version IS DISTINCT FROM OLD.embedding_version
        OR NEW.distance_metric IS DISTINCT FROM OLD.distance_metric
        OR NEW.storage_type IS DISTINCT FROM OLD.storage_type
        OR NEW.profile_fingerprint IS DISTINCT FROM OLD.profile_fingerprint
        OR NEW.profile_manifest IS DISTINCT FROM OLD.profile_manifest
    ) THEN
        RAISE EXCEPTION 'server-global embedding set identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_embedding_sets_server_global_identity_immutable
    BEFORE UPDATE ON embedding_sets
    FOR EACH ROW
    EXECUTE FUNCTION prevent_server_global_embedding_set_identity_mutation();

CREATE TABLE embedding_active_profiles (
    scope text PRIMARY KEY,
    active_embedding_set_id uuid NOT NULL
        REFERENCES embedding_sets (embedding_set_id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz,
    CONSTRAINT ck_embedding_active_profiles_scope
        CHECK (scope = 'server_global')
);

CREATE OR REPLACE FUNCTION require_server_global_active_profile_target()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    target_scope text;
BEGIN
    SELECT scope
    INTO target_scope
    FROM embedding_sets
    WHERE embedding_set_id = NEW.active_embedding_set_id;

    IF target_scope IS DISTINCT FROM 'SERVER_GLOBAL' THEN
        RAISE EXCEPTION 'active embedding profile must reference a server-global embedding set';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_embedding_active_profiles_require_server_global_target
    BEFORE INSERT OR UPDATE OF active_embedding_set_id ON embedding_active_profiles
    FOR EACH ROW
    EXECUTE FUNCTION require_server_global_active_profile_target();

CREATE TABLE chunk_embedding_vectors_384 (
    chunk_embedding_id uuid PRIMARY KEY
        REFERENCES chunk_embeddings (chunk_embedding_id) ON DELETE CASCADE,
    embedding vector(384) NOT NULL
);

CREATE INDEX idx_chunk_embedding_vectors_384_hnsw_cosine
    ON chunk_embedding_vectors_384 USING hnsw (embedding vector_cosine_ops);

INSERT INTO chunk_embedding_vectors_384 (chunk_embedding_id, embedding)
SELECT chunk_embedding.chunk_embedding_id, chunk_embedding.embedding::vector(384)
FROM chunk_embeddings AS chunk_embedding
JOIN embedding_sets AS embedding_set
    ON embedding_set.embedding_set_id = chunk_embedding.embedding_set_id
WHERE embedding_set.scope = 'LEGACY'
  AND embedding_set.embedding_dimension = 384
  AND vector_dims(chunk_embedding.embedding) = 384
ON CONFLICT (chunk_embedding_id) DO NOTHING;
