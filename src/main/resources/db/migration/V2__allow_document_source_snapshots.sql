DROP INDEX IF EXISTS uq_documents_project_source_document_id;

CREATE UNIQUE INDEX uq_documents_project_iteration_source_document_hash
    ON documents (
        project_id,
        iteration_id,
        source_document_id,
        content_hash
    )
    NULLS NOT DISTINCT
    WHERE source_document_id IS NOT NULL;
