# P2A 로컬 산출물 저장 서버 - Implementation Plan

## 1. 아키텍처

field: `architecture`

- Kotlin Spring Boot REST 애플리케이션 하나로 시작한다. 모듈은 controller, service, repository, migration, test fixture로 나눈다.
- persistence는 PostgreSQL을 기준으로 두고, schema migration은 Flyway로 관리한다. Spring Boot는 PostgreSQL 같은 외부 DB에서 Flyway 모듈을 사용할 때 database-specific module을 요구하므로 `flyway-database-postgresql`을 포함한다. [WEB-4]
- repository 계층은 Spring `JdbcClient` 또는 `NamedParameterJdbcTemplate` 중심으로 둔다. vector search, JSONB, upsert, content hash constraint는 명시 SQL이 단순하고 예측 가능하다. Spring Framework는 `JdbcClient`를 JDBC query/update용 통합 fluent API로 제공한다. [WEB-5]
- `CREATE EXTENSION IF NOT EXISTS vector`를 첫 migration에 포함한다. pgvector는 `vector(n)`뿐 아니라 모델별 차원이 다를 때 `vector` 타입을 사용할 수 있고, 차원별 partial/expression index를 나중에 만들 수 있다. [WEB-1]
- MVP vector 검색은 exact search를 기본으로 하고, embedding model/dimension이 안정화된 뒤 HNSW 또는 IVFFlat 인덱스를 migration으로 추가한다. pgvector 문서는 기본 exact search와 approximate index의 recall/speed trade-off를 구분한다. [WEB-1]
- 인증은 단일 local token/API key를 application config에서 읽고, 요청 header를 검증하는 얇은 filter/interceptor로 처리한다.

## 2. 인터페이스

field: `interfaces`

- request/response DTO는 외부 식별자를 문자열로 받는다. 서버 생성 UUID는 보조 경로로만 사용한다.
- 모든 write API는 `contentHash`, `sourcePath`, `artifactType`, 관계 ID를 명시적으로 받는다.
- `POST /api/documents/snapshots`는 같은 logical artifact와 contentHash에 대해 기존 snapshot을 반환하고, contentHash가 다르면 새 snapshotVersion을 만든다.
- `POST /api/document-chunks/bulk`는 chunkHash 기준으로 idempotent하게 저장하고 embedding이 없으면 keyword 검색 대상만 된다.
- `POST /api/search/vector`는 query embedding 배열, distance metric, limit, metadata filters를 받는다.
- error response는 validation error, auth error, conflict, not found를 구분한다.

## 3. 데이터 흐름

field: `data_flow`

- P2A CLI/GUI가 로컬 파일 산출물을 읽고 metadata와 contentHash를 계산한다.
- 클라이언트가 project와 iteration을 먼저 upsert한다.
- 클라이언트가 문서 스냅샷과 task graph를 저장하고, task graph 내 task를 bulk upsert한다.
- run 종료 시 클라이언트가 run JSON과 artifact refs를 저장한다.
- chunking과 embedding 생성은 클라이언트 또는 별도 외부 프로세스가 수행하고, 서버는 chunk content와 embedding 값을 저장한다.
- 검색 API는 filters를 먼저 적용한 뒤 keyword 또는 vector ranking을 수행한다.

## 4. 의존성

field: `dependencies`

- Kotlin, Spring Boot Web, Validation, JDBC, Actuator
- PostgreSQL JDBC driver
- Flyway core와 PostgreSQL database module
- PostgreSQL + pgvector Docker image for Compose
- Testcontainers PostgreSQL module과 Spring Boot Testcontainers service connection 지원. Spring Boot는 Testcontainers service connection 정보를 자동으로 application connection details에 반영할 수 있다. [WEB-2]
- Testcontainers PostgreSQL module은 `pgvector/pgvector` 이미지를 compatible image로 사용할 수 있다. [WEB-3]
- AI SDK 또는 embedding API client는 포함하지 않는다.

## 5. 엣지 케이스

field: `edge_cases`

- 같은 sourcePath/artifactType에 같은 contentHash가 반복 저장되면 기존 snapshot을 반환한다.
- 같은 sourcePath/artifactType에 다른 contentHash가 저장되면 새 version으로 보존한다.
- 서로 다른 sourcePath에 같은 contentHash가 나타나면 content blob dedup은 가능하지만 document relationship은 별도로 유지한다.
- embedding이 null인 chunk는 keyword 검색에만 포함한다.
- query embedding dimension과 stored embedding dimension이 다르면 400 validation error 또는 같은 dimension 필터로 제한한다.
- task graph가 없는 task 저장 요청은 400으로 거부하거나 explicit standalone flag가 있을 때만 허용한다.
- 로컬 파일 삭제는 MVP에서 서버 삭제로 자동 반영하지 않고 tombstone API는 후속으로 둔다.
- sourcePath는 OS별 separator 차이를 줄이기 위해 normalizedPath와 rawSourcePath를 함께 보존한다.

## 6. 검증

field: `verification`

- schema migration test: Testcontainers pgvector PostgreSQL에서 Flyway migration, `vector` extension, 주요 unique index를 검증한다.
- repository integration test: project/iteration/document/taskGraph/task/run/chunk upsert와 content hash idempotency를 검증한다.
- API integration test: local token 인증, validation error, filter 조회, keyword 검색, vector 검색을 검증한다.
- Docker Compose smoke test: 로컬 DB 시작과 서버 health check를 검증한다.
- negative test: embedding dimension mismatch, 누락된 relation id, 잘못된 artifactType, 중복 logical key 처리.
- no-AI guarantee: 서버 코드와 테스트에 외부 AI API 호출 경로가 없음을 검증한다.
