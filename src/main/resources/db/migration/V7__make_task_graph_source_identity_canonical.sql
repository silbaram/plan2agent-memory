UPDATE task_graphs
SET source_task_graph_id = NULLIF(btrim(metadata ->> 'sourceTaskGraphId'), '')
WHERE source_task_graph_id IS NULL
  AND NULLIF(btrim(metadata ->> 'sourceTaskGraphId'), '') IS NOT NULL;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM task_graphs
        WHERE source_task_graph_id IS NULL
    ) THEN
        RAISE EXCEPTION
            'Cannot make task graph source identity canonical: legacy task_graphs rows still have null source_task_graph_id';
    END IF;
END;
$$;

ALTER TABLE task_graphs
    ALTER COLUMN source_task_graph_id SET NOT NULL;

DROP INDEX IF EXISTS uq_task_graphs_project_iteration_graph_hash;
