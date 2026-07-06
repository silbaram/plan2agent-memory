WITH resolved_task_graph_documents AS (
    SELECT
        tg.task_graph_id,
        d.document_id
    FROM task_graphs tg
    JOIN LATERAL (
        SELECT document_id
        FROM documents d
        WHERE d.project_id = tg.project_id
          AND d.iteration_id = tg.iteration_id
          AND d.source_document_id = tg.source_document_id
        ORDER BY d.snapshot_version DESC, d.created_at DESC, d.document_id
        LIMIT 1
    ) d ON TRUE
    WHERE tg.document_id IS NULL
      AND tg.source_document_id IS NOT NULL
)
UPDATE task_graphs tg
SET document_id = resolved.document_id
FROM resolved_task_graph_documents resolved
WHERE tg.task_graph_id = resolved.task_graph_id;
