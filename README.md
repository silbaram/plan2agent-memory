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

## REST API 명세

Base URL은 기본 실행 기준 `http://localhost:8080`입니다. 인증이 켜져 있다면 `/api/health`를 제외한 `/api/**` 요청에 `X-P2A-Local-Token` header를 포함해야 합니다.

### Endpoint 요약

| Method | Endpoint | 설명 | 인증 |
| --- | --- | --- | --- |
| `POST` | `/api/projects` | 프로젝트를 등록하거나 upsert합니다. | 필요 |
| `POST` | `/api/projects/{projectId}/iterations` | 프로젝트에 iteration을 연결해 등록하거나 upsert합니다. | 필요 |
| `POST` | `/api/documents/snapshots` | 문서 또는 산출물 snapshot을 저장합니다. | 필요 |
| `POST` | `/api/task-graphs` | task graph JSON과 graph metadata를 저장합니다. | 필요 |
| `POST` | `/api/tasks/bulk` | task graph에 속한 task 목록을 bulk 저장합니다. | 필요 |
| `POST` | `/api/runs` | task 실행 기록을 저장합니다. | 필요 |
| `POST` | `/api/document-chunks/bulk` | 문서 chunk와 선택적 embedding을 bulk 저장합니다. | 필요 |
| `GET` | `/api/artifacts` | 저장된 artifact를 filter 조건으로 조회합니다. | 필요 |
| `GET` | `/api/search/keyword` | RAG/history lookup을 위한 keyword 검색을 수행합니다. | 필요 |
| `POST` | `/api/search/vector` | 외부 query embedding으로 vector 검색을 수행합니다. | 필요 |
| `GET` | `/api/health` | 간단한 API health check입니다. | 불필요 |
| `GET` | `/actuator/health` | Spring Actuator health check입니다. | 불필요 |

### 공통 데이터 규칙

| 항목 | 의미 |
| --- | --- |
| Canonical server ID | 서버가 canonical하게 다루는 ID입니다. 예: `projectId`, `iterationId`, `documentId`, `taskGraphId`, `taskId`, `runId`, `chunkId`. |
| Source ID | 로컬/P2A 원본 시스템의 ID입니다. 예: `sourceProjectId`, `sourceIterationId`, `sourceDocumentId`, `sourceTaskGraphId`, `sourceTaskId`, `sourceRunId`. |
| Lineage | artifact의 출처와 버전을 추적하는 metadata입니다. 예: `lineage.projectId`, `lineage.iterationId`, `lineage.sourcePath`, `lineage.contentHash`, `lineage.snapshotVersion`, `lineage.taskId`, `lineage.runId`. |
| Source reference | canonical ID와 원본 위치를 연결합니다. 예: `sourceReference.canonicalServerId`, `sourceReference.uri`, `sourceReference.path`. |

P2A GUI/CLI는 위 metadata를 사용해 git client처럼 status, diff, push, pull, conflict resolution, history UI/workflow를 구현하는 동기화 클라이언트입니다. 서버는 metadata를 저장하고 조회할 뿐, 로컬 파일을 자동 수정하거나 병합하지 않습니다.

### `POST /api/projects`

프로젝트를 등록 또는 upsert합니다.

| Request field | 필수 | 설명 |
| --- | --- | --- |
| `projectId` | 선택 | Canonical server UUID입니다. |
| `sourceProjectId` | 권장 | 로컬/P2A project ID입니다. |
| `name` | 필수 | 프로젝트 이름입니다. |
| `canonicalServerId` | 선택 | 생략하면 `projectId`를 사용합니다. |
| `rootPath` | 선택 | 로컬 repository/project root path입니다. |
| `sourceReference` | 선택 | 원본 위치 참조 정보입니다. |
| `metadata` | 선택 | 확장 metadata입니다. |

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

| Response | 포함 정보 |
| --- | --- |
| `ProjectResponse` | `projectId`, `canonicalServerId`, `sourceProjectId`, `rootPath`, `sourceReference`, `metadata` |

### `POST /api/projects/{projectId}/iterations`

Iteration을 프로젝트에 연결해 등록 또는 upsert합니다.

| Request field | 필수 | 설명 |
| --- | --- | --- |
| `projectId` | 필수 | Path variable입니다. |
| `iterationId` | 선택 | Canonical iteration ID입니다. |
| `sourceIterationId` | 권장 | 로컬/P2A iteration ID입니다. |
| `label` | 필수 | Iteration 표시 이름입니다. |
| `status` | 필수 | `PLANNED`, `ACTIVE`, `APPROVED`, `COMPLETED`, `ARCHIVED` 중 하나입니다. |
| `sourceReference` | 선택 | 원본 위치 참조 정보입니다. |
| `metadata` | 선택 | 확장 metadata입니다. |

| Response | 포함 정보 |
| --- | --- |
| `IterationResponse` | Iteration canonical/source ID, `label`, `status`, `sourceReference`, `metadata` |

### `POST /api/documents/snapshots`

문서 또는 산출물 snapshot을 저장합니다.

| Request field | 필수 | 설명 |
| --- | --- | --- |
| `documentId` | 선택 | Canonical document snapshot ID입니다. |
| `projectId` | 필수 | 소속 project ID입니다. |
| `iterationId` | 선택 | 소속 iteration ID입니다. |
| `sourceDocumentId` | 권장 | 로컬/P2A document ID입니다. |
| `sourcePath` | 필수 | 정규화 대상 source path입니다. |
| `snapshotVersion` | 선택 | Snapshot version입니다. |
| `artifactType` | 필수 | 예: `DOCUMENT_SNAPSHOT`, `PROPOSAL`. |
| `title` | 선택 | 문서 제목입니다. |
| `content` | 필수 | 문서 본문입니다. |
| `contentHash` | 필수 | 내용 hash입니다. |
| `sourceReference` | 선택 | 원본 위치 참조 정보입니다. |
| `capturedAt` | 선택 | Snapshot 수집 시각입니다. |
| `metadata` | 선택 | 확장 metadata입니다. |

| Response | 포함 정보 |
| --- | --- |
| `DocumentSnapshotResponse` | Snapshot 정보와 `lineage.contentHash`, `lineage.snapshotVersion`, `metadata.sourceDocumentId` |

### `POST /api/task-graphs`

Task graph JSON과 graph metadata를 저장합니다.

| Request field | 필수 | 설명 |
| --- | --- | --- |
| `taskGraphId` | 선택 | Canonical task graph ID입니다. |
| `projectId` | 필수 | 소속 project ID입니다. |
| `iterationId` | 선택 | 소속 iteration ID입니다. |
| `sourceTaskGraphId` | 권장 | 로컬/P2A task graph ID입니다. |
| `sourceDocumentId` | 선택 | Graph를 생성한 source document ID입니다. |
| `graphHash` | 필수 | Graph JSON hash입니다. |
| `graphJson` | 필수 | Task graph 원본 JSON입니다. |
| `taskIds` | 선택 | Graph에 포함된 task ID 목록입니다. |
| `dependencyEdges` | 선택 | Task dependency edge 목록입니다. |
| `sourceReference` | 선택 | 원본 위치 참조 정보입니다. |
| `metadata` | 선택 | 확장 metadata입니다. |

| Response | 포함 정보 |
| --- | --- |
| `TaskGraphResponse` | Task graph canonical/source ID, graph hash, task/dependency metadata |

### `POST /api/tasks/bulk`

Task graph에 속한 task 목록을 저장합니다.

| Request field | 필수 | 설명 |
| --- | --- | --- |
| `graphId` | 필수 | Task들이 속한 graph ID입니다. |
| `tasks[].taskId` | 선택 | Canonical task ID입니다. |
| `tasks[].projectId` | 필수 | 소속 project ID입니다. |
| `tasks[].iterationId` | 선택 | 소속 iteration ID입니다. |
| `tasks[].taskGraphId` | 필수 | 소속 task graph ID입니다. |
| `tasks[].sourceTaskId` | 권장 | 로컬/P2A task ID입니다. |
| `tasks[].title` | 필수 | Task 제목입니다. |
| `tasks[].description` | 선택 | Task 설명입니다. |
| `tasks[].status` | 필수 | `READY`, `BLOCKED`, `IN_PROGRESS`, `DONE` 중 하나입니다. |
| `tasks[].targetArea` | 선택 | 구현/검토 대상 영역입니다. |
| `tasks[].dependencies` | 선택 | 선행 task ID 목록입니다. |
| `tasks[].acceptanceCriteria` | 선택 | 완료 기준 목록입니다. |
| `tasks[].sourceReference` | 선택 | 원본 위치 참조 정보입니다. |
| `tasks[].metadata` | 선택 | 확장 metadata입니다. |

| Response | 포함 정보 |
| --- | --- |
| `TaskResponse[]` | 저장된 task 목록과 lineage/source metadata |

### `POST /api/runs`

Task 실행 기록을 저장합니다.

| Request field | 필수 | 설명 |
| --- | --- | --- |
| `runId` | 선택 | Canonical run ID입니다. |
| `projectId` | 필수 | 소속 project ID입니다. |
| `iterationId` | 선택 | 소속 iteration ID입니다. |
| `taskId` | 필수 | 실행 대상 task ID입니다. |
| `sourceRunId` | 권장 | 로컬/P2A run ID입니다. |
| `status` | 필수 | `STARTED`, `FINISHED`, `FAILED`, `BLOCKED` 중 하나입니다. |
| `agentTool` | 선택 | 실행한 agent/tool 이름입니다. |
| `runJson` | 선택 | 실행 상세 JSON입니다. |
| `artifactRefs` | 선택 | 실행 중 생성/참조한 artifact 목록입니다. |
| `startedAt` | 선택 | 시작 시각입니다. |
| `finishedAt` | 선택 | 종료 시각입니다. |
| `sourceReference` | 선택 | 원본 위치 참조 정보입니다. |
| `metadata` | 선택 | 확장 metadata입니다. |

| Response | 포함 정보 |
| --- | --- |
| `RunRecordResponse` | Run 정보와 `lineage.taskId`, `lineage.runId`, `metadata.sourceRunId` |

### `POST /api/document-chunks/bulk`

문서 chunk와 선택적 embedding을 저장합니다. `embeddingSet`과 `embedding`은 함께 제공해야 하며, 둘 중 하나만 있으면 validation error입니다.

| Request field | 필수 | 설명 |
| --- | --- | --- |
| `documentId` | 필수 | Chunk가 속한 document ID입니다. |
| `chunks[].chunk.chunkId` | 선택 | Canonical chunk ID입니다. |
| `chunks[].chunk.projectId` | 필수 | 소속 project ID입니다. |
| `chunks[].chunk.iterationId` | 선택 | 소속 iteration ID입니다. |
| `chunks[].chunk.taskId` | 선택 | 연결된 task ID입니다. |
| `chunks[].chunk.runId` | 선택 | 연결된 run ID입니다. |
| `chunks[].chunk.artifactType` | 필수 | Chunk의 artifact type입니다. |
| `chunks[].chunk.sourcePath` | 필수 | 정규화 대상 source path입니다. |
| `chunks[].chunk.chunkIndex` | 필수 | 문서 내 chunk 순서입니다. |
| `chunks[].chunk.content` | 필수 | Chunk 본문입니다. |
| `chunks[].chunk.chunkHash` | 필수 | Chunk 내용 hash입니다. |
| `chunks[].chunk.tokenEstimate` | 선택 | Token 추정치입니다. |
| `chunks[].chunk.sourceReference` | 선택 | 원본 위치 참조 정보입니다. |
| `chunks[].chunk.metadata` | 선택 | Chunk 확장 metadata입니다. |
| `chunks[].embeddingSet` | 선택 | Embedding set 정보입니다. |
| `chunks[].embedding` | 선택 | 외부 클라이언트가 생성한 vector입니다. |
| `chunks[].embeddingHash` | 선택 | Embedding vector hash입니다. |

| `embeddingSet` field | 필수 | 설명 |
| --- | --- | --- |
| `embeddingSetId` | 선택 | Canonical embedding set ID입니다. |
| `projectId` | 필수 | 소속 project ID입니다. |
| `embeddingModel` | 필수 | Embedding model 이름입니다. |
| `embeddingDimension` | 필수 | Vector 차원입니다. |
| `embeddingVersion` | 필수 | Embedding model/version 문자열입니다. |
| `distanceMetric` | 필수 | `COSINE`, `L2`, `INNER_PRODUCT` 중 하나입니다. |
| `storageType` | 필수 | `VECTOR_INDEX`, `INLINE`, `EXTERNAL` 중 하나입니다. |

| Response | 포함 정보 |
| --- | --- |
| `DocumentChunkResponse[]` | 저장된 chunk 목록과 `chunkHash`, `lineage.taskId`, `lineage.runId`, `sourceReference` |

### `GET /api/artifacts`

저장된 artifact를 filter 조건으로 조회합니다.

| Query param | 필수 | 설명 |
| --- | --- | --- |
| `projectId` | 선택 | Project ID filter입니다. |
| `iterationId` | 선택 | Iteration ID filter입니다. |
| `sourceProjectId` | 선택 | Source project ID filter입니다. |
| `sourceIterationId` | 선택 | Source iteration ID filter입니다. |
| `sourceDocumentId` | 선택 | Source document ID filter입니다. |
| `sourceTaskGraphId` | 선택 | Source task graph ID filter입니다. |
| `sourceTaskId` | 선택 | Source task ID filter입니다. |
| `sourceRunId` | 선택 | Source run ID filter입니다. |
| `artifactType` | 선택 | Artifact type filter입니다. Proposal snapshot은 `PROPOSAL`로 조회할 수 있습니다. |
| `sourcePath` | 선택 | 정규화된 source path filter입니다. |
| `taskId` | 선택 | Canonical task ID filter입니다. |
| `runId` | 선택 | Canonical run ID filter입니다. |
| `contentHash` | 선택 | Content hash filter입니다. |
| `sourceReferenceCanonicalServerId` | 선택 | Source reference canonical server ID filter입니다. |
| `sourceReferenceUri` | 선택 | Source reference URI filter입니다. |
| `limit` | 선택 | 최대 응답 개수입니다. |

| Response | 포함 정보 |
| --- | --- |
| `ArtifactLookupResponse[]` | 각 항목의 `lineage`, `sourceIds`, `sourceReference`, `contentHash`, `snapshotVersion` |

### `GET /api/search/keyword`

RAG/history lookup을 위한 deterministic lexical retrieval입니다.

| Query param | 필수 | 설명 |
| --- | --- | --- |
| `q` | 필수 | Keyword query입니다. |
| `projectId` | 선택 | Project ID filter입니다. |
| `iterationId` | 선택 | Iteration ID filter입니다. |
| `artifactType` | 선택 | Artifact type filter입니다. Proposal snapshot은 `PROPOSAL`로 검색할 수 있습니다. |
| `sourcePath` | 선택 | 정규화된 source path filter입니다. |
| `taskId` | 선택 | Canonical task ID filter입니다. |
| `runId` | 선택 | Canonical run ID filter입니다. |
| `limit` | 선택 | 최대 응답 개수입니다. |

| 검색 동작 | 설명 |
| --- | --- |
| 검색 대상 | 주 대상은 `document_chunks.content`, 보조 대상은 `documents.content`, `sourcePath`, `artifactType`입니다. |
| Matching | Case-insensitive matching입니다. |
| Filter semantics | 여러 filter는 AND semantics로 적용됩니다. |
| Score | `score`는 backend-opaque 값이며 API 안정 계약으로 고정하지 않습니다. |
| Tie-break | 동률은 snapshot/version/timestamp/chunkIndex 기준으로 정렬합니다. |

| Response | 포함 정보 |
| --- | --- |
| `KeywordSearchResponse[]` | `content`, `score`, `matchReason`, `lineage`, `sourceIds`, `metadata` |

### `POST /api/search/vector`

외부에서 받은 query embedding으로 pgvector exact search를 수행합니다.

| Request field | 필수 | 설명 |
| --- | --- | --- |
| `embedding` | 필수 | Query vector입니다. 비어 있으면 validation error입니다. |
| `embeddingModel` | 필수 | 검색할 embedding model 이름입니다. |
| `embeddingDimension` | 필수 | Vector 차원입니다. `embedding.size`와 같아야 합니다. |
| `embeddingVersion` | 필수 | 검색할 embedding version입니다. |
| `distanceMetric` | 선택 | 생략 시 `COSINE`입니다. |
| `projectId` | 선택 | Project ID filter입니다. |
| `iterationId` | 선택 | Iteration ID filter입니다. |
| `artifactType` | 선택 | Artifact type filter입니다. |
| `sourcePath` | 선택 | 정규화된 source path filter입니다. |
| `taskId` | 선택 | Canonical task ID filter입니다. |
| `runId` | 선택 | Canonical run ID filter입니다. |
| `metadataFilters` | 선택 | Metadata key/value filter입니다. |
| `limit` | 선택 | 최대 응답 개수입니다. |

| 검색 동작 | 설명 |
| --- | --- |
| Matching scope | 같은 `embeddingModel`, `embeddingDimension`, `embeddingVersion`, `distanceMetric` embedding set 안에서만 검색합니다. |
| Dimension validation | 저장된 embedding set 차원과 요청 차원이 다르면 validation error입니다. |
| Search mode | pgvector exact search를 사용합니다. |

| Response | 포함 정보 |
| --- | --- |
| `VectorSearchResponse[]` | `score`, `distanceMetric`, `embeddingModel`, `embeddingVersion`, `lineage`, `sourceIds` |

### Health endpoints

| Method | Endpoint | 설명 | 인증 |
| --- | --- | --- | --- |
| `GET` | `/api/health` | 간단한 API health endpoint입니다. | 불필요 |
| `GET` | `/actuator/health` | Spring Actuator health endpoint입니다. | 불필요 |

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
