# P2A 로컬 산출물 저장 서버 - Gate A Intake

## 1. 이해

요구사항의 핵심은 P2A의 로컬 md/json 산출물 구조를 유지하면서, 별도 Kotlin/Spring Boot REST 서버가 그 산출물을 PostgreSQL/pgvector에 동기화해 관계와 검색 기반을 보존하는 것입니다. 명확한 범위는 프로젝트, iteration, 문서 스냅샷, task graph, task, run, chunk 저장과 content hash 기반 중복 방지, metadata 조회, keyword 검색, vector 검색 준비입니다.

명확한 비범위도 있습니다. 서버는 P2A 하네스를 실행하지 않고, AI agent도 실행하지 않으며, embedding 생성용 AI API를 직접 호출하지 않습니다. 자동 개선 적용은 MVP 범위가 아니고, 자기고도화도 후속 단계에서 proposal 생성 정도까지만 고려합니다.

아직 확정되지 않은 세부는 외부 ID와 서버 생성 ID의 경계, embedding dimension/index 최적화 정책, 같은 sourcePath의 변경 이력을 version으로 저장하는 세부 정책입니다. 이들은 구현 세부에 영향을 주지만 MVP 범위를 바꿀 정도의 Gate A 차단 결정은 아니므로 소프트 질문으로 처리합니다.

## 2. 가정

### A-1

- statement: 서버는 로컬 파일을 대체하지 않고, 파일 산출물을 동기화 및 검색하기 위한 보조 저장소로만 동작한다.
- risk: low
- confirmation_needed: false
- reasoning: 사용자가 로컬 파일을 원본으로 명시했기 때문에 제품 경계가 분명합니다. 모든 저장 API는 원본 파일 경로와 content hash를 보존해야 합니다.

### A-2

- statement: MVP는 하나의 Spring Boot REST 애플리케이션과 PostgreSQL 데이터베이스로 구성되며 별도 UI, queue, worker, agent runtime은 포함하지 않는다.
- risk: low
- confirmation_needed: false
- reasoning: 초기 단일 사용자 로컬 개발용이고 CLI/GUI는 나중에 연동되므로, 별도 비동기 실행 인프라를 넣으면 MVP 목적보다 커집니다.

### A-3

- statement: P2A CLI/GUI는 가능한 경우 projectId, iterationId, taskId, runId 같은 안정적인 외부 식별자를 전달하고, 서버는 누락된 식별자에 대해서만 자체 UUID를 생성한다.
- risk: medium
- confirmation_needed: true
- reasoning: P2A 파일과 저장 서버 사이의 재동기화 안정성에 영향을 줍니다. 기본 권장안은 외부 ID 우선, 서버 ID 보조입니다.

### A-4

- statement: embedding 모델과 차원은 초기에는 고정하지 않고, chunk별 embedding_model, embedding_dimension, embedding 값을 함께 저장한다.
- risk: medium
- confirmation_needed: true
- reasoning: 서버가 embedding을 생성하지 않으므로 모델과 차원을 서버가 선제 결정하면 P2A CLI/GUI 연동을 불필요하게 제한합니다.

### A-5

- statement: keyword 검색은 MVP에서 PostgreSQL 기반 검색으로 충분하며, 별도 검색 엔진은 도입하지 않는다.
- risk: low
- confirmation_needed: true
- reasoning: 로컬 단일 사용자 MVP에서는 운영 복잡도를 낮추는 편이 낫습니다. 이후 RAG 품질 평가가 생기면 별도 검색 엔진을 재검토할 수 있습니다.

## 3. 결정

Gate A에서 사용자 결정을 요구하는 항목은 없습니다.

REST 서버, PostgreSQL, pgvector, 로컬 파일 원본 원칙, agent 미실행, AI API 미호출, 단일 사용자 로컬 개발, API key/local token 인증, Docker Compose, Testcontainers는 모두 사용자가 이미 명시했습니다. 따라서 intake_json.needs_user_decision은 비어 있고 status는 `ready_for_spec`입니다.

## 4. 소프트 질문

### CQ-1

- question: P2A CLI/GUI가 모든 projectId, iterationId, taskId, runId를 외부에서 안정적으로 전달할 예정인가, 아니면 서버 생성 ID도 공식 계약으로 열어둘 것인가?
- handling: Gate B에서는 외부 ID 우선, 서버 생성 UUID 보조로 가정합니다.

### CQ-2

- question: 초기 vector 검색의 기본 distance metric은 cosine으로 두고, embedding 모델과 차원별 인덱스 최적화는 후속 단계로 미뤄도 되는가?
- handling: Gate B에서는 cosine 기본값과 exact search 우선 정책으로 가정합니다.

### CQ-3

- question: 같은 sourcePath와 artifactType에 다른 content hash가 들어오면 기존 레코드를 덮어쓰지 않고 새 snapshot/version으로 저장하는 정책이면 충분한가?
- handling: Gate B에서는 새 snapshot/version 저장 정책으로 가정합니다.

## 5. 다음

status: `ready_for_spec`

다음 단계는 Gate B product spec과 implementation plan을 검토하는 것입니다. Gate B 산출물은 draft로 저장되며, 명시 승인 후에만 Gate C task graph를 생성합니다.
