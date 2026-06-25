# P2A 로컬 산출물 저장 서버 - Product Spec

## 1. 문제 정의

field: `problem`

P2A는 계획 문서, task graph, run 기록, proposal, review를 로컬 md/json 파일로 남기지만, 파일만으로는 project, iteration, task, run, document 사이의 관계형 조회와 검색, 향후 RAG/회고 기반 자기고도화 준비가 어렵습니다. 이 서버는 로컬 파일을 원본으로 유지하면서 산출물을 동기화하고 검색하기 위한 보조 저장소를 제공합니다.

## 2. 대상 사용자

field: `target_users`

- 로컬에서 P2A CLI/GUI를 사용하는 단일 개발자
- 향후 P2A GUI 또는 CLI 동기화 기능
- P2A run과 planning artifact를 회고하거나 검색하려는 운영자

## 3. 목표

field: `goals`

- project, iteration, task, run, document 관계를 보존한다.
- 문서 스냅샷, task graph, task, run, chunk를 REST API로 저장한다.
- content hash 기반으로 중복 저장을 방지하고 idempotent sync를 지원한다.
- projectId, iterationId, taskId, runId, artifactType, sourcePath 기준 조회를 제공한다.
- PostgreSQL keyword 검색과 pgvector 기반 vector 검색 준비를 제공한다.
- embedding은 외부에서 주입된 값을 저장하고 서버 내부에서 AI API를 호출하지 않는다.
- Docker Compose와 Testcontainers 기반 로컬 개발/검증 흐름을 제공한다.

## 4. 비목표

field: `non_goals`

- 로컬 md/json 파일을 대체하지 않는다.
- P2A 하네스 또는 AI agent를 직접 실행하지 않는다.
- embedding 생성, RAG 응답 생성, 자동 개선 적용은 하지 않는다.
- 다중 사용자 권한, OAuth, cloud deployment, queue 기반 ingestion은 MVP 범위가 아니다.
- proposal 자동 적용은 MVP 범위가 아니며 후속 단계에서도 proposal 생성까지만 고려한다.

## 5. 핵심 흐름

field: `core_flows`

- 클라이언트가 프로젝트를 등록하고 서버는 stable projectId를 반환하거나 수락한다.
- 클라이언트가 iteration을 등록하고 프로젝트와 연결한다.
- 클라이언트가 sourcePath, artifactType, contentHash, raw content 또는 metadata를 포함한 문서 스냅샷을 저장한다.
- 클라이언트가 task graph JSON과 graph 내 task 목록을 저장한다.
- 클라이언트가 task별 run 기록과 산출물 참조를 저장한다.
- 클라이언트가 문서 chunk와 선택적 embedding을 bulk 저장한다.
- 클라이언트가 관계형 필터 또는 keyword/vector query로 산출물을 조회한다.

## 6. 인터페이스

field: `screens_or_interfaces`

- `POST /api/projects`: 프로젝트 등록 또는 upsert
- `POST /api/projects/{projectId}/iterations`: iteration 등록 또는 upsert
- `POST /api/documents/snapshots`: 문서/산출물 스냅샷 저장
- `POST /api/task-graphs`: task graph 저장
- `POST /api/tasks/bulk`: task 저장 또는 task graph에서 추출한 task upsert
- `POST /api/runs`: run 기록 저장
- `POST /api/document-chunks/bulk`: chunk와 선택적 embedding 저장
- `GET /api/artifacts`: projectId, iterationId, taskId, runId, artifactType, sourcePath 필터 조회
- `GET /api/search/keyword`: keyword 검색
- `POST /api/search/vector`: 외부 주입 query embedding 기반 vector 검색
- `GET /actuator/health`: 로컬 개발용 health check

## 7. 데이터 모델

field: `data_model_draft`

- `projects`: projectId, name, rootPath, metadata, timestamps
- `iterations`: iterationId, projectId, label, status, metadata, timestamps
- `documents`: documentId, projectId, iterationId, artifactType, sourcePath, contentHash, snapshotVersion, content, metadata, timestamps
- `task_graphs`: taskGraphId, projectId, iterationId, sourceDocumentId, graphHash, graphJson, timestamps
- `tasks`: taskId, projectId, iterationId, taskGraphId, title, status, targetArea, dependenciesJson, acceptanceCriteriaJson, metadata
- `runs`: runId, projectId, iterationId, taskId, status, agentTool, startedAt, finishedAt, runJson, artifactRefsJson
- `document_chunks`: chunkId, documentId, projectId, iterationId, taskId, runId, artifactType, sourcePath, chunkIndex, chunkHash, content, tokenEstimate, embeddingModel, embeddingDimension, embedding vector, metadata
- content hash 중복 방지는 logical scope와 hash를 함께 사용한다. 같은 sourcePath/artifactType에 다른 hash가 들어오면 overwrite가 아니라 새 snapshotVersion을 만든다.

## 8. 외부 연동

field: `external_integrations`

- PostgreSQL with pgvector: 관계형 저장과 vector 컬럼을 한 데이터베이스에 둔다. pgvector는 Postgres 클라이언트와 함께 사용할 수 있고 vector distance operator와 HNSW/IVFFlat 인덱스를 제공한다. [WEB-1]
- P2A CLI/GUI: 후속 연동 클라이언트이며 MVP 서버는 REST API만 제공한다.
- Docker Compose: 로컬 PostgreSQL + pgvector 실행을 제공한다.
- Testcontainers: 통합 테스트에서 pgvector 이미지 기반 PostgreSQL을 실행한다. [WEB-3]
- AI API: 서버가 직접 호출하지 않는다.

## 9. 성공 기준

field: `success_criteria`

- Docker Compose로 pgvector가 활성화된 PostgreSQL을 띄우고 서버가 연결된다.
- 프로젝트, iteration, document, task graph, task, run, chunk 저장 API가 idempotent하게 동작한다.
- 동일 contentHash의 중복 저장이 추가 blob/chunk 중복을 만들지 않는다.
- 요구된 필터 기준 조회가 가능하다.
- keyword 검색이 document/chunk content를 대상으로 동작한다.
- 외부에서 embedding을 주입하면 vector 검색 API가 저장된 chunk를 반환한다.
- Testcontainers 기반 통합 테스트가 migration, 저장, 조회, 검색, 중복 방지 경로를 검증한다.

## 10. 제약

field: `constraints`

- 로컬 파일이 원본이고 서버는 보조 저장소다.
- 서버는 P2A 하네스와 AI agent를 실행하지 않는다.
- 초기 단일 사용자 로컬 개발용이다.
- 인증은 단순 API key 또는 local token이다.
- 서버는 embedding을 생성하지 않고 외부 입력만 저장한다.
- 자동 개선 적용은 하지 않는다.
- Gate B 승인 전에는 task graph 산출물을 만들지 않는다.
