CREATE TABLE artifact_nodes (
    node_id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects (project_id) ON DELETE CASCADE,
    iteration_id uuid REFERENCES iterations (iteration_id) ON DELETE CASCADE,
    node_kind text NOT NULL,
    natural_key text NOT NULL,
    label text NOT NULL,
    content text,
    document_id uuid REFERENCES documents (document_id) ON DELETE CASCADE,
    task_id uuid REFERENCES tasks (task_id) ON DELETE CASCADE,
    run_id uuid REFERENCES runs (run_id) ON DELETE CASCADE,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz,
    CONSTRAINT ck_artifact_nodes_kind CHECK (node_kind IN ('decision','assumption','clarifying_question','evidence','spec_section','document','task','run','proposal')),
    CONSTRAINT ck_artifact_nodes_natural_key_not_blank CHECK (btrim(natural_key) <> ''),
    CONSTRAINT ck_artifact_nodes_label_not_blank CHECK (btrim(label) <> ''),
    CONSTRAINT ck_artifact_nodes_metadata_object CHECK (jsonb_typeof(metadata) = 'object'),
    CONSTRAINT uq_artifact_nodes_scope_natural_key UNIQUE NULLS NOT DISTINCT (project_id, iteration_id, natural_key)
);

CREATE INDEX idx_artifact_nodes_project_kind ON artifact_nodes (project_id, node_kind);
CREATE INDEX idx_artifact_nodes_project_natural_key ON artifact_nodes (project_id, natural_key);
CREATE INDEX idx_artifact_nodes_project_iteration ON artifact_nodes (project_id, iteration_id);

CREATE TABLE artifact_edges (
    edge_id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects (project_id) ON DELETE CASCADE,
    from_node_id uuid NOT NULL REFERENCES artifact_nodes (node_id) ON DELETE CASCADE,
    to_node_id uuid NOT NULL REFERENCES artifact_nodes (node_id) ON DELETE CASCADE,
    edge_type text NOT NULL,
    source_reference text,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz,
    CONSTRAINT ck_artifact_edges_type CHECK (edge_type IN ('DERIVED_FROM','DEPENDS_ON','DISPOSES','EVIDENCED_BY','EXECUTED_FOR','BLOCKS')),
    CONSTRAINT ck_artifact_edges_source_reference_not_blank CHECK (source_reference IS NULL OR btrim(source_reference) <> ''),
    CONSTRAINT ck_artifact_edges_metadata_object CHECK (jsonb_typeof(metadata) = 'object'),
    CONSTRAINT uq_artifact_edges_nodes_type UNIQUE (from_node_id, to_node_id, edge_type)
);

CREATE INDEX idx_artifact_edges_project_from ON artifact_edges (project_id, from_node_id);
CREATE INDEX idx_artifact_edges_project_to ON artifact_edges (project_id, to_node_id);
