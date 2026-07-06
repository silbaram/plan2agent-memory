CREATE TABLE chunk_embedding_vectors_2 (
    chunk_embedding_id uuid PRIMARY KEY REFERENCES chunk_embeddings (chunk_embedding_id) ON DELETE CASCADE,
    embedding vector(2) NOT NULL
);

CREATE TABLE chunk_embedding_vectors_1536 (
    chunk_embedding_id uuid PRIMARY KEY REFERENCES chunk_embeddings (chunk_embedding_id) ON DELETE CASCADE,
    embedding vector(1536) NOT NULL
);

INSERT INTO chunk_embedding_vectors_2 (chunk_embedding_id, embedding)
SELECT ce.chunk_embedding_id, ce.embedding::vector(2)
FROM chunk_embeddings ce
JOIN embedding_sets es ON es.embedding_set_id = ce.embedding_set_id
WHERE es.embedding_dimension = 2
ON CONFLICT (chunk_embedding_id) DO NOTHING;

INSERT INTO chunk_embedding_vectors_1536 (chunk_embedding_id, embedding)
SELECT ce.chunk_embedding_id, ce.embedding::vector(1536)
FROM chunk_embeddings ce
JOIN embedding_sets es ON es.embedding_set_id = ce.embedding_set_id
WHERE es.embedding_dimension = 1536
ON CONFLICT (chunk_embedding_id) DO NOTHING;

CREATE INDEX idx_chunk_embedding_vectors_2_hnsw_cosine
    ON chunk_embedding_vectors_2 USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_chunk_embedding_vectors_2_hnsw_l2
    ON chunk_embedding_vectors_2 USING hnsw (embedding vector_l2_ops);
CREATE INDEX idx_chunk_embedding_vectors_2_hnsw_ip
    ON chunk_embedding_vectors_2 USING hnsw (embedding vector_ip_ops);

CREATE INDEX idx_chunk_embedding_vectors_1536_hnsw_cosine
    ON chunk_embedding_vectors_1536 USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_chunk_embedding_vectors_1536_hnsw_l2
    ON chunk_embedding_vectors_1536 USING hnsw (embedding vector_l2_ops);
CREATE INDEX idx_chunk_embedding_vectors_1536_hnsw_ip
    ON chunk_embedding_vectors_1536 USING hnsw (embedding vector_ip_ops);
