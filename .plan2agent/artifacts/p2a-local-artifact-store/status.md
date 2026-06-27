# P2A 로컬 산출물 저장 서버 - 상태

## 1. 진행 상태

Progress: [A complete] -> [B approved] -> [C complete] -> [D complete:passed]

Gate A intake는 사용자 요구사항을 기반으로 완료되었습니다. Gate B product spec과 implementation plan은 사용자가 명시 승인했으며, Gate C task graph를 생성했습니다. Gate D review를 재실행했고 blocking issue 없이 통과했습니다.

## 2. 게이트별

### Gate A

- 상태: complete
- 산출물: `gate-a-intake/intake.json`, `gate-a-intake/intake.md`
- 근거: `intake.status`는 `ready_for_spec`이며 `needs_user_decision`은 비어 있습니다.

### Gate B

- 상태: approved
- 산출물: `gate-b-spec/product-spec.md`, `gate-b-spec/implementation-plan.md`, `gate-b-spec/spec.json`
- 근거: `spec.approval`은 `approved`이고 `spec.open_decisions`는 비어 있습니다.
- 최신 변경: 레이어드 아키텍처 대신 pragmatic hexagonal architecture를 적용하고, PostgreSQL/pgvector는 outbound adapter로 격리했습니다.

#### Gate B approval audit

Approved by: user
Approved at: 2026-06-26
Approved artifacts: `gate-b-spec/product-spec.md`, `gate-b-spec/implementation-plan.md`, `gate-b-spec/spec.json`
Approval note: 사용자가 "게이트 b 확인했고 확정하자"라고 명시해 Gate B 산출물을 승인했습니다.

### Gate C

- 상태: complete
- 산출물: `gate-c-task-graph/task-graph.json`
- 근거: Gate B 승인 후 schema-compatible task graph를 생성했고, oversized task였던 application use case, REST adapter, integration test 항목을 17개 task로 재분리했습니다.

### Gate D

- 상태: complete, passed
- 산출물: `gate-d-review/review-report.md`, `gate-d-review/review.json`
- 근거: Gate D review를 재실행했으며 `review.blocking_issues`는 빈 배열입니다. Technology Reconnaissance, chunk embedding-set 저장 모델, RAG용 keyword search semantics, oversized task split이 Gate B/C 산출물에 반영된 것을 확인했습니다.

## 3. 결정 / 질문 상태

- 열린 needs_user_decision: 없음
- CQ-1: 사용자가 로컬 생성 주도 + 서버 canonical ID/version lineage 주도 하이브리드 모델로 확정했습니다.
- CQ-2: 사용자가 cosine exact search 기본, embeddingModel/embeddingDimension/embeddingVersion 저장, 같은 embedding set 내 검색, ANN index 후속 추가 전략으로 확정했습니다.
- CQ-3: 사용자가 같은 sourcePath/artifactType의 contentHash 변경 시 기존 레코드를 덮어쓰지 않고 새 snapshotVersion으로 저장하는 정책을 확정했습니다.
- A-2: 서버는 headless REST service로 유지하고, P2A GUI/CLI가 git-like sync client로 status/diff/push/pull/conflict/history 화면을 담당하도록 확정했습니다.
- A-5: keyword 검색은 RAG/회고용 deterministic lexical retrieval로 정의하고 MVP에서는 PostgreSQL 기반 adapter로 제공하며, OpenSearch/Elasticsearch는 후속 문서 검색 adapter 후보로 둡니다.
- 아키텍처 방향: 사용자의 추가 요청에 따라 헥사고날 아키텍처로 수정했습니다.
- Web stack / DB access: 사용자가 추천안을 승인해 Spring Boot 4.1.0 stable + Spring WebMVC + JDBC/JdbcClient를 MVP 기준으로 확정했습니다. WebFlux/R2DBC는 후속 adapter 후보로만 둡니다.
- Java baseline: 사용자가 Java 17 대신 더 높은 LTS를 요청해 Java 25 LTS를 runtime/toolchain 기준으로 확정했습니다. 이에 따라 Gradle 기준도 Java 25를 지원하는 9.1.0 이상 9.x로 올렸습니다.
- Build/dependency policy: 사용자가 Gradle Kotlin DSL을 확정했습니다. 빌드 파일은 `build.gradle.kts`, `settings.gradle.kts`를 사용하고, Spring Boot Gradle plugin은 Spring Boot runtime과 같은 stable compatible 버전인 4.1.0을 사용합니다. dependency version은 Spring Boot managed versions/BOM을 우선합니다.
- Security: 사용자가 Spring Security를 MVP에서 제외하기로 확정했습니다. 인증은 custom servlet Filter 또는 HandlerInterceptor 기반 local token/API key 검증으로 구현합니다.
- Embedding storage: 사용자가 `document_chunks`, `embedding_sets`, `chunk_embeddings` 3-table 모델을 확정했습니다. chunk row와 embedding row를 분리해 chunkHash idempotency와 여러 embedding set 보존 요구를 동시에 만족시킵니다.
- Keyword search semantics: 사용자가 추천안을 승인해 keyword 검색을 RAG 후보 검색의 정확 매칭 채널로 확정했습니다. `document_chunks.content` 중심으로 검색하고 `documents.content`, `sourcePath`, `artifactType`을 보조 대상으로 두며, backend-specific score와 안정적인 chunk/document/source metadata를 반환합니다.
- 문서 언어: 사용자가 프로젝트 문서, REST contract, local workflow 문서를 한글로 작성하도록 확정했습니다.
- Gate B 승인: 사용자가 2026-06-26에 승인했습니다.

## 4. 다음

Gate D review가 통과했습니다. 다음은 split된 Gate C task graph 기준으로 구현을 시작합니다.

## 5. 변경 이력

- 2026-06-26: Gate A intake를 작성하고 `ready_for_spec` 상태로 저장했습니다.
- 2026-06-26: Gate B product spec, implementation plan, spec.json을 draft로 저장했습니다.
- 2026-06-26: Gate B 산출물을 헥사고날 아키텍처와 검색 backend 교체 가능 구조로 수정했습니다.
- 2026-06-26: 사용자가 Gate B 산출물을 승인해 `spec.approval`을 `approved`로 변경했습니다.
- 2026-06-26: Gate C task graph를 생성했습니다.
- 2026-06-26: CQ-1을 사용자 결정으로 확정하고 canonical server ID와 source reference 병행 모델을 Gate B/Gate C 산출물에 반영했습니다.
- 2026-06-26: A-2를 사용자 결정으로 구체화해 서버 내장 웹 UI를 제외하고 P2A GUI/CLI sync client 방향을 Gate A/B/C 산출물에 반영했습니다.
- 2026-06-26: CQ-2를 사용자 결정으로 확정하고 embedding set 기반 vector search 전략을 Gate A/B/C 산출물에 반영했습니다.
- 2026-06-26: A-3을 CQ-1 최종 결정과 맞춰 canonical server ID와 source reference 병행 모델로 정리했습니다.
- 2026-06-26: A-4를 CQ-2 최종 결정과 맞춰 embedding set 기반 저장/검색 모델로 정리했습니다.
- 2026-06-26: A-5를 Gate B 최종 설계와 맞춰 PostgreSQL keyword search 기본, 외부 검색 엔진 후속 adapter 후보로 정리했습니다.
- 2026-06-26: CQ-3을 사용자 결정으로 확정하고 document snapshot versioning 정책을 Gate A/B/C 산출물에 반영했습니다.
- 2026-06-27: Gate D review를 완료하고 blocker 2건을 기록했습니다.
- 2026-06-27: 사용자가 Spring Boot 4.1.0 stable, Spring WebMVC + JDBC/JdbcClient, 한글 문서화 방침을 확정해 Gate B/C 산출물에 반영했습니다.
- 2026-06-27: 사용자가 Java 17 대신 Java 25 LTS를 runtime/toolchain 기준으로 확정해 Gate B/C 산출물에 반영했습니다.
- 2026-06-27: 사용자가 Gradle Kotlin DSL, Spring Boot Gradle plugin 4.1.0, Spring Boot managed versions 우선 정책, Spring Security MVP 제외를 확정해 Gate B/C 산출물에 반영했습니다.
- 2026-06-27: 사용자가 chunk embedding-set 3-table 저장 모델을 확정해 Gate B/C 산출물에 반영했습니다.
- 2026-06-27: 사용자가 RAG/회고용 keyword search semantics 추천안을 승인해 Gate B/C 산출물에 반영했습니다.
- 2026-06-27: Gate D review를 재실행했고 blocking issue 없이 통과했습니다.
- 2026-06-27: 사용자가 broad task 분리를 요청해 기존 task-6, task-8, task-11을 write/query, REST write/query, storage/search integration test 단위로 나누고 Gate D review를 다시 통과시켰습니다.
