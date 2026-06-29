CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE projects (
    project_id uuid PRIMARY KEY,
    source_project_id text,
    name text NOT NULL,
    root_path text NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz,
    CONSTRAINT ck_projects_source_project_id_not_blank
        CHECK (source_project_id IS NULL OR btrim(source_project_id) <> ''),
    CONSTRAINT ck_projects_name_not_blank
        CHECK (btrim(name) <> ''),
    CONSTRAINT ck_projects_root_path_not_blank
        CHECK (btrim(root_path) <> ''),
    CONSTRAINT ck_projects_metadata_object
        CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE UNIQUE INDEX uq_projects_source_project_id
    ON projects (source_project_id)
    WHERE source_project_id IS NOT NULL;

CREATE TABLE iterations (
    iteration_id uuid PRIMARY KEY,
    source_iteration_id text,
    project_id uuid NOT NULL REFERENCES projects (project_id) ON DELETE CASCADE,
    label text NOT NULL,
    status text NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz,
    CONSTRAINT ck_iterations_source_iteration_id_not_blank
        CHECK (source_iteration_id IS NULL OR btrim(source_iteration_id) <> ''),
    CONSTRAINT ck_iterations_label_not_blank
        CHECK (btrim(label) <> ''),
    CONSTRAINT ck_iterations_status_not_blank
        CHECK (btrim(status) <> ''),
    CONSTRAINT ck_iterations_metadata_object
        CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE UNIQUE INDEX uq_iterations_project_source_iteration_id
    ON iterations (project_id, source_iteration_id)
    WHERE source_iteration_id IS NOT NULL;
CREATE INDEX idx_iterations_project_id
    ON iterations (project_id);
CREATE INDEX idx_iterations_project_status
    ON iterations (project_id, status);

CREATE TABLE documents (
    document_id uuid PRIMARY KEY,
    source_document_id text,
    project_id uuid NOT NULL REFERENCES projects (project_id) ON DELETE CASCADE,
    iteration_id uuid REFERENCES iterations (iteration_id) ON DELETE SET NULL,
    artifact_type text NOT NULL,
    source_path text NOT NULL,
    raw_source_path text,
    content_hash text NOT NULL,
    snapshot_version integer NOT NULL,
    content text NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz,
    CONSTRAINT ck_documents_source_document_id_not_blank
        CHECK (source_document_id IS NULL OR btrim(source_document_id) <> ''),
    CONSTRAINT ck_documents_artifact_type_not_blank
        CHECK (btrim(artifact_type) <> ''),
    CONSTRAINT ck_documents_source_path_not_blank
        CHECK (btrim(source_path) <> ''),
    CONSTRAINT ck_documents_raw_source_path_not_blank
        CHECK (raw_source_path IS NULL OR btrim(raw_source_path) <> ''),
    CONSTRAINT ck_documents_content_hash_not_blank
        CHECK (btrim(content_hash) <> ''),
    CONSTRAINT ck_documents_snapshot_version_positive
        CHECK (snapshot_version > 0),
    CONSTRAINT ck_documents_metadata_object
        CHECK (jsonb_typeof(metadata) = 'object'),
    CONSTRAINT uq_documents_logical_snapshot_hash
        UNIQUE NULLS NOT DISTINCT (
            project_id,
            iteration_id,
            artifact_type,
            source_path,
            content_hash
        ),
    CONSTRAINT uq_documents_logical_snapshot_version
        UNIQUE NULLS NOT DISTINCT (
            project_id,
            iteration_id,
            artifact_type,
            source_path,
            snapshot_version
        )
);

CREATE UNIQUE INDEX uq_documents_project_source_document_id
    ON documents (project_id, source_document_id)
    WHERE source_document_id IS NOT NULL;
CREATE INDEX idx_documents_artifact_filters
    ON documents (project_id, iteration_id, artifact_type, source_path);
CREATE INDEX idx_documents_content_hash
    ON documents (content_hash);
CREATE INDEX idx_documents_latest_snapshot
    ON documents (project_id, iteration_id, artifact_type, source_path, snapshot_version DESC);
CREATE INDEX idx_documents_source_path_lower
    ON documents (lower(source_path));
CREATE INDEX idx_documents_content_fts
    ON documents USING gin (to_tsvector('simple', content));

CREATE TABLE task_graphs (
    task_graph_id uuid PRIMARY KEY,
    source_task_graph_id text,
    project_id uuid NOT NULL REFERENCES projects (project_id) ON DELETE CASCADE,
    iteration_id uuid NOT NULL REFERENCES iterations (iteration_id) ON DELETE CASCADE,
    document_id uuid REFERENCES documents (document_id) ON DELETE SET NULL,
    source_document_id text,
    graph_hash text NOT NULL,
    graph_json jsonb NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz,
    CONSTRAINT ck_task_graphs_source_task_graph_id_not_blank
        CHECK (source_task_graph_id IS NULL OR btrim(source_task_graph_id) <> ''),
    CONSTRAINT ck_task_graphs_source_document_id_not_blank
        CHECK (source_document_id IS NULL OR btrim(source_document_id) <> ''),
    CONSTRAINT ck_task_graphs_graph_hash_not_blank
        CHECK (btrim(graph_hash) <> ''),
    CONSTRAINT ck_task_graphs_graph_json_object
        CHECK (jsonb_typeof(graph_json) = 'object'),
    CONSTRAINT ck_task_graphs_metadata_object
        CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE UNIQUE INDEX uq_task_graphs_project_iteration_source_task_graph_id
    ON task_graphs (project_id, iteration_id, source_task_graph_id)
    WHERE source_task_graph_id IS NOT NULL;
CREATE UNIQUE INDEX uq_task_graphs_project_iteration_graph_hash
    ON task_graphs (project_id, iteration_id, graph_hash);
CREATE INDEX idx_task_graphs_project_iteration
    ON task_graphs (project_id, iteration_id);

CREATE TABLE tasks (
    task_id uuid PRIMARY KEY,
    source_task_id text,
    project_id uuid NOT NULL REFERENCES projects (project_id) ON DELETE CASCADE,
    iteration_id uuid NOT NULL REFERENCES iterations (iteration_id) ON DELETE CASCADE,
    task_graph_id uuid NOT NULL REFERENCES task_graphs (task_graph_id) ON DELETE CASCADE,
    title text NOT NULL,
    description text,
    status text NOT NULL,
    target_area text,
    dependencies_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    acceptance_criteria_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz,
    CONSTRAINT ck_tasks_source_task_id_not_blank
        CHECK (source_task_id IS NULL OR btrim(source_task_id) <> ''),
    CONSTRAINT ck_tasks_title_not_blank
        CHECK (btrim(title) <> ''),
    CONSTRAINT ck_tasks_status_not_blank
        CHECK (btrim(status) <> ''),
    CONSTRAINT ck_tasks_target_area_not_blank
        CHECK (target_area IS NULL OR btrim(target_area) <> ''),
    CONSTRAINT ck_tasks_dependencies_array
        CHECK (jsonb_typeof(dependencies_json) = 'array'),
    CONSTRAINT ck_tasks_acceptance_criteria_array
        CHECK (jsonb_typeof(acceptance_criteria_json) = 'array'),
    CONSTRAINT ck_tasks_metadata_object
        CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE UNIQUE INDEX uq_tasks_graph_source_task_id
    ON tasks (task_graph_id, source_task_id)
    WHERE source_task_id IS NOT NULL;
CREATE UNIQUE INDEX uq_tasks_project_iteration_source_task_id
    ON tasks (project_id, iteration_id, source_task_id)
    WHERE source_task_id IS NOT NULL;
CREATE INDEX idx_tasks_graph_id
    ON tasks (task_graph_id);
CREATE INDEX idx_tasks_artifact_filters
    ON tasks (project_id, iteration_id, status, target_area);

CREATE TABLE runs (
    run_id uuid PRIMARY KEY,
    source_run_id text,
    project_id uuid NOT NULL REFERENCES projects (project_id) ON DELETE CASCADE,
    iteration_id uuid NOT NULL REFERENCES iterations (iteration_id) ON DELETE CASCADE,
    task_id uuid NOT NULL REFERENCES tasks (task_id) ON DELETE CASCADE,
    status text NOT NULL,
    agent_tool text NOT NULL,
    started_at timestamptz NOT NULL,
    finished_at timestamptz,
    run_json jsonb NOT NULL,
    artifact_refs_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz,
    CONSTRAINT ck_runs_source_run_id_not_blank
        CHECK (source_run_id IS NULL OR btrim(source_run_id) <> ''),
    CONSTRAINT ck_runs_status_not_blank
        CHECK (btrim(status) <> ''),
    CONSTRAINT ck_runs_agent_tool_not_blank
        CHECK (btrim(agent_tool) <> ''),
    CONSTRAINT ck_runs_finished_after_started
        CHECK (finished_at IS NULL OR finished_at >= started_at),
    CONSTRAINT ck_runs_run_json_object
        CHECK (jsonb_typeof(run_json) = 'object'),
    CONSTRAINT ck_runs_artifact_refs_array
        CHECK (jsonb_typeof(artifact_refs_json) = 'array'),
    CONSTRAINT ck_runs_metadata_object
        CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE UNIQUE INDEX uq_runs_project_iteration_source_run_id
    ON runs (project_id, iteration_id, source_run_id)
    WHERE source_run_id IS NOT NULL;
CREATE INDEX idx_runs_task_id
    ON runs (task_id);
CREATE INDEX idx_runs_artifact_filters
    ON runs (project_id, iteration_id, task_id, status);

CREATE TABLE document_chunks (
    chunk_id uuid PRIMARY KEY,
    source_chunk_id text,
    document_id uuid NOT NULL REFERENCES documents (document_id) ON DELETE CASCADE,
    project_id uuid NOT NULL REFERENCES projects (project_id) ON DELETE CASCADE,
    iteration_id uuid REFERENCES iterations (iteration_id) ON DELETE SET NULL,
    task_id uuid REFERENCES tasks (task_id) ON DELETE SET NULL,
    run_id uuid REFERENCES runs (run_id) ON DELETE SET NULL,
    artifact_type text NOT NULL,
    source_path text NOT NULL,
    raw_source_path text,
    chunk_index integer NOT NULL,
    chunk_hash text NOT NULL,
    content text NOT NULL,
    token_estimate integer,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz,
    CONSTRAINT ck_document_chunks_source_chunk_id_not_blank
        CHECK (source_chunk_id IS NULL OR btrim(source_chunk_id) <> ''),
    CONSTRAINT ck_document_chunks_artifact_type_not_blank
        CHECK (btrim(artifact_type) <> ''),
    CONSTRAINT ck_document_chunks_source_path_not_blank
        CHECK (btrim(source_path) <> ''),
    CONSTRAINT ck_document_chunks_raw_source_path_not_blank
        CHECK (raw_source_path IS NULL OR btrim(raw_source_path) <> ''),
    CONSTRAINT ck_document_chunks_chunk_index_non_negative
        CHECK (chunk_index >= 0),
    CONSTRAINT ck_document_chunks_chunk_hash_not_blank
        CHECK (btrim(chunk_hash) <> ''),
    CONSTRAINT ck_document_chunks_token_estimate_non_negative
        CHECK (token_estimate IS NULL OR token_estimate >= 0),
    CONSTRAINT ck_document_chunks_metadata_object
        CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE UNIQUE INDEX uq_document_chunks_document_source_chunk_id
    ON document_chunks (document_id, source_chunk_id)
    WHERE source_chunk_id IS NOT NULL;
CREATE UNIQUE INDEX uq_document_chunks_document_chunk_hash
    ON document_chunks (document_id, chunk_hash);
CREATE UNIQUE INDEX uq_document_chunks_document_chunk_index
    ON document_chunks (document_id, chunk_index);
CREATE INDEX idx_document_chunks_artifact_filters
    ON document_chunks (
        project_id,
        iteration_id,
        artifact_type,
        source_path,
        task_id,
        run_id
    );
CREATE INDEX idx_document_chunks_document_id
    ON document_chunks (document_id);
CREATE INDEX idx_document_chunks_source_path_lower
    ON document_chunks (lower(source_path));
CREATE INDEX idx_document_chunks_content_fts
    ON document_chunks USING gin (to_tsvector('simple', content));

CREATE TABLE embedding_sets (
    embedding_set_id uuid PRIMARY KEY,
    project_id uuid REFERENCES projects (project_id) ON DELETE CASCADE,
    embedding_model text NOT NULL,
    embedding_dimension integer NOT NULL,
    embedding_version text NOT NULL,
    distance_metric text NOT NULL DEFAULT 'cosine',
    storage_type text NOT NULL DEFAULT 'vector',
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz,
    CONSTRAINT ck_embedding_sets_embedding_model_not_blank
        CHECK (btrim(embedding_model) <> ''),
    CONSTRAINT ck_embedding_sets_embedding_dimension_range
        CHECK (embedding_dimension BETWEEN 1 AND 2000),
    CONSTRAINT ck_embedding_sets_embedding_version_not_blank
        CHECK (btrim(embedding_version) <> ''),
    CONSTRAINT ck_embedding_sets_distance_metric
        CHECK (distance_metric IN ('cosine', 'inner_product', 'l2')),
    CONSTRAINT ck_embedding_sets_storage_type
        CHECK (storage_type IN ('vector', 'halfvec', 'external')),
    CONSTRAINT ck_embedding_sets_metadata_object
        CHECK (jsonb_typeof(metadata) = 'object'),
    CONSTRAINT uq_embedding_sets_model_dimension_version_metric
        UNIQUE (
            embedding_model,
            embedding_dimension,
            embedding_version,
            distance_metric
        )
);

CREATE INDEX idx_embedding_sets_project_id
    ON embedding_sets (project_id);

CREATE TABLE chunk_embeddings (
    chunk_embedding_id uuid PRIMARY KEY,
    chunk_id uuid NOT NULL REFERENCES document_chunks (chunk_id) ON DELETE CASCADE,
    embedding_set_id uuid NOT NULL REFERENCES embedding_sets (embedding_set_id) ON DELETE CASCADE,
    embedding vector NOT NULL,
    embedding_hash text,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz,
    CONSTRAINT ck_chunk_embeddings_embedding_hash_not_blank
        CHECK (embedding_hash IS NULL OR btrim(embedding_hash) <> ''),
    CONSTRAINT ck_chunk_embeddings_metadata_object
        CHECK (jsonb_typeof(metadata) = 'object'),
    CONSTRAINT uq_chunk_embeddings_chunk_embedding_set
        UNIQUE (chunk_id, embedding_set_id)
);

CREATE INDEX idx_chunk_embeddings_embedding_set_id
    ON chunk_embeddings (embedding_set_id);
CREATE INDEX idx_chunk_embeddings_chunk_id
    ON chunk_embeddings (chunk_id);
