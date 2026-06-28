# P2A 로컬 산출물 저장 서버 - Implementation Plan

## 1. 아키텍처

field: `architecture`

- Kotlin + Spring Boot 4.1.0 stable 기반의 Spring WebMVC REST 애플리케이션 하나로 시작하되, 레이어드 아키텍처가 아니라 pragmatic hexagonal architecture로 구성한다. Java toolchain과 runtime baseline은 Java 25 LTS로 고정하고, Gradle은 Java 25 실행과 toolchain을 지원하는 9.1.0 이상 9.x를 기준으로 둔다. 빌드는 Gradle Kotlin DSL을 사용하며 루트 빌드 파일은 `build.gradle.kts`, 설정 파일은 `settings.gradle.kts`로 둔다. Spring Boot Gradle plugin은 Spring Boot runtime과 같은 stable 버전인 `org.springframework.boot` 4.1.0을 사용한다. 핵심 패키지는 `domain`, `application/usecase`, `application/port/in`, `application/port/out`, `adapter/in/rest`, `adapter/out/postgres`, `adapter/out/search`, `adapter/out/security`, `config`로 나눈다. [WEB-6] [WEB-7] [WEB-12] [WEB-14] [WEB-15] [WEB-16]
- 서버는 headless REST service로 유지한다. 서버 내장 웹 UI, server-side rendered page, static frontend bundle은 만들지 않고, 화면과 sync UX는 P2A GUI/CLI가 담당한다.
- application core는 P2A 산출물 저장/검색 규칙만 알고 Spring MVC, JDBC, PostgreSQL, pgvector SQL, HTTP DTO에 직접 의존하지 않는다.
- domain model은 canonical server ID와 source reference를 분리한다. 서버 내부 관계와 lineage는 canonical ID를 기준으로 하고, 로컬/P2A ID와 sourcePath는 source reference로 보존한다.
- inbound port는 use case 단위로 둔다: `RegisterProjectUseCase`, `RegisterIterationUseCase`, `SaveDocumentSnapshotUseCase`, `SaveTaskGraphUseCase`, `SaveTasksUseCase`, `SaveRunRecordUseCase`, `SaveDocumentChunksUseCase`, `FindArtifactsUseCase`, `KeywordSearchUseCase`, `VectorSearchUseCase`.
- outbound port는 저장, source-to-canonical ID 해석, 검색 backend를 분리한다: `ProjectStorePort`, `IterationStorePort`, `DocumentSnapshotStorePort`, `TaskGraphStorePort`, `TaskStorePort`, `RunRecordStorePort`, `DocumentChunkStorePort`, `ArtifactQueryPort`, `KeywordSearchPort`, `VectorSearchPort`, `ContentHashDeduplicationPort`, `SourceReferenceResolutionPort`.
- REST controller는 Spring WebMVC 기반 inbound adapter로만 동작하고 request/response DTO를 command/result 객체로 변환한다. 인증 filter/interceptor도 adapter 영역에 둔다. Spring Boot 문서는 servlet 기반 web application에서 Spring MVC 또는 Jersey auto-configuration을 사용할 수 있음을 설명하고, Spring MVC는 `@RestController`/`@RequestMapping` 기반 HTTP request 처리를 제공한다. [WEB-8]
- MVP에서는 Spring WebFlux와 R2DBC를 사용하지 않는다. WebFlux는 non-blocking, Reactive Streams back pressure 기반 reactive-stack framework이고, r2dbc-postgresql도 PostgreSQL용 R2DBC 구현을 제공하지만, 이 서버의 MVP는 로컬 단일 사용자 sync/write/query 중심이며 streaming/backpressure가 핵심 요구사항이 아니다. Flyway migration, pgvector SQL, JSONB, upsert, constraint, transaction 처리의 단순성을 우선해 WebMVC + JDBC를 선택한다. [WEB-9] [WEB-13]
- 초기 outbound adapter는 PostgreSQL 저장 adapter와 PgVector 검색 adapter다. 향후 OpenSearch/Elasticsearch를 붙일 때는 `KeywordSearchPort`/`VectorSearchPort` 구현만 추가하거나 교체한다.
- keyword 검색은 RAG/회고용 deterministic lexical retrieval 계약으로 정의한다. MVP adapter는 PostgreSQL 기반 case-insensitive normalized substring search로 시작하되, application core는 `KeywordSearchPort`에만 의존하고 검색 점수는 backend-specific opaque score로 취급한다. 검색 대상은 `document_chunks.content`를 주 대상으로 하고 `documents.content`, `sourcePath`, `artifactType`을 보조 대상으로 둔다. 의미 기반 유사 검색은 `VectorSearchPort`가 담당하고, 후속 RAG retrieval 단계에서는 keyword result와 vector result를 합치는 hybrid retrieval로 확장할 수 있다.
- persistence는 PostgreSQL을 기준으로 두고, schema migration은 Flyway로 관리한다. Spring Boot는 PostgreSQL 같은 외부 DB에서 Flyway 모듈을 사용할 때 database-specific module을 요구하므로 `flyway-database-postgresql`을 포함한다. [WEB-4]
- PostgreSQL adapter 내부는 Spring `JdbcClient`를 우선 사용하고, `JdbcClient`로 표현하기 어려운 batch/custom SQL 세부는 `NamedParameterJdbcTemplate` 또는 `JdbcTemplate`로 보완한다. vector search, JSONB, upsert, content hash constraint는 명시 SQL이 단순하고 예측 가능하다. Spring Framework는 `JdbcClient`를 `JdbcTemplate`과 `NamedParameterJdbcTemplate`의 unified client API로 제공한다. [WEB-5]
- `CREATE EXTENSION IF NOT EXISTS vector`를 첫 migration에 포함한다. pgvector는 `vector(n)`뿐 아니라 모델별 차원이 다를 때 `vector` 타입을 사용할 수 있고, 차원별 partial/expression index를 나중에 만들 수 있다. [WEB-1]
- MVP vector 검색은 cosine exact search를 기본으로 한다. embedding은 embeddingModel, embeddingDimension, embeddingVersion으로 embedding set을 구분하고, 검색은 기본적으로 같은 embedding set 안에서만 수행한다. 모델과 차원이 안정된 뒤 HNSW 또는 IVFFlat ANN index를 모델/차원별 partial 또는 expression index migration으로 추가한다. pgvector 문서는 기본 exact search와 approximate index의 recall/speed trade-off를 구분한다. [WEB-1]
- chunk 저장 모델은 `document_chunks`, `embedding_sets`, `chunk_embeddings`의 3-table 구조로 확정한다. `document_chunks`는 chunk identity, content, source reference, chunkHash만 책임지고, embedding metadata와 vector value는 `embedding_sets`와 `chunk_embeddings`에 저장한다. `embedding_sets`는 embeddingModel + embeddingDimension + embeddingVersion + distanceMetric으로 unique하게 식별하고, `chunk_embeddings`는 chunkId + embeddingSetId로 unique하게 식별한다. 이 구조는 chunkHash idempotency와 같은 chunk의 여러 embedding set 보존 요구를 동시에 만족한다.
- 인증은 단일 local token/API key를 application config에서 읽고, 요청 header를 검증하는 custom servlet `Filter` 또는 `HandlerInterceptor`로 처리한다. MVP에서는 Spring Security 전체를 사용하지 않는다. `spring-boot-starter-security`, Spring Security filter chain, session login, OAuth, RBAC는 후속 다중 사용자/권한 모델이 필요해질 때 별도 Technology Reconnaissance와 함께 재검토한다.

## 2. 인터페이스

field: `interfaces`

- request DTO는 로컬/P2A 식별자를 `source*Id`로 받을 수 있고, response DTO는 canonical server ID와 source reference를 함께 반환한다.
- 관계 필드는 canonical server ID를 우선 사용하되, import/sync 편의를 위해 source reference를 받아 canonical ID로 resolve하는 경로를 제공한다.
- response DTO는 P2A GUI/CLI가 status, diff, push, pull, conflict resolution, history 화면을 구성할 수 있도록 contentHash, snapshotVersion, lineage, timestamps, sourcePath, source*Id metadata를 포함한다.
- REST API는 application inbound port의 adapter이며, controller에서 persistence adapter를 직접 호출하지 않는다.
- 모든 write API는 `contentHash`, `sourcePath`, `artifactType`, 관계 ID를 명시적으로 받는다.
- `POST /api/documents/snapshots`는 같은 logical artifact와 contentHash에 대해 기존 snapshot을 반환하고, contentHash가 다르면 새 snapshotVersion을 만든다.
- `POST /api/document-chunks/bulk`는 chunkHash 기준으로 chunk를 idempotent하게 저장한다. embedding이 없으면 chunk는 keyword 검색 대상만 되고, embedding이 포함되면 서버가 embedding set을 resolve/create한 뒤 `chunk_embeddings`를 chunkId + embeddingSetId 기준으로 idempotent하게 upsert한다.
- `GET /api/search/keyword`는 RAG 후보 검색용 lexical query `q`, limit, projectId, iterationId, artifactType, sourcePath, taskId, runId 필터를 받는다. 빈 `q`는 400 validation error로 처리하고 filter-only 조회는 `GET /api/artifacts`를 사용한다. 응답은 `chunkId`, `documentId`, `projectId`, `iterationId`, `artifactType`, `sourcePath`, `chunkIndex`, `content`, backend-specific `score`, `matchReason`, `metadata`를 포함한다.
- `POST /api/search/vector`는 query embedding 배열, embeddingModel, embeddingDimension, embeddingVersion, distance metric, limit, metadata filters를 받는다. distance metric 기본값은 cosine이다.
- error response는 validation error, auth error, conflict, not found를 구분한다.

## 3. 데이터 흐름

field: `data_flow`

- P2A CLI/GUI가 로컬 파일 산출물을 읽고 metadata와 contentHash를 계산한다.
- 클라이언트가 sourceProjectId, sourceIterationId, sourceTaskId, sourceRunId 같은 로컬/P2A ID를 보내면 서버가 canonical server ID로 resolve하거나 새로 부여한다.
- P2A GUI/CLI는 로컬 hash와 서버 latest snapshot metadata를 비교해 status/diff/push/pull/conflict/history UX를 제공한다.
- 클라이언트가 REST inbound adapter로 요청을 보내면 controller가 command로 변환해 application use case를 호출한다.
- use case가 content hash, snapshot version, 관계 무결성 같은 도메인 규칙을 적용한 뒤 outbound port를 호출한다.
- PostgreSQL/PgVector adapter가 port를 구현해 project와 iteration upsert, 문서 스냅샷 저장, task graph/task bulk upsert를 수행한다.
- run 종료 시 클라이언트가 run JSON과 artifact refs를 저장하면 `SaveRunRecordUseCase`가 run과 관련 artifact 참조를 같은 application transaction으로 처리한다.
- chunking과 embedding 생성은 클라이언트 또는 별도 외부 프로세스가 수행한다. 서버는 chunk content를 `document_chunks`에 저장하고, embedding 값은 embedding set별로 `chunk_embeddings`에 저장한다.
- 검색 API는 `KeywordSearchUseCase` 또는 `VectorSearchUseCase`를 통해 filters를 적용한다. keyword 검색은 정확한 용어, 파일 경로, API path, 에러 문자열, 과거 결정 문구를 찾는 lexical retrieval이고, vector 검색은 의미 기반 유사 이력 검색이다. RAG 후보 검색에서는 두 결과를 후속 hybrid retrieval 단계에서 병합할 수 있게 양쪽 결과가 canonical/source metadata와 content snippet을 포함한다.

## 4. 의존성

field: `dependencies`

- Kotlin 2.2.x, Spring Boot 4.1.0 stable, Spring WebMVC, Validation, JDBC, Actuator, Java 25 LTS, Gradle 9.1.0 이상 9.x. 빌드는 Gradle Kotlin DSL(`build.gradle.kts`, `settings.gradle.kts`)을 사용한다. Spring Boot Gradle plugin은 `org.springframework.boot` 4.1.0을 적용하고, 의존성 버전은 기본적으로 Spring Boot managed versions/BOM(`spring-boot-dependencies`)을 따른다. Spring Boot 자체는 Java 17 이상부터 Java 26까지 호환되지만, 이 프로젝트의 표준 runtime/toolchain은 사용자의 결정에 따라 Java 25 LTS로 둔다. [WEB-6] [WEB-7] [WEB-8] [WEB-12] [WEB-14] [WEB-15] [WEB-16] [WEB-17]
- PostgreSQL JDBC driver `org.postgresql:postgresql`. pgJDBC는 PostgreSQL native network protocol을 사용하는 pure Java Type 4 JDBC driver이고, 현재 공식 사이트 기준 current release는 42.7.11이다. Spring Boot managed version을 우선하되, Spring Boot BOM에 없거나 보안 패치가 필요한 dependency만 명시적으로 최신 compatible patch를 검토한다. [WEB-10] [WEB-17]
- Flyway core와 PostgreSQL database module
- PostgreSQL + pgvector Docker image for Compose
- Testcontainers PostgreSQL module과 Spring Boot Testcontainers service connection 지원. Spring Boot는 Testcontainers service connection 정보를 자동으로 application connection details에 반영할 수 있다. [WEB-2]
- Testcontainers PostgreSQL module은 `pgvector/pgvector` 이미지를 compatible image로 사용할 수 있다. [WEB-3]
- Spring WebFlux, R2DBC, Spring Data R2DBC, JPA/Hibernate, Spring Security 전체, Spring Security OAuth는 MVP 의존성에 포함하지 않는다. WebFlux/R2DBC는 향후 streaming ingestion, high-concurrency reactive API, long-lived reactive endpoints가 실제 요구사항이 될 때 별도 adapter 후보로 재검토한다. Spring Security는 향후 다중 사용자, RBAC, OAuth, session login 요구가 생길 때 재검토한다. [WEB-9] [WEB-13]
- dependency version policy: Spring Boot managed versions/BOM을 따르는 dependency에는 직접 버전을 적지 않는다. 직접 버전 고정은 Gradle plugin 버전, Kotlin plugin 버전, Docker image tag, Spring Boot BOM에 없는 라이브러리, 또는 보안 패치가 필요한 dependency에만 제한한다. 직접 버전을 고정할 때는 공식 문서나 release source 기준 최신 compatible version을 확인한다. [WEB-16] [WEB-17]
- AI SDK 또는 embedding API client는 포함하지 않는다.

### 4.1. Gate B 기술 조사

field: `implementation.architecture`, `implementation.dependencies`, `evidence`

이 섹션은 Gate D에서 지적된 Technology Reconnaissance 누락을 보완하기 위한 한글 기술 조사 기록이다. 결정은 MVP 구현 안정성, 로컬 단일 사용자 제약, PostgreSQL/pgvector 명시 SQL 처리, 테스트 재현성을 기준으로 잡았다.

| 결정 | 선택 | 대안/비선택 | 근거와 판단 |
| --- | --- | --- | --- |
| 런타임/프레임워크 | Spring Boot 4.1.0 stable + Kotlin + Java 25 LTS | snapshot release와 Java 17 baseline은 제외 | Spring Boot 공식 프로젝트 페이지와 system requirements에서 4.1.0 stable, Java 17 이상부터 Java 26까지의 호환성, Gradle 8.14+/9.x 조건을 확인했다. Oracle roadmap에서 Java SE 25가 LTS release임을 확인했고, Gradle compatibility matrix에서 Java 25는 Gradle 9.1.0 이상에서 toolchain과 Gradle 실행을 지원함을 확인했다. Kotlin은 Spring Boot가 Kotlin BOM과 plugin alignment를 관리한다. [WEB-6] [WEB-7] [WEB-12] [WEB-14] [WEB-15] |
| Build system | Gradle Kotlin DSL + Spring Boot Gradle plugin 4.1.0 + Spring Boot managed versions | Groovy DSL, ad hoc dependency version pinning은 비선택 | 빌드 파일은 `build.gradle.kts`와 `settings.gradle.kts`로 고정한다. Spring Boot Gradle plugin은 Spring Boot stable runtime과 같은 `4.1.0`을 사용하고, dependency version은 Spring Boot managed versions/BOM을 우선한다. 직접 버전 고정은 plugin, Docker image tag, BOM 외부 라이브러리, 보안 패치에만 제한한다. [WEB-16] [WEB-17] |
| Web stack | Spring WebMVC | Spring WebFlux는 MVP 비선택 | WebMVC는 servlet 기반 REST controller에 직접 맞고, 현재 요구사항은 reactive streaming/backpressure보다 REST sync/write/query가 중심이다. WebFlux는 후속 reactive API 요구가 생기면 재검토한다. [WEB-8] [WEB-9] |
| DB access | Spring JDBC `JdbcClient` 우선 | R2DBC, JPA/Hibernate는 MVP 비선택 | `JdbcClient`는 Spring Framework의 unified JDBC query/update API다. pgvector operator, JSONB, upsert, Flyway migration, constraint 중심 구현은 명시 SQL이 단순하다. R2DBC PostgreSQL은 존재하지만 reactive end-to-end 요구가 없고 pgvector binding 전략을 별도로 정해야 한다. [WEB-5] [WEB-13] |
| PostgreSQL driver | pgJDBC | R2DBC driver는 후속 후보 | pgJDBC는 PostgreSQL native protocol을 사용하는 공식 JDBC driver이며 2026-04-28 기준 current release 42.7.11이 공개되어 있다. Spring Boot BOM 관리 버전을 우선하고 보안 패치는 별도 확인한다. [WEB-10] |
| Schema migration | Flyway + PostgreSQL database module | `schema.sql`, Hibernate DDL auto는 비선택 | Spring Boot DB initialization 문서에 따라 migration은 Flyway 한 경로로 관리하고 PostgreSQL database-specific module을 포함한다. [WEB-4] |
| Vector search | PostgreSQL + pgvector exact cosine search | ANN index는 후속 migration | pgvector는 vector type, distance operator, HNSW/IVFFlat을 제공하지만 MVP는 정확성과 단순성을 위해 exact search를 기본값으로 둔다. [WEB-1] |
| Local environment | Docker Compose로 PostgreSQL + pgvector만 실행 | app containerization은 후속 | Compose는 services/networks/volumes를 YAML로 관리하고 단일 명령으로 서비스를 시작할 수 있다. MVP에서는 DB 재현성에만 사용한다. [WEB-11] |
| Integration test | Testcontainers PostgreSQL module + pgvector compatible image | mock-only DB test는 비선택 | migration, pgvector extension, SQL, vector search는 실제 PostgreSQL 호환 컨테이너로 검증한다. [WEB-2] [WEB-3] |
| 문서 언어 | 한글 | 영문-only 문서는 비선택 | 사용자가 프로젝트 문서 작성 언어를 한글로 확정했다. REST contract와 local workflow 문서는 한글로 작성하되 API path, JSON field, class/package 이름은 원문 표기를 유지한다. [USER-8] |
| Security | custom servlet `Filter` 또는 `HandlerInterceptor` local token/API key 인증 | Spring Security 전체는 MVP 비선택 | MVP는 단일 사용자 로컬 개발용이며 OAuth/RBAC/session login 요구가 없다. Spring Security는 다중 사용자/권한 모델이 생기는 후속 단계에서 새 Technology Reconnaissance로 재검토한다. [USER-10] |

## 5. 엣지 케이스

field: `edge_cases`

- 같은 sourcePath/artifactType에 같은 contentHash가 반복 저장되면 기존 snapshot을 반환한다.
- 같은 sourcePath/artifactType에 다른 contentHash가 저장되면 새 version으로 보존한다.
- 서로 다른 sourcePath에 같은 contentHash가 나타나면 content blob dedup은 가능하지만 document relationship은 별도로 유지한다.
- embedding이 null인 chunk는 keyword 검색에만 포함한다.
- keyword 검색의 빈 query 또는 whitespace-only query는 400 validation error로 처리한다. 단순 필터 조회는 `GET /api/artifacts`가 담당한다.
- keyword 검색 필터는 projectId, iterationId, artifactType, sourcePath, taskId, runId와 AND 조건으로 결합한다.
- keyword 검색 ranking은 backend-specific score로만 노출하고 API 안정 계약으로 고정하지 않는다. MVP에서는 정확한 구문/부분 문자열 매칭을 우선하고, 동률이면 최신 snapshotVersion/timestamp와 chunkIndex를 tie-breaker로 사용한다.
- 후속 Elasticsearch/OpenSearch adapter를 추가할 때 PostgreSQL MVP의 `ILIKE` 구현 세부를 API 계약으로 노출하지 않는다. BM25, analyzer, phrase search, highlight/snippet, faceted filtering은 adapter capability로 확장한다.
- query embedding의 model/dimension/version과 stored embedding set이 맞지 않으면 400 validation error를 반환하거나 명시적으로 같은 embedding set으로 필터링한다.
- 새 embedding 모델이나 version으로 전환할 때는 기존 embedding을 덮어쓰지 않고 새 `embedding_sets` row와 `chunk_embeddings` row를 추가 저장해 점진 전환과 비교 평가가 가능해야 한다.
- 같은 chunkId와 embeddingSetId에 같은 embeddingHash가 반복 저장되면 기존 `chunk_embeddings` row를 반환한다. 같은 chunkId와 embeddingSetId에 다른 embeddingHash 또는 vector가 들어오면 명시적 overwrite/update 정책 없이 conflict로 처리한다.
- MVP의 pgvector storageType은 `vector`이고 embeddingDimension은 pgvector `vector` 한계에 맞춰 2000 이하로 제한한다. 2000차원을 초과하는 모델은 후속 `halfvec` 또는 별도 storage migration 결정으로 미룬다.
- 같은 logical scope에서 동일한 source*Id가 다른 canonical entity로 해석되려 하면 conflict로 처리한다.
- source*Id 없이 들어온 artifact는 서버가 canonical ID를 부여하지만, 이후 로컬 파일과 재동기화하려면 sourcePath, artifactType, contentHash, snapshotVersion 조합으로만 매칭된다.
- local artifact와 server latest snapshot이 모두 변경된 경우 서버는 자동 병합하지 않고, GUI/CLI가 conflict를 표시하고 사용자가 선택할 수 있도록 양쪽 snapshot metadata를 반환한다.
- task graph가 없는 task 저장 요청은 400으로 거부하거나 explicit standalone flag가 있을 때만 허용한다.
- 검색 backend를 추가할 때 PostgreSQL 원장 데이터와 외부 검색 인덱스 사이의 지연/불일치가 생길 수 있으므로, MVP에서는 PostgreSQL/pgvector 단일 source of search truth로 유지한다.
- application port가 backend별 기능 차이를 숨기지 못하는 경우에는 공통 계약을 작게 유지하고 backend-specific tuning은 adapter config로 제한한다.
- 로컬 파일 삭제는 MVP에서 서버 삭제로 자동 반영하지 않고 tombstone API는 후속으로 둔다.
- sourcePath는 OS별 separator 차이를 줄이기 위해 normalizedPath와 rawSourcePath를 함께 보존한다.

## 6. 검증

field: `verification`

- schema migration test: Testcontainers pgvector PostgreSQL에서 Flyway migration, `vector` extension, 주요 unique index를 검증한다.
- application use case test: core가 mock outbound port만으로 content hash idempotency, snapshot version, relation validation, no-AI 규칙을 검증한다.
- source reference resolution test: source*Id가 canonical server ID로 안정적으로 매핑되고 conflict를 감지하는지 검증한다.
- adapter integration test: PostgreSQL/PgVector adapter에서 project/iteration/document/taskGraph/task/run/chunk upsert와 content hash idempotency를 검증한다.
- port contract test: `KeywordSearchPort`와 `VectorSearchPort`가 metadata filter, empty keyword query, empty embedding, embedding set mismatch, dimension mismatch, backend-specific score contract를 만족하는지 검증한다.
- keyword search semantics test: `document_chunks.content` 중심 검색, `documents.content`/`sourcePath`/`artifactType` 보조 검색, case-insensitive match, 필터 AND 결합, 빈 `q` validation, 반환 metadata, tie-breaker 순서를 검증한다.
- embedding set persistence test: 같은 chunkHash 반복 저장은 chunk를 중복 생성하지 않고, 같은 chunk에 여러 embedding set을 저장해도 기존 embedding을 덮어쓰지 않으며, 같은 chunkId + embeddingSetId 중복 저장은 idempotent하게 처리되는지 검증한다.
- API integration test: local token 인증, validation error, filter 조회, keyword 검색, vector 검색을 검증한다.
- sync metadata test: API 응답이 P2A GUI/CLI의 status/diff/push/pull/conflict/history 구현에 필요한 canonical ID, source reference, contentHash, snapshotVersion, lineage metadata를 포함하는지 검증한다.
- Docker Compose smoke test: 로컬 DB 시작과 서버 health check를 검증한다.
- negative test: embedding model/dimension/version mismatch, 누락된 relation id, 잘못된 artifactType, 중복 logical key 처리.
- architecture test: application core가 Spring MVC, JDBC, PostgreSQL, pgvector adapter package를 import하지 않는지 검증한다.
- no-server-UI guarantee: 서버 코드에 내장 웹 UI, server-rendered page, static frontend bundle이 없음을 검증한다.
- no-AI guarantee: 서버 코드와 테스트에 외부 AI API 호출 경로가 없음을 검증한다.
- bootstrap verification: Spring Boot 4.1.0, Java 25 LTS toolchain/runtime, Gradle 9.1.0 이상 9.x, Gradle Kotlin DSL 파일(`build.gradle.kts`, `settings.gradle.kts`), Spring WebMVC/JDBC 의존성 조합을 확인하고 WebFlux/R2DBC/JPA/Hibernate/Spring Security가 MVP dependency graph에 들어오지 않았는지 검증한다.
