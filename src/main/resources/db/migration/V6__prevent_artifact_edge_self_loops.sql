ALTER TABLE artifact_edges
    ADD CONSTRAINT ck_artifact_edges_no_self_loop CHECK (from_node_id <> to_node_id) NOT VALID;
