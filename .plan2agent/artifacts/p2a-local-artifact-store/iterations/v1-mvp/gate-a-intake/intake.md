# P2A 로컬 산출물 저장 서버 - Gate A Intake

## 1. 이해

요구사항의 핵심은 P2A의 로컬 md/json 산출물 구조를 유지하면서, 별도 Kotlin/Spring Boot REST 서버가 그 산출물을 PostgreSQL/pgvector에 동기화해 관계와 검색 기반을 보존하는 것입니다. 명확한 범위는 프로젝트, iteration, 문서 스냅샷, task graph, task, run, chunk 저장과 content hash 기반 중복 방지, metadata 조회, keyword 검색, vector 검색 준비입니다.

명확한 비범위도 있습니다. 서버는 P2A 하네스를 실행하지 않고, AI agent도 실행하지 않으며, embedding 생성용 AI API를 직접 호출하지 않습니다. 자동 개선 적용은 MVP 범위가 아니고, 자기고도화도 후속 단계에서 proposal 생성 정도까지만 고려합니다.

초기에는 외부 ID와 서버 생성 ID의 경계, embedding dimension/index 최적화 정책, 같은 sourcePath의 변경 이력을 version으로 저장하는 세부 정책이 확정되지 않았습니다. 이후 사용자 결정으로 ID 정책은 canonical server ID와 source reference 병행 모델로 확정되었고, vector 검색은 embedding set 기반 cosine exact search로 확정되었습니다. 같은 sourcePath의 변경 이력 정책도 새 snapshot/version 저장으로 확정되었습니다.

## 2. 확정된 전제

### A-1

- statement: 로컬 파일은 content source of truth로 유지하고, 서버는 동기화된 artifact의 canonical ID, version lineage, 관계, 검색 index를 관리하는 headless metadata/search store로 동작한다.
- risk: low
- confirmation_needed: false
- reasoning: 사용자가 로컬 파일을 원본으로 명시했고, 이후 결정에서 서버가 canonical ID, version lineage, 관계, 검색 index를 관리하기로 확정했습니다. 모든 저장 API는 원본 파일 경로와 content hash를 보존해야 합니다.

### A-2

- statement: MVP 서버는 하나의 headless Spring Boot REST 애플리케이션과 PostgreSQL 데이터베이스로 구성하며, 서버 내장 웹 UI, queue, worker, agent runtime은 포함하지 않는다. 단, P2A GUI/CLI는 후속 공식 클라이언트로 발전해 git client처럼 local artifact working tree와 server canonical store의 status/diff/push/pull/conflict/history 기능을 제공한다.
- risk: low
- confirmation_needed: false
- reasoning: 초기 단일 사용자 로컬 개발용 서버는 저장/검색/lineage API에 집중하고, 화면과 동기화 UX는 기존 P2A GUI/CLI 쪽에서 맡기는 편이 제품 경계를 작게 유지합니다.

### A-3

- statement: 서버는 모든 artifact에 canonical server ID를 부여하고, P2A CLI/GUI가 전달하는 projectId, iterationId, taskId, runId 같은 로컬/P2A 식별자는 sourceProjectId, sourceIterationId, sourceTaskId, sourceRunId 같은 source reference로 보존한다.
- risk: medium
- confirmation_needed: false
- reasoning: 사용자가 CQ-1에서 로컬 생성 주도 + 서버 canonical ID/version lineage 주도 하이브리드 모델을 확정했습니다. 서버 내부 관계, 검색, 회고, 자기고도화 metadata는 canonical ID를 기준으로 하고, 로컬 파일과 재연결할 때 source reference를 사용합니다.

### A-4

- statement: embedding 모델과 차원은 초기에는 고정하지 않고, chunk별 embeddingModel, embeddingDimension, embeddingVersion, embedding 값을 함께 저장한다.
- risk: medium
- confirmation_needed: false
- reasoning: 사용자가 CQ-2에서 cosine exact search 기본값, embeddingModel/embeddingDimension/embeddingVersion 저장, 같은 embedding set 내 검색, ANN index 후속 추가 전략을 확정했습니다. 서버는 embedding을 생성하지 않고 외부 주입값을 저장하므로, 모델 전환과 재색인을 위해 embedding set 단위 병행 저장을 지원합니다.

### A-5

- statement: keyword 검색은 MVP에서 PostgreSQL 기반 검색으로 제공하고, OpenSearch/Elasticsearch 같은 별도 검색 엔진은 후속 adapter 후보로만 둔다.
- risk: low
- confirmation_needed: false
- reasoning: Gate B에서 PostgreSQL/pgvector를 MVP 검색 source of truth로 두고, 검색 backend는 `KeywordSearchPort`/`VectorSearchPort` adapter로 교체 가능하게 설계하기로 확정했습니다. 로컬 단일 사용자 MVP에서는 운영 복잡도를 낮추고, 이후 RAG 품질 평가가 생기면 별도 검색 엔진을 adapter로 추가합니다.

## 3. 결정

Gate A에서 사용자 결정을 요구하는 항목은 없습니다.

REST 서버, PostgreSQL, pgvector, 로컬 파일 원본 원칙, agent 미실행, AI API 미호출, 단일 사용자 로컬 개발, API key/local token 인증, Docker Compose, Testcontainers는 모두 사용자가 이미 명시했습니다. 따라서 intake_json.needs_user_decision은 비어 있고 status는 `ready_for_spec`입니다.

## 4. 소프트 질문

### CQ-1

- question: P2A CLI/GUI가 모든 projectId, iterationId, taskId, runId를 외부에서 안정적으로 전달할 예정인가, 아니면 서버 생성 ID도 공식 계약으로 열어둘 것인가?
- handling: 사용자 결정으로 canonical server ID와 source reference를 병행합니다. 서버 내부 관계와 lineage는 canonical ID를 기준으로 하고, 로컬/P2A ID는 source*Id로 보존합니다.

### CQ-2

- question: 초기 vector 검색의 기본 distance metric은 cosine으로 두고 exact search를 먼저 제공하며, embedding 모델/차원/버전별 인덱스 최적화는 후속 단계로 미뤄도 되는가?
- handling: Gate B에서는 cosine exact search를 기본으로 확정하고, 검색은 같은 embeddingModel/embeddingDimension/embeddingVersion 범위로 제한합니다. HNSW/IVFFlat 같은 ANN index는 모델과 차원이 안정된 뒤 모델/차원별 partial 또는 expression index로 추가합니다.

### CQ-3

- question: 같은 sourcePath와 artifactType에 다른 content hash가 들어오면 기존 레코드를 덮어쓰지 않고 새 snapshot/version으로 저장하는 정책이면 충분한가?
- handling: 사용자 결정으로 같은 sourcePath와 artifactType에 다른 contentHash가 들어오면 기존 레코드를 덮어쓰지 않고 새 snapshotVersion으로 저장합니다.

## 5. 다음

status: `ready_for_spec`

Gate B product spec과 implementation plan은 사용자 승인으로 `approved`가 되었고, Gate C task graph도 생성되었습니다. 현재 다음 단계는 Gate D review입니다.
