# Plan2Agent Memory Server

Plan2Agent Memory Server는 로컬 P2A 산출물을 관계형으로 저장하고 검색하기 위한 headless REST service입니다. 로컬 파일이 원본(source of truth)이고, 이 서버는 동기화된 artifact의 canonical ID, lineage, hash, relation, keyword/vector 검색 인덱스를 제공하는 보조 저장소입니다.

서버는 P2A harness, agent, 외부 AI API를 실행하지 않습니다. Embedding 값은 외부 클라이언트가 생성해 주입하며, 서버는 받은 embedding을 `embedding_sets`와 `chunk_embeddings`에 저장하고 검색합니다.

## 로컬 실행

### 요구 사항

- Java 21
- Gradle wrapper
- Docker Compose
- Docker가 실행 가능한 로컬 환경

### PostgreSQL 시작

`compose.yaml`은 `pgvector/pgvector:pg17` 기반 PostgreSQL을 시작합니다.

```bash
docker compose up -d postgres
```

기본 DB 설정은 다음과 같습니다.

- DB: `p2a_artifact_store`
- User: `p2a`
- Password: `p2a_local_password`
- Port: `5432`
- JDBC URL: `jdbc:postgresql://localhost:5432/p2a_artifact_store`

### 애플리케이션 실행

기본값으로 실행:

```bash
./gradlew bootRun
```

환경 변수로 DB와 인증을 지정할 수 있습니다.

```bash
P2A_DB_URL=jdbc:postgresql://localhost:5432/p2a_artifact_store \
P2A_DB_USERNAME=p2a \
P2A_DB_PASSWORD=p2a_local_password \
P2A_LOCAL_TOKEN=local-dev-token \
./gradlew bootRun
```

`P2A_LOCAL_TOKEN`이 비어 있으면 `/api/**`도 인증 없이 열립니다. 값이 있으면 `/api/health`와 `/actuator/health`를 제외한 `/api/**` 요청에 `X-P2A-Local-Token` header가 필요합니다. Header 이름은 `P2A_LOCAL_TOKEN_HEADER`로 바꿀 수 있습니다.

### Health check

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/api/health
```

정상 응답은 `status: "UP"`입니다.

### 테스트 실행

통합 테스트는 Testcontainers로 `pgvector/pgvector:pg16` PostgreSQL을 시작합니다.

```bash
./gradlew test
./gradlew compileKotlin compileTestKotlin
```

Lima Docker socket을 쓰는 로컬 환경에서는 다음처럼 실행할 수 있습니다.

```bash
DOCKER_HOST=unix:///Users/qoo10/.lima/default/sock/docker.sock \
TESTCONTAINERS_RYUK_DISABLED=true \
./gradlew test --rerun-tasks
```

## 인증

보호 대상:

- `/api/**`

인증 제외:

- `/api/health`
- `/actuator/health`

인증이 켜진 경우 요청 예:

```bash
curl -H 'X-P2A-Local-Token: local-dev-token' \
  http://localhost:8080/api/artifacts
```

인증 실패는 `401`과 `RestErrorResponse`를 반환합니다.

```json
{
  "error": "auth_error",
  "message": "Missing or invalid local API token",
  "status": 401
}
```

## 공통 응답 의미

대부분의 write response는 다음 metadata를 포함합니다.

- canonical server ID: `projectId`, `iterationId`, `documentId`, `taskGraphId`, `taskId`, `runId`, `chunkId`
- source ID: `sourceProjectId`, `sourceIterationId`, `sourceDocumentId`, `sourceTaskGraphId`, `sourceTaskId`, `sourceRunId`
- lineage: `lineage.projectId`, `lineage.iterationId`, `lineage.sourcePath`, `lineage.contentHash`, `lineage.snapshotVersion`, `lineage.taskId`, `lineage.runId`
- source reference: `sourceReference.canonicalServerId`, `sourceReference.uri`, `sourceReference.path`

P2A GUI/CLI는 이 metadata를 사용해 git client처럼 status, diff, push, pull, conflict resolution, history UI/workflow를 구현하는 동기화 클라이언트입니다. 서버는 이 metadata를 저장하고 조회할 뿐, 로컬 파일을 자동 수정하거나 병합하지 않습니다.

## REST endpoints

### `POST /api/projects`

프로젝트를 등록 또는 upsert합니다.

주요 request fields:

- `projectId`: canonical server UUID
- `sourceProjectId`: 로컬/P2A project ID
- `name`
- `canonicalServerId`: 생략하면 `projectId`
- `rootPath`
- `sourceReference`
- `metadata`

예:

```json
{
  "projectId": "11111111-1111-1111-1111-111111111111",
  "sourceProjectId": "local-project",
  "name": "Local Project",
  "canonicalServerId": "11111111-1111-1111-1111-111111111111",
  "rootPath": "/repo/local-project",
  "sourceReference": {
    "canonicalServerId": "11111111-1111-1111-1111-111111111111",
    "uri": "file:///repo/local-project",
    "path": "projects/local-project"
  },
  "metadata": {}
}
```

응답은 `ProjectResponse`이며 `projectId`, `canonicalServerId`, `sourceProjectId`, `rootPath`, `sourceReference`, `metadata`를 포함합니다.

### `POST /api/projects/{projectId}/iterations`

iteration을 프로젝트에 연결해 등록 또는 upsert합니다.

주요 request fields:

- path `projectId`
- `iterationId`
- `sourceIterationId`
- `label`
- `status`: `PLANNED`, `ACTIVE`, `APPROVED`, `COMPLETED`, `ARCHIVED`
- `sourceReference`
- `metadata`

응답은 `IterationResponse`입니다.

### `POST /api/documents/snapshots`

문서 또는 산출물 snapshot을 저장합니다.

주요 request fields:

- `documentId`
- `projectId`
- `iterationId`
- `sourceDocumentId`
- `sourcePath`
- `snapshotVersion`
- `artifactType`: 예: `DOCUMENT_SNAPSHOT`
- `title`
- `content`
- `contentHash`
- `sourceReference`
- `capturedAt`
- `metadata`

응답은 `DocumentSnapshotResponse`이며 `lineage.contentHash`, `lineage.snapshotVersion`, `metadata.sourceDocumentId`를 포함합니다.

### `POST /api/task-graphs`

task graph JSON과 graph metadata를 저장합니다.

주요 request fields:

- `taskGraphId`
- `projectId`
- `iterationId`
- `sourceTaskGraphId`
- `sourceDocumentId`
- `graphHash`
- `graphJson`
- `taskIds`
- `dependencyEdges`
- `sourceReference`
- `metadata`

응답은 `TaskGraphResponse`입니다.

### `POST /api/tasks/bulk`

task graph에 속한 task 목록을 저장합니다.

주요 request fields:

- `graphId`
- `tasks[].taskId`
- `tasks[].projectId`
- `tasks[].iterationId`
- `tasks[].taskGraphId`
- `tasks[].sourceTaskId`
- `tasks[].title`
- `tasks[].description`
- `tasks[].status`: `READY`, `BLOCKED`, `IN_PROGRESS`, `DONE`
- `tasks[].targetArea`
- `tasks[].dependencies`
- `tasks[].acceptanceCriteria`
- `tasks[].sourceReference`
- `tasks[].metadata`

응답은 `TaskResponse[]`입니다.

### `POST /api/runs`

task 실행 기록을 저장합니다.

주요 request fields:

- `runId`
- `projectId`
- `iterationId`
- `taskId`
- `sourceRunId`
- `status`: `STARTED`, `FINISHED`, `FAILED`, `BLOCKED`
- `agentTool`
- `runJson`
- `artifactRefs`
- `startedAt`
- `finishedAt`
- `sourceReference`
- `metadata`

응답은 `RunRecordResponse`이며 `lineage.taskId`, `lineage.runId`, `metadata.sourceRunId`를 포함합니다.

### `POST /api/document-chunks/bulk`

문서 chunk와 선택적 embedding을 저장합니다.

주요 request fields:

- `documentId`
- `chunks[].chunk.chunkId`
- `chunks[].chunk.projectId`
- `chunks[].chunk.iterationId`
- `chunks[].chunk.taskId`
- `chunks[].chunk.runId`
- `chunks[].chunk.artifactType`
- `chunks[].chunk.sourcePath`
- `chunks[].chunk.chunkIndex`
- `chunks[].chunk.content`
- `chunks[].chunk.chunkHash`
- `chunks[].chunk.tokenEstimate`
- `chunks[].chunk.sourceReference`
- `chunks[].chunk.metadata`
- `chunks[].embeddingSet`
- `chunks[].embedding`
- `chunks[].embeddingHash`

`embeddingSet`과 `embedding`은 함께 제공해야 합니다. 둘 중 하나만 있으면 validation error입니다.

`embeddingSet` 주요 fields:

- `embeddingSetId`
- `projectId`
- `embeddingModel`
- `embeddingDimension`
- `embeddingVersion`
- `distanceMetric`: `COSINE`, `L2`, `INNER_PRODUCT`
- `storageType`: `VECTOR_INDEX`, `INLINE`, `EXTERNAL`

응답은 `DocumentChunkResponse[]`이며 `chunkHash`, `lineage.taskId`, `lineage.runId`, `sourceReference`를 포함합니다.

### `GET /api/artifacts`

저장된 artifact를 필터로 조회합니다.

지원 query params:

- `projectId`
- `iterationId`
- `sourceProjectId`
- `sourceIterationId`
- `sourceDocumentId`
- `sourceTaskGraphId`
- `sourceTaskId`
- `sourceRunId`
- `artifactType`
- `sourcePath`
- `taskId`
- `runId`
- `contentHash`
- `sourceReferenceCanonicalServerId`
- `sourceReferenceUri`
- `limit`

응답은 `ArtifactLookupResponse[]`입니다. 각 항목은 `lineage`, `sourceIds`, `sourceReference`, `contentHash`, `snapshotVersion`을 포함합니다.

### `GET /api/search/keyword`

RAG/history lookup을 위한 deterministic lexical retrieval입니다.

지원 query params:

- `q`: 필수 keyword query
- `projectId`
- `iterationId`
- `artifactType`
- `sourcePath`
- `taskId`
- `runId`
- `limit`

검색 대상:

- 주 대상: `document_chunks.content`
- 보조 대상: `documents.content`, `sourcePath`, `artifactType`

동작:

- case-insensitive matching
- filter는 AND semantics
- `score`는 backend-opaque 값이며 API 안정 계약으로 고정하지 않음
- 동률은 snapshot/version/timestamp/chunkIndex 기준으로 정렬

응답은 `KeywordSearchResponse[]`이며 `content`, `score`, `matchReason`, `lineage`, `sourceIds`, `metadata`를 포함합니다.

### `POST /api/search/vector`

외부에서 받은 query embedding으로 pgvector exact search를 수행합니다.

주요 request fields:

- `embedding`
- `embeddingModel`
- `embeddingDimension`
- `embeddingVersion`
- `distanceMetric`: 생략 시 `COSINE`
- `projectId`
- `iterationId`
- `artifactType`
- `sourcePath`
- `taskId`
- `runId`
- `metadataFilters`
- `limit`

동작:

- query `embedding`은 비어 있으면 안 됩니다.
- `embeddingDimension`은 `embedding.size`와 같아야 합니다.
- 검색은 같은 `embeddingModel`, `embeddingDimension`, `embeddingVersion`, `distanceMetric` embedding set 안에서만 수행합니다.
- 저장된 embedding set 차원과 요청 차원이 다르면 validation error입니다.

응답은 `VectorSearchResponse[]`이며 `score`, `distanceMetric`, `embeddingModel`, `embeddingVersion`, `lineage`, `sourceIds`를 포함합니다.

### `GET /api/health`

간단한 API health endpoint입니다. 인증 대상에서 제외됩니다.

### `GET /actuator/health`

Spring Actuator health endpoint입니다. 인증 대상에서 제외됩니다.

## Idempotency와 versioning

### Document snapshot

동일 logical scope에서 같은 `sourcePath`, `artifactType`, `contentHash`가 반복 저장되면 기존 snapshot을 반환합니다. 새 row를 만들지 않습니다.

같은 `sourcePath`, `artifactType`에 다른 `contentHash`가 저장되면 overwrite하지 않고 새 snapshot을 만들며 `snapshotVersion`이 증가합니다.

### Document chunk

같은 `documentId`, `chunkHash`가 반복 저장되면 기존 chunk를 반환합니다. 새 chunk row를 만들지 않습니다.

같은 batch 안에서 `chunkId`, `chunkHash`, `chunkIndex`가 중복되면 validation error입니다.

### Chunk embedding

같은 `chunkId`와 `embeddingSetId`에 같은 `embeddingHash`와 같은 vector가 반복 저장되면 idempotent하게 기존 row를 반환합니다.

같은 `chunkId`와 `embeddingSetId`에 다른 `embeddingHash` 또는 다른 vector를 저장하려 하면 conflict입니다. 서버는 명시적 overwrite/update 정책 없이 기존 embedding을 덮어쓰지 않습니다.

새 embedding model 또는 version으로 전환할 때는 새 `embedding_sets` row와 새 `chunk_embeddings` row를 추가해 점진 전환과 비교 평가가 가능하게 합니다.

## Path 처리

`sourcePath`는 저장 use case에서 정규화됩니다.

- 앞뒤 공백 제거
- Windows separator `\`를 `/`로 변환
- 중복 `/` 축약
- 앞의 `./` 제거

이 문서에서 `normalizedPath`는 정규화 후의 path를 의미합니다. REST 응답의 `sourcePath`와 DB 컬럼 `source_path`에는 `normalizedPath`가 저장됩니다.

이 문서에서 `rawSourcePath`는 클라이언트가 보낸 원본 path를 의미합니다. REST payload에는 별도 top-level `rawSourcePath` field가 없고, `sourceReference.path`가 원본 path 역할을 합니다. 서버는 `sourceReference.path`를 DB 컬럼 `raw_source_path`에 보존합니다.

문서와 chunk 응답의 `sourcePath`는 정규화된 path입니다. `/api/artifacts`, `/api/search/keyword`, `/api/search/vector`의 `sourcePath` filter도 `normalizedPath` 기준으로 비교됩니다. 클라이언트가 로컬 파일과 다시 매칭할 때는 `sourcePath`, `artifactType`, `contentHash`, `snapshotVersion`, `sourceReference`를 함께 사용해야 합니다.

## Error semantics

공통 error response는 `RestErrorResponse`입니다.

```json
{
  "error": "validation_error",
  "message": "field is required",
  "status": 400
}
```

대표 status:

- `400 validation_error`: 필수 field 누락, 잘못된 enum, 잘못된 embedding dimension, 빈 query
- `401 auth_error`: local API token 누락 또는 불일치
- `404 not_found`: relation id 또는 source id를 찾을 수 없음
- `409 conflict`: 같은 logical key가 다른 canonical entity로 충돌하거나 embedding overwrite가 발생함

## Source of truth와 non-goals

- 로컬 md/json 파일이 원본입니다.
- 서버는 동기화된 artifact의 저장, 조회, 검색, lineage metadata 제공을 담당합니다.
- 서버는 로컬 파일을 자동 merge/delete하지 않습니다.
- 서버는 P2A harness를 실행하지 않습니다.
- 서버는 agent를 실행하지 않습니다.
- 서버는 외부 AI API를 호출하지 않습니다.
- 서버는 embedding을 생성하지 않습니다.
- 서버 내장 웹 UI는 제공하지 않습니다.
- status, diff, push, pull, conflict resolution, history UX는 P2A GUI/CLI가 담당합니다.
