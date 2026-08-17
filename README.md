# Plan2Agent Memory Server

Plan2Agent Memory Server는 로컬 P2A 산출물을 관계형으로 저장하고 검색하기 위한 headless REST service입니다. 로컬 파일이 원본(source of truth)이고, 이 서버는 동기화된 artifact의 canonical ID, lineage, hash, relation, keyword/vector 검색 인덱스를 제공하는 보조 저장소입니다.

서버는 P2A harness나 agent를 실행하지 않습니다. Chunk 저장과 query text 검색에서 필요한 embedding 생성·작업 관리는 서버가 소유하며, 클라이언트는 embedding 값이나 embedding model metadata를 전송하지 않습니다.

## 로컬 실행

### 요구 사항

- Java 21
- Gradle wrapper
- Node.js 22.23.1과 Corepack (dashboard 개발·browser 검증)
- Lima와 Docker Compose v2 (`docker-compose` 명령)
- Compose runtime용 ONNX model directory (`model.onnx`, `tokenizer.json`)

Lima를 처음 쓰는 macOS 환경에서는 Docker daemon을 먼저 시작하고 shell이 Lima socket을 보도록 설정합니다. 이 repository의 Compose 명령은 Docker Desktop plugin이 아닌 `docker-compose`를 사용합니다.

```bash
limactl start
export DOCKER_HOST="unix://$HOME/.lima/default/sock/docker.sock"
docker-compose version
```

### Dashboard 포함 Compose reference deployment

이 reference deployment는 한 명의 로컬 운영자만을 위한 구성입니다. PostgreSQL, backend, dashboard의 published port는 모두 `127.0.0.1`로만 bind됩니다. dashboard의 Node BFF만 backend의 `X-P2A-Local-Token`을 보유·주입하며, browser bundle·storage·request에는 token을 넣지 않습니다. LAN, public reverse proxy, port forwarding 또는 다중 사용자 접근이 필요해지면 먼저 인증·권한 경계를 별도 설계해야 합니다.

`.env`는 git에 포함하지 않습니다. 개인 경로와 실제 secret을 넣어야 하므로 example을 복사한 후 직접 값을 채웁니다.

```bash
cp .env.example .env
# .env의 P2A_DB_PASSWORD, P2A_LOCAL_TOKEN, P2A_MODEL_DIR를 실제 값으로 변경
set -a
source .env
set +a
```

`P2A_MODEL_DIR`에는 정확히 `model.onnx`와 `tokenizer.json`이 있어야 합니다. Compose는 이 directory만 `/opt/p2a/model:ro`로 mount하고, container 내부 URI `file:///opt/p2a/model/model.onnx`, `file:///opt/p2a/model/tokenizer.json`만 사용합니다. model·tokenizer·host absolute path·token은 image나 git에 복사하지 않습니다.

```bash
shasum -a 256 "$P2A_MODEL_DIR/model.onnx" "$P2A_MODEL_DIR/tokenizer.json"
docker-compose --env-file .env config --quiet
docker-compose --env-file .env up --build --detach --wait
curl --fail http://127.0.0.1:8080/actuator/health
curl --fail http://127.0.0.1:4173/healthz
```

정상 순서는 `postgres healthy → backend healthy → dashboard healthy`입니다. backend의 `DJL_OFFLINE=true`는 image에 prefetch된 native cache를 사용하며 runtime download를 금지합니다. provider 상태는 별도로 확인합니다.

```bash
curl http://127.0.0.1:8080/actuator/metrics/p2a.embedding.provider.state
```

정상 중지는 volume을 유지합니다. 재기동 뒤에도 data가 남는지 확인할 때는 같은 명령을 다시 사용합니다.

```bash
docker-compose --env-file .env down
docker-compose --env-file .env up --detach --wait
```

`docker-compose --env-file .env down --volumes`는 이 deployment의 PostgreSQL data를 삭제하는 의도적인 reset입니다. 백업 없이 실행하지 마세요.

#### Local helper scripts

매번 Lima socket, `.env`, model files, Compose health를 확인할 필요가 없도록 helper script를 제공합니다.

```bash
./scripts/local-up.sh
./scripts/local-down.sh
```

`local-up.sh`와 `local-down.sh`는 매번 Lima `default` instance를 확인·기동하고, 기존 SSH Docker context를 해제한 뒤 Lima Unix socket만 사용합니다. `local-up.sh`는 `.env`의 필수 값과 `model.onnx`·`tokenizer.json`을 확인한 뒤 모든 서비스를 기동합니다. `local-down.sh`는 `docker-compose down --remove-orphans`만 사용하며 `--volumes`를 절대 전달하지 않으므로 PostgreSQL named volume과 저장 data를 보존합니다. 두 script 모두 Lima 자체는 중지하지 않습니다.

### PostgreSQL 시작

backend만 host에서 개발할 때도 먼저 `.env`를 export한 뒤 PostgreSQL service만 올릴 수 있습니다. `compose.yaml`은 정확히 `pgvector/pgvector:0.8.5-pg17-bookworm@sha256:d2ef61f42ef767baa5a1475393303cc235bcd92febd9d7014eddb48b41f3bad0` 이미지를 사용합니다. digest를 생략하거나 tag만 바꾸지 마세요.

```bash
docker-compose --env-file .env up --detach postgres
```

DB 이름과 user는 고정이고 password와 host port는 `.env`가 소유합니다.

- DB: `p2a_artifact_store`
- User: `p2a`
- Password: `P2A_DB_PASSWORD`
- Port: `P2A_POSTGRES_HOST_PORT` (기본 `5432`, loopback only)
- JDBC URL: `jdbc:postgresql://127.0.0.1:${P2A_POSTGRES_HOST_PORT:-5432}/p2a_artifact_store`

DB schema의 유일한 변경 경로는 `src/main/resources/db/migration/V*.sql` Flyway migration입니다. 빈 DB에는 현재 `V1__create_artifact_store_schema.sql`, `V2__add_server_global_embedding_profiles.sql`, `V3__add_embedding_jobs.sql`의 V1~V3 chain이 순서대로 적용됩니다. 같은 version 번호가 있더라도 현재 V1 checksum/schema를 validate하지 못하는 과거 DB history는 호환되는 것으로 가정하지 마세요. 필요한 data를 백업하고 reset한 뒤 현재 server가 V1~V3를 적용하게 해야 합니다. JPA/Hibernate DDL 생성과 Spring `schema.sql` 초기화는 비활성화되어 있으며, 환경변수나 profile이 `create`, `update`, `create-drop`, 표준 JPA schema generation 또는 `spring.sql.init.mode`를 활성화하면 애플리케이션은 persistence bean 초기화 전에 기동을 중단합니다. 이후 테이블의 `CREATE`, `ALTER`, `DROP`은 새 versioned SQL migration으로만 반영합니다.

### 현재 Flyway V1~V3 chain 전환을 위한 전체 초기화

이 절차는 Memory DB의 테이블, Flyway history, 검색 인덱스와 저장 데이터를 모두 삭제합니다. 기존 서버와 쓰기 요청을 먼저 중지하고, 필요한 데이터는 백업한 뒤 실행합니다. 초기화 후에는 이 repository의 최신 서버만 시작해야 합니다. `.env`를 먼저 만들고 export한 상태여야 합니다.

로컬 Docker Compose 환경은 named volume을 제거하면 됩니다.

```bash
docker-compose --env-file .env down --volumes
docker-compose --env-file .env up --detach postgres
./gradlew bootRun
```

Cloud SQL처럼 PostgreSQL을 별도 운영하는 환경에서는 `postgres` 관리 계정으로 다음 SQL을 실행합니다.

```sql
DROP SCHEMA public CASCADE;
CREATE SCHEMA public AUTHORIZATION p2a;

CREATE EXTENSION IF NOT EXISTS vector;

GRANT CONNECT, CREATE
ON DATABASE p2a_artifact_store
TO p2a;

GRANT USAGE, CREATE
ON SCHEMA public
TO p2a;
```

최신 서버가 정상 기동한 뒤 Flyway 적용 이력이 V1~V3의 성공한 세 건이고 최신 version이 `3`인지 확인합니다.

```sql
SELECT installed_rank, version, description, success
FROM flyway_schema_history
ORDER BY installed_rank;
```

결과에는 성공한 version `1`, `2`, `3` migration이 순서대로 있어야 하며, 최신 version은 `3`이어야 합니다.

### 애플리케이션 실행

기본값으로 실행:

```bash
./gradlew bootRun
```

환경 변수로 DB와 인증을 지정할 수 있습니다.

```bash
P2A_DB_URL=jdbc:postgresql://localhost:5432/p2a_artifact_store \
P2A_DB_USERNAME=p2a \
P2A_DB_PASSWORD=replace-with-a-local-db-password \
P2A_LOCAL_TOKEN=replace-with-a-long-random-local-token \
./gradlew bootRun
```

`P2A_LOCAL_TOKEN`이 비어 있으면 `/api/**`도 인증 없이 열립니다. 값이 있으면 `/api/health`, `/api/embedding-jobs/**`, `/actuator/health`, `/actuator/metrics`를 제외한 `/api/**` 요청에 `X-P2A-Local-Token` header가 필요합니다. Header 이름은 `P2A_LOCAL_TOKEN_HEADER`로 바꿀 수 있습니다.

### 로컬 embedding provider 운영

기본값은 `P2A_EMBEDDING_PROVIDER=none`입니다. 이 모드에서는 ONNX 모델을 열거나 내려받지 않고 서버가 기동합니다. artifact 저장, keyword search, durable job 관리는 계속 가능하지만 semantic/hybrid search는 `503 embedding_provider_not_configured`을 반환합니다. 이미 만들어진 embedding job은 provider가 준비될 때까지 pending 상태로 남습니다.

`P2A_EMBEDDING_PROVIDER=transformers`는 operator가 제공한 두 개의 로컬 `file:` URI를 사용합니다. 애플리케이션은 ready event 뒤 백그라운드에서 artifact byte checksum을 확인하고 Spring AI/ONNX runtime을 warm-up합니다. 따라서 artifact가 없거나 checksum이 맞지 않거나 native model 초기화가 실패해도 기본 liveness/readiness가 DOWN으로 바뀌지는 않지만 provider는 `unavailable`이며 semantic/hybrid search는 `503 embedding_provider_unavailable`을 반환합니다.

고정 V2 artifact 계약은 다음과 같습니다. URI, provider 종류, worker tuning은 이 manifest/fingerprint의 일부가 아닙니다.

| 항목 | 고정값 |
| --- | --- |
| Profile fingerprint | `sha256:0bc822ab3bf2558f89838b6dd617e0f656098ceb809f9a6d33359e0e5889e3e5` |
| Model | `intfloat/multilingual-e5-small` |
| Revision | `d1d99a1efae6779390caba937d92c54b5bc70e51` |
| Model output / dimension / distance | `last_hidden_state` / `384` / cosine |
| ONNX model SHA-256 | `ca456c06b3a9505ddfd9131408916dd79290368331e7d76bb621f1cba6bc8665` |
| Tokenizer SHA-256 | `0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39` |
| Input contract | `passage: ` document prefix, `query: ` query prefix; 512-token attention-mask mean pooling and L2 normalization |

operator는 artifact의 실제 bytes를 먼저 검증하고, repository에 model을 넣거나 서버가 인터넷에서 model을 받도록 구성하지 않습니다. macOS에서는 다음처럼 확인할 수 있습니다.

```bash
shasum -a 256 /absolute/path/model.onnx
shasum -a 256 /absolute/path/tokenizer.json
```

checksum이 표의 값과 각각 일치하면 transformers provider를 시작합니다. 두 URI 모두 absolute local file URI여야 합니다.

```bash
SERVER_ADDRESS=127.0.0.1 \
P2A_EMBEDDING_PROVIDER=transformers \
P2A_EMBEDDING_MODEL_ARTIFACT_URI=file:///absolute/path/model.onnx \
P2A_EMBEDDING_TOKENIZER_ARTIFACT_URI=file:///absolute/path/tokenizer.json \
./gradlew bootRun
```

provider를 고치는 동안에는 `none`으로 기동해도 안전합니다. transformers artifact/configuration을 수정한 뒤 서버를 재시작하면 durable queue가 다시 사용됩니다. 매 worker poll마다 만료된 `running` lease는 `pending`으로 복구되고, provider가 ready가 된 뒤에만 job을 claim합니다. 따라서 provider가 `none`, `initializing`, 또는 `unavailable`인 동안에는 작업을 claim하거나 attempt를 소모하지 않습니다.

### ONNX opt-in 검증

실제 pinned ONNX artifact로 Spring AI의 tokenizer/pooling 결과를 독립 ONNX probe와 대조하려면 operator가 동일한 로컬 file URI를 제공해야 합니다.

```bash
P2A_ONNX_MODEL_URI=file:///absolute/path/model.onnx \
P2A_ONNX_TOKENIZER_URI=file:///absolute/path/tokenizer.json \
./gradlew onnxVerificationTest
```

이 task는 URI가 없거나 pinned checksum과 다르면 실패합니다. 일반 `./gradlew test`는 `onnx-verification` tag를 제외합니다. mandatory CI가 이를 포함하지 않는 이유는 검증 대상 artifact가 repository/CI에 포함되지 않은 operator-provided local file이고, CI가 model을 내려받거나 다른 bytes를 대체해서는 안 되기 때문입니다.

#### 첫 실행 네이티브 런타임 준비

`model.onnx`와 `tokenizer.json`은 위 URI에서만 읽으며 이 task가 내려받지 않습니다. 다만 Spring AI가 사용하는 DJL PyTorch engine은 비어 있는 플랫폼별 native-runtime cache에서 첫 model 초기화 시 `publish.djl.ai`의 native library를 내려받을 수 있습니다. Gradle의 `--offline`은 Gradle dependency resolution만 막으므로 이 애플리케이션 레벨 다운로드를 막지 않습니다.

따라서 네트워크가 허용된 동일 OS·CPU·Java 환경에서 다음 최초 준비를 한 번 수행한 뒤, 이후 검증을 실행하세요. 이 과정은 model/tokenizer를 대체하거나 다운로드하지 않으며, 로그에 DJL native library download가 나타날 수 있습니다.

```bash
P2A_ONNX_MODEL_URI=file:///absolute/path/model.onnx \
P2A_ONNX_TOKENIZER_URI=file:///absolute/path/tokenizer.json \
./gradlew onnxVerificationTest
```

성공한 뒤에는 같은 artifact URI로 검증을 반복합니다. `--offline`을 붙여도 DJL cache가 비어 있으면 native library 다운로드가 다시 시도될 수 있으므로, air-gapped 환경에서는 먼저 해당 환경과 같은 platform/runtime 조합에서 native runtime cache를 준비해야 합니다. OS, CPU architecture, Java, Spring AI/DJL dependency를 바꾸거나 native cache를 지운 경우 이 최초 준비를 다시 수행합니다.

### Worker tuning, reconciliation, recovery

reference local capacity는 **4 vCPU, 8 GB RAM, 20 GB SSD**입니다. 이는 hard SLO가 아닌 운영 계획 기준입니다. Gradle daemon의 `gradle.properties` heap은 `-Xmx2g`로 설정되어 있으므로, Java build/test와 PostgreSQL/ONNX runtime이 같은 호스트에서 경쟁할 여유를 남겨야 합니다.

| 환경 변수 | 기본값 | 운영 의미 |
| --- | --- | --- |
| `P2A_MEMORY_EMBEDDING_WORKER_ENABLED` | `true` | `false`면 worker와 reconciler를 모두 실행하지 않습니다. |
| `P2A_MEMORY_EMBEDDING_WORKER_POLL_DELAY` | `1s` | durable job poll 주기입니다. |
| `P2A_MEMORY_EMBEDDING_WORKER_CLAIM_BATCH_SIZE` | `16` | 한 poll에서 active set 대상으로 claim하는 최대 job 수입니다. |
| `P2A_MEMORY_EMBEDDING_WORKER_CONCURRENCY` | `1` | 고정 worker executor thread 수입니다. CPU/memory 측정 없이 올리지 마세요. |
| `P2A_MEMORY_EMBEDDING_WORKER_LEASE_DURATION` | `2m` | in-flight inference의 최악 시간을 넘도록 설정합니다. |
| `P2A_MEMORY_EMBEDDING_WORKER_MAX_ATTEMPTS` | `5` | retryable provider failure의 terminal 전 최대 attempt 수입니다. |
| `P2A_MEMORY_EMBEDDING_WORKER_INITIAL_RETRY_DELAY` | `5s` | retry backoff 시작값입니다. |
| `P2A_MEMORY_EMBEDDING_WORKER_MAX_RETRY_DELAY` | `5m` | exponential retry backoff 상한입니다. |
| `P2A_MEMORY_EMBEDDING_WORKER_BACKFILL_POLL_DELAY` | `1m` | 기존 chunk reconciliation 주기입니다. |
| `P2A_MEMORY_EMBEDDING_WORKER_BACKFILL_BATCH_SIZE` | `500` | reconciliation 한 번에 scan하는 최대 chunk 수입니다. |

reconciler는 startup 직후와 위 주기마다 structurally valid active V2 set을 기준으로 기존 chunk를 bounded batch로 훑습니다. 누락된 embedding job을 enqueue하고, 이미 generic 384-dimensional embedding은 있으나 typed vector mirror가 빠진 경우 mirror를 복구합니다. provider readiness에 의존하지 않으므로 degraded 상태에서도 coverage를 회복할 pending work를 남깁니다. active pointer가 없거나 partial/corrupt/mismatched면 fail closed로 아무 작업도 하지 않습니다.

coverage는 active set별로 `eligibleTotal = pending + running + retrying + succeeded + permanentlyFailed + missing`으로 계산됩니다. 이 내부 reconciler coverage는 아직 REST endpoint가 아니므로, 로컬 operator는 아래 job API에서 backlog 상태를 진단합니다. `missing` 상태는 다음 reconciliation pass가 보완합니다.

job이 오래 `running`이면 lease 만료 후 다음 poll에서 자동으로 pending으로 복구됩니다. `permanently_failed`는 provider/artifact 문제를 먼저 고친 후에만 재시도하세요. 재시도 API는 성공한 embedding을 덮어쓰지 않습니다.

### Health check

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/api/health
curl http://localhost:8080/actuator/metrics
```

정상 응답은 `status: "UP"`입니다.

### 테스트 실행

통합 테스트는 Testcontainers로 Compose와 같은 digest-pinned `pgvector/pgvector:0.8.5-pg17-bookworm@sha256:d2ef61f42ef767baa5a1475393303cc235bcd92febd9d7014eddb48b41f3bad0` PostgreSQL을 시작합니다.

```bash
./gradlew test
./gradlew compileKotlin compileTestKotlin
```

`RetrievalEvalIntegrationTest`는 고정 corpus로 keyword, vector, hybrid 검색의 `recall@k`와 `nDCG@k`를 검증합니다. 검색 ranking이나 ANN 저장 구조를 바꿀 때 이 테스트가 회귀 guardrail 역할을 합니다.

Lima Docker socket을 쓰는 로컬 환경에서는 다음처럼 실행할 수 있습니다.

```bash
DOCKER_HOST=unix://$HOME/.lima/default/sock/docker.sock \
TESTCONTAINERS_RYUK_DISABLED=true \
./gradlew test --rerun-tasks
```

### Dashboard와 전체 regression

아래는 새 로컬 환경에서 실행하는 전체 regression 순서입니다. `P2A_MODEL_DIR`은 runtime Compose에만 쓰는 실제 model directory이고, opt-in ONNX test URI는 `.env`에서 주석을 해제했을 때만 쓰입니다. browser, dashboard static bundle, URL query, local storage에는 `P2A_LOCAL_TOKEN`을 넣지 마세요. token은 Compose dashboard BFF와 backend 사이에서만 server-side header로 사용됩니다.

```bash
# Dashboard/BFF 의존성과 browser 준비 (처음 한 번 또는 lockfile 변경 뒤)
corepack enable
corepack pnpm --dir dashboard install --frozen-lockfile
corepack pnpm --dir dashboard exec playwright install chromium

# Backend와 Testcontainers regression
DOCKER_HOST=unix://$HOME/.lima/default/sock/docker.sock \
TESTCONTAINERS_RYUK_DISABLED=true \
./gradlew test

# Dashboard/BFF 정적 검사와 unit/integration regression
corepack pnpm --dir dashboard run typecheck
corepack pnpm --dir dashboard run lint
corepack pnpm --dir dashboard run test

# 실제 Fastify production BFF를 띄운 browser regression 및 axe 검사
corepack pnpm --dir dashboard run test:e2e
corepack pnpm --dir dashboard run test:a11y

# 별도 project·port·temporary model을 사용하는 Compose E2E regression
P2A_DOCKER_HOST=unix://$HOME/.lima/default/sock/docker.sock \
node docker/verify-compose-e2e.mjs
```

마지막 Compose E2E는 runtime provider가 unavailable일 때의 degraded 응답도 검증하기 위해 temporary fake model을 사용합니다. 실제 `P2A_MODEL_DIR`을 쓰는 위 Compose 기동의 provider `ready` 확인을 대체하지 않습니다. 이 script는 고유한 `p2a-e2e-*` project, port, volume만 생성·정리하므로 표준 Compose deployment와 그 data volume을 중지하거나 삭제하지 않습니다.

### Version pin 유지보수 정책

Compose image는 tag와 digest를 항상 한 쌍으로 고정합니다. base image, pgvector image, Node/Java image의 tag 또는 digest를 바꾸는 일은 별도 maintenance review에서 source tag와 digest, build, runtime health, architecture를 함께 재검증해야 합니다. floating tag를 쓰거나 digest만/태그만 바꾸지 마세요.

Gradle dependency version, `dashboard/package.json`의 direct dependency version, `pnpm-lock.yaml`의 exact resolved version도 같은 maintenance review 대상으로 취급합니다. 의존성 갱신은 기능 작업에 섞지 말고 필요한 regression을 다시 실행합니다. multi-architecture image manifest를 이용하므로 `platform: linux/amd64`처럼 host architecture를 Compose에 hard-code하지 마세요. Lima가 실행 중인 host architecture에 맞는 image를 선택하게 둡니다.

## 인증

보호 대상:

- `/api/**`

인증 제외:

- `/api/health`
- `/api/embedding-jobs/**` (localhost-only embedding 작업 운영 API)
- `/actuator/health`
- `/actuator/metrics`
- `/actuator/metrics/**`

인증이 켜진 경우 요청 예:

```bash
curl -H 'X-P2A-Local-Token: local-dev-token' \
  http://localhost:8080/api/artifacts
```

인증 실패는 `401`과 `RestErrorResponse`를 반환합니다.

```json
{
  "error": "auth_error",
  "message": "Authentication failed",
  "status": 401
}
```

`/api/embedding-jobs/**`는 의도적으로 local token을 우회하는 no-auth 운영 API이며, 현재 Spring Security나 source-IP 검사를 구현하지 않습니다. 여기서 말하는 localhost-only는 배포 경계입니다. `compose.yaml`의 `127.0.0.1` bind는 PostgreSQL에만 적용되므로, Boot server도 `SERVER_ADDRESS=127.0.0.1`로 bind하거나 동등한 loopback firewall/private network 경계를 적용해야 합니다. LAN, public reverse proxy, port forwarding에 이 endpoint를 노출하지 마세요.

localhost 밖에서 embedding 작업 관리가 필요해지면 먼저 별도로 승인된 변경으로 Spring Security 기반 admin authentication/authorization을 추가해야 합니다. 그때까지 no-auth job endpoint를 원격 운영용 API로 사용하지 않습니다.

## REST API 명세

Base URL은 기본 실행 기준 `http://localhost:8080`입니다. 인증이 켜져 있다면 `/api/health`와 local deployment boundary 안의 no-auth `/api/embedding-jobs/**`를 제외한 `/api/**` 요청에 `X-P2A-Local-Token` header를 포함해야 합니다.

### Endpoint 요약

| Method | Endpoint | 설명 | 인증 |
| --- | --- | --- | --- |
| `POST` | `/api/projects` | 프로젝트를 등록하거나 upsert합니다. | 필요 |
| `POST` | `/api/projects/{projectId}/iterations` | 프로젝트에 iteration을 연결해 등록하거나 upsert합니다. | 필요 |
| `POST` | `/api/documents/snapshots` | 문서 또는 산출물 snapshot을 저장합니다. | 필요 |
| `POST` | `/api/task-graphs` | task graph JSON과 graph metadata를 저장합니다. | 필요 |
| `POST` | `/api/tasks/bulk` | task graph에 속한 task 목록을 bulk 저장합니다. | 필요 |
| `POST` | `/api/runs` | task 실행 기록을 저장합니다. | 필요 |
| `POST` | `/api/document-chunks/bulk` | 문서 chunk를 bulk 저장하고 서버 embedding 작업을 enqueue합니다. | 필요 |
| `GET` | `/api/artifacts` | 저장된 artifact를 filter 조건으로 조회합니다. | 필요 |
| `GET` | `/api/search/keyword` | RAG/history lookup을 위한 keyword 검색을 수행합니다. | 필요 |
| `POST` | `/api/search/semantic` | q 텍스트를 서버 embedding으로 semantic 검색합니다. | 필요 |
| `POST` | `/api/search/hybrid` | q 텍스트의 keyword와 server-managed semantic 후보를 RRF로 융합합니다. | 필요 |
| `GET` | `/api/embedding-jobs` | embedding 작업을 status/chunk filter와 cursor로 조회합니다. | 불필요 (localhost-only) |
| `GET` | `/api/embedding-jobs/{jobId}` | embedding 작업의 sanitized 운영 상태를 조회합니다. | 불필요 (localhost-only) |
| `POST` | `/api/embedding-jobs/{jobId}/retry` | permanently failed 작업을 pending으로 재등록합니다. | 불필요 (localhost-only) |
| `GET` | `/api/health` | 간단한 API health check입니다. | 불필요 |
| `GET` | `/actuator/health` | Spring Actuator health check입니다. | 불필요 |
| `GET` | `/actuator/metrics` | Micrometer metric 목록을 조회합니다. | 불필요 |

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
| `chunking` | 선택 | 서버 chunk 생성 opt-in입니다. 이번 계약은 정확히 `{ "strategy": "paragraph-2000" }`만 허용합니다. |

| Response | 포함 정보 |
| --- | --- |
| `DocumentSnapshotResponse` | Snapshot 정보와 `lineage.contentHash`, `lineage.snapshotVersion`, `metadata.sourceDocumentId`. Opt-in 성공 시에만 `chunking.strategy`, 양의 정수 `chunking.chunkCount`가 추가됩니다. |

`chunking`이 없으면 기존 snapshot-only 동작을 그대로 유지하며 자동 chunk나 embedding job을 만들지 않습니다. `chunking`이 malformed이거나 strategy가 `paragraph-2000`과 정확히 일치하지 않으면 snapshot을 쓰기 전에 `400 validation_error`로 거부합니다.

`paragraph-2000` opt-in은 본문을 P2A와 같은 UTF-16 최대 2000자, paragraph 결합, overlap 없음 규칙으로 나눕니다. 서버는 idempotency가 반환한 canonical snapshot ID를 기준으로 chunk ID와 hash를 만들고, snapshot-first 상태에서는 누락된 chunk와 embedding job만 backfill합니다. Snapshot, 생성된 모든 chunk, active embedding target 확인과 chunk별 durable job enqueue는 요청 하나의 transaction이므로 어느 단계에서든 실패하면 그 요청에서 새로 만든 row가 모두 rollback됩니다.

```json
{
  "documentId": "<document-id>",
  "projectId": "<project-id>",
  "sourceDocumentId": "<source-document-id>",
  "sourcePath": "iterations/v2/gate-b-spec/product-spec.md",
  "artifactType": "DOCUMENT_SNAPSHOT",
  "title": "Product spec",
  "content": "# Product spec\n\n...",
  "contentHash": "<sha256>",
  "chunking": { "strategy": "paragraph-2000" }
}
```

```json
{
  "documentId": "<canonical-document-id>",
  "chunking": {
    "strategy": "paragraph-2000",
    "chunkCount": 3
  }
}
```

### `POST /api/task-graphs`

Task graph JSON과 graph metadata를 저장합니다.

`projectId`/`iterationId`/`sourceTaskGraphId`가 같은 graph는 하나의 logical graph로 취급합니다. 같은 source identity와 `graphHash`를 다시 보내면 기존 응답을 그대로 반환하고, hash가 달라지면 canonical `taskGraphId`는 유지한 채 최신 graph JSON과 metadata를 갱신합니다. source identity가 다르면 hash가 같아도 별도 graph로 저장합니다. 이미 다른 source identity에 연결된 canonical ID를 보내면 `409 conflict`로 거부합니다. 클라이언트는 이후 `/api/tasks/bulk`의 `graphId`와 `tasks[].taskGraphId`에 응답의 canonical ID를 사용해야 합니다. 이 source identity 규칙은 Flyway migration version과 무관한 API contract입니다.

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

문서 chunk만 저장합니다. 서버는 저장 성공 후 활성 embedding 구성에 대한 durable embedding 작업을 enqueue합니다. 요청에는 `embeddingSet`, `embedding`, `embeddingHash`를 포함할 수 없으며, 제거된 field는 unknown-field validation error로 거부됩니다. Snapshot opt-in이 추가된 뒤에도 이 endpoint의 요청·응답과 enqueue 동작은 기존 client를 위해 그대로 유지됩니다.

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

| Response | 포함 정보 |
| --- | --- |
| `DocumentChunkResponse[]` | 저장된 chunk 목록과 `chunkHash`, `lineage.taskId`, `lineage.runId`, `sourceReference` |

```json
{
  "documentId": "<document-id>",
  "chunks": [{
    "chunk": {
      "chunkId": "<chunk-id>",
      "projectId": "<project-id>",
      "artifactType": "DOCUMENT_SNAPSHOT",
      "sourcePath": "docs/spec.md",
      "chunkIndex": 0,
      "content": "Chunk text",
      "chunkHash": "sha256:..."
    }
  }]
}
```

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
| `cursor` | 선택 | 이전 응답의 `nextCursor`입니다. 같은 filter와 함께 넘기면 다음 페이지를 keyset 방식으로 조회합니다. |

| Response | 포함 정보 |
| --- | --- |
| `PagedResponse<ArtifactLookupResponse>` | `items`, 다음 페이지가 있을 때만 채워지는 opaque `nextCursor` |

Artifact lookup은 `sort_timestamp DESC, artifactType ASC, artifactId ASC` keyset cursor를 사용합니다. Cursor는 서버 opaque 값이므로 클라이언트에서 파싱하지 말고 그대로 전달해야 합니다.

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
| `cursor` | 선택 | 이전 응답의 `nextCursor`입니다. 같은 filter와 함께 넘기면 다음 페이지를 keyset 방식으로 조회합니다. |

| 검색 동작 | 설명 |
| --- | --- |
| 검색 대상 | 주 대상은 `document_chunks.content`, 보조 대상은 `documents.content`, `sourcePath`, `artifactType`입니다. |
| Matching | PostgreSQL `to_tsvector('simple')`/`plainto_tsquery('simple')` 기반 full-text search입니다. 한국어/CJK 전용 analyzer는 후속 과제입니다. |
| Filter semantics | 여러 filter는 AND semantics로 적용됩니다. |
| Score | `score`는 backend-opaque 값이며 API 안정 계약으로 고정하지 않습니다. |
| Tie-break | 동률은 snapshot/version/timestamp/chunkIndex/documentId/chunkId 기준으로 정렬합니다. |

| Response | 포함 정보 |
| --- | --- |
| `PagedResponse<KeywordSearchResponse>` | `items` 안의 `content`, `score`, `matchReason`, `lineage`, `sourceIds`, `sourceReference`, `citation`, `metadata`; 다음 페이지가 있을 때만 채워지는 opaque `nextCursor` |

### `POST /api/search/semantic`

서버가 q 텍스트로 query embedding을 생성하고 현재 활성 embedding set만 검색합니다. 외부 query vector와 model/dimension/version/metric 입력은 지원하지 않습니다.

`projectId` filter에는 P2A artifact의 사람용 source/display key가 아니라 Memory 서버의 canonical UUID를 전달해야 합니다. P2A 하네스에서는 `p2a memory status` 또는 `p2a memory push --dry-run`의 `canonical project ID` 출력으로 확인할 수 있고, JSON 출력에서는 `context.canonicalProjectId`를 사용합니다. 서버 API만 사용할 때는 `GET /api/artifacts` 응답의 `projectId`로 확인할 수 있습니다.

| Request field | 필수 | 설명 |
| --- | --- | --- |
| `q` | 필수 | 검색 query text입니다. |
| `projectId`, `iterationId`, `artifactType`, `sourcePath`, `taskId`, `runId` | 선택 | 활성 embedding set 검색에 적용할 filter입니다. |
| `metadataFilters` | 선택 | Metadata key/value filter입니다. 생략하거나 `null`을 보내면 빈 map으로 처리합니다. |
| `limit` | 선택 | 최대 응답 개수입니다. |
| `cursor` | 선택 | 이전 응답의 request-bound `nextCursor`입니다. |

```json
{
  "q": "결제 취소 정책",
  "projectId": "<canonical-project-uuid>",
  "metadataFilters": {"kind": "decision"},
  "limit": 20
}
```

| Response | 포함 정보 |
| --- | --- |
| `PagedResponse<VectorSearchResponse>` | `items` 안의 `score`, `distanceMetric`, `embeddingModel`, `embeddingVersion`, `lineage`, `sourceIds`, `sourceReference`, `citation`, `metadata`; 다음 페이지가 있을 때만 채워지는 opaque `nextCursor` |

### `POST /api/search/hybrid`

q 텍스트에서 keyword 후보와 server-managed semantic 후보를 각각 조회한 뒤 reciprocal rank fusion(RRF)으로 합쳐 반환합니다.

| Request field | 필수 | 설명 |
| --- | --- | --- |
| `q` | 필수 | Keyword query입니다. |
| `projectId`, `iterationId`, `artifactType`, `sourcePath`, `taskId`, `runId` | 선택 | Keyword/semantic 양쪽 후보 조회에 동일하게 적용되는 filter입니다. |
| `metadataFilters` | 선택 | Metadata key/value filter입니다. 생략하거나 `null`을 보내면 빈 map으로 처리합니다. |
| `rrfK` | 선택 | RRF 상수입니다. 기본값은 `60`입니다. |
| `candidateLimit` | 선택 | 각 arm에서 가져올 후보 수입니다. 생략 시 `max(80, limit * 4)`입니다. |
| `limit` | 선택 | 최종 응답 개수입니다. |
| `cursor` | 선택 | 이전 응답의 `nextCursor`입니다. 같은 filter와 함께 넘기면 다음 fused 후보 페이지를 조회합니다. |

```json
{
  "q": "결제 취소 정책",
  "projectId": "<canonical-project-uuid>",
  "rrfK": 60,
  "candidateLimit": 80,
  "limit": 20
}
```

| 검색 동작 | 설명 |
| --- | --- |
| Fusion | 같은 chunk는 하나의 hit로 병합하고, keyword/semantic arm별 rank와 원 score를 `keyword`, `vector`에 노출합니다. |
| Score | 최종 `score`는 RRF 점수입니다. Arm별 `score`는 각 검색 backend의 opaque 점수입니다. |
| Cursor scope | Hybrid cursor는 현재 `candidateLimit` 안에서 만든 fused 후보 목록 기준입니다. 완전한 전역 pagination이 필요하면 `candidateLimit`을 충분히 크게 잡아야 합니다. |

| Response | 포함 정보 |
| --- | --- |
| `PagedResponse<HybridSearchResponse>` | `items` 안의 `content`, RRF `score`, `matchReason`, `keyword`, `vector`, `lineage`, `sourceIds`, `sourceReference`, `citation`, `metadata`; 다음 페이지가 있을 때만 채워지는 opaque `nextCursor` |

### `GET /api/embedding-jobs`

headless 로컬 운영자가 embedding 작업을 진단하는 localhost-only API입니다. `status`는 `pending`, `running`, `retrying`, `succeeded`, `permanently_failed` 중 하나 이상을 반복 또는 comma-separated로 전달할 수 있고, `chunkId`와 함께 좁힐 수 있습니다. `limit`의 기본값은 `50`, 최대값은 `200`입니다.

응답은 `createdAt DESC`, `jobId DESC`로 고정 정렬됩니다. `nextCursor`에는 마지막 row의 두 정렬 key와 `status`/`chunkId` filter fingerprint가 포함되므로 다른 filter로 재사용하면 `400 validation_error`를 반환합니다.

```text
GET /api/embedding-jobs?status=permanently_failed&limit=50
GET /api/embedding-jobs?status=pending&status=retrying&chunkId=<chunk-id>
```

로컬 diagnose/retry 순서는 다음과 같습니다.

```bash
# terminal failure와 대기 중인 work를 분리해 조회합니다.
curl 'http://127.0.0.1:8080/api/embedding-jobs?status=permanently_failed&limit=50'
curl 'http://127.0.0.1:8080/api/embedding-jobs?status=pending&status=retrying&limit=50'

# 위 응답의 jobId 한 건을 상세 조회한 뒤, 원인을 수정한 경우에만 재시도합니다.
curl 'http://127.0.0.1:8080/api/embedding-jobs/<job-id>'
curl -X POST 'http://127.0.0.1:8080/api/embedding-jobs/<job-id>/retry'
```

상세 응답의 `lastErrorCode`는 `provider_unavailable`, `provider_contract_invalid`, `content_invalid`, `max_attempts_exhausted`, `lease_expired` 중 하나입니다. file URI, raw exception, provider credential, chunk content는 의도적으로 반환되지 않습니다. `provider_unavailable` 또는 `max_attempts_exhausted`는 artifact/provider와 worker settings를 먼저 고치고 재시도하고, `content_invalid`은 source chunk를 정정한 새 write 뒤 처리하세요.

### `GET /api/embedding-jobs/{jobId}`

작업 ID의 상세 상태를 반환합니다. 응답 field는 `jobId`, `chunkId`, `embeddingSetId`, `status`, `attemptCount`, `nextAttemptAt`, `leaseExpiresAt`, `lastErrorCode`, `sanitizedLastErrorMessage`, `createdAt`, `updatedAt`, `completedAt`입니다. 원본 chunk content, lease owner, provider credential, raw exception/stack trace, file URI 또는 전체 path는 반환하지 않습니다. 오류 text 대신 stable error code와 일반화된 sanitized message만 노출합니다.

### `POST /api/embedding-jobs/{jobId}/retry`

`permanently_failed` 작업은 `pending`으로 되돌리고 `attemptCount=0`, `nextAttemptAt=now`, lease fields=`null`로 초기화합니다. 이미 `pending`, `running`, `retrying`인 작업은 같은 현재 상태를 성공 응답으로 반환하므로 중복 호출에 idempotent합니다. `succeeded` 작업은 기존 embedding을 덮어쓰지 않도록 `409 conflict`, 없는 job은 `404 not_found`입니다.

이 세 API는 v2에서 local token이나 Spring Security를 요구하지 않습니다. 이 no-auth 예외는 위의 localhost-only deployment boundary에서만 허용됩니다. 원격 접근은 future Spring Security admin API가 별도 승인·구현되기 전까지 지원하지 않습니다.

### Search citation

Keyword, semantic, hybrid hit는 모두 `lineage`, `sourceIds`, `sourceReference`를 top-level로 유지하면서 같은 정보를 `citation` object로도 제공합니다. 클라이언트는 `citation.sourceReference.path`, `citation.lineage.chunkId`, `citation.sourceIds.sourceDocumentId`를 함께 사용해 검색 결과를 원본 산출물 위치와 연결할 수 있습니다.

### Observability endpoints

| Method | Endpoint | 설명 | 인증 |
| --- | --- | --- | --- |
| `GET` | `/api/health` | 간단한 API health endpoint입니다. | 불필요 |
| `GET` | `/actuator/health` | Spring Actuator health endpoint입니다. | 불필요 |
| `GET` | `/actuator/metrics` | 노출된 Micrometer metric 목록입니다. | 불필요 |
| `GET` | `/actuator/metrics/{metricName}` | 개별 metric 상세입니다. | 불필요 |

주요 custom metric:

- `p2a.memory.search.calls`, `p2a.memory.search.duration`
- `p2a.memory.write.calls`, `p2a.memory.write.duration`
- `p2a.embedding.provider.state` — `state=not_configured|initializing|ready|unavailable` one-hot gauge
- `p2a.embedding.provider.initialization` — `outcome=ready|unavailable|not_configured` counter
- `p2a.embedding.jobs` — `status=pending|running|retrying|succeeded|permanently_failed` durable backlog gauge
- `p2a.embedding.jobs.outcomes` — `outcome=succeeded|retrying|permanently_failed` counter
- `p2a.embedding.inference` — `operation=query|document`, `outcome=succeeded|not_configured|unavailable|contract_invalid` latency timer

embedding custom metric의 tag는 위 고정 enum만 사용합니다. query text, chunk/job ID, content, provider 응답 body, credential, file path는 tag·metric name·structured log에 넣지 않습니다. provider가 `UNAVAILABLE`이어도 기본 `/actuator/health/liveness`, `/actuator/health/readiness`를 DOWN으로 만들지 않으며, semantic/hybrid만 `embedding_provider_unavailable` 503으로 구분해 반환합니다.

## Idempotency와 versioning

### Document snapshot

동일 logical scope에서 같은 `sourcePath`, `artifactType`, `contentHash`가 반복 저장되면 기존 snapshot을 반환합니다. 새 row를 만들지 않습니다.

같은 `sourcePath`, `artifactType`에 다른 `contentHash`가 저장되면 overwrite하지 않고 새 snapshot을 만들며 `snapshotVersion`이 증가합니다.

### Document chunk

같은 `documentId`, `chunkHash`가 반복 저장되면 기존 chunk를 반환합니다. 새 chunk row를 만들지 않습니다.

같은 batch 안에서 `chunkId`, `chunkHash`, `chunkIndex`가 중복되면 validation error입니다.

### Chunk embedding

`embedding_sets`와 `chunk_embeddings`는 서버가 관리하는 persisted vector data입니다. 기존 row는 제거된 REST 요청 형식으로 덮어쓰거나 삭제하지 않으며, 새 chunk의 embedding은 durable job worker가 활성 set에 추가합니다.

V2는 완전히 비어 있는 profile state에서만 고정 V2 immutable set을 bootstrap합니다. 그 이후에는 V2 active pointer를 바꾸는 REST API나 application service가 없습니다. operator나 client가 pointer를 직접 바꾸거나 기존 set의 model/version/manifest를 수정해서는 안 됩니다.

새 embedding model 또는 version은 현재 V2의 pointer switch가 아닙니다. 미래 model 변경은 새 immutable set, backfill, 평가, cutover를 명시한 **별도로 승인된 Plan2Agent iteration**으로만 진행해야 합니다. 이 문서는 그런 future iteration의 API/service를 정의하거나 승인하지 않습니다.

`embeddingDimension`이 2 또는 1536인 legacy embedding은 고정 차원 보조 테이블에도 보존됩니다. active semantic search는 서버가 선택한 active embedding set으로 범위를 제한합니다.

### Breaking client migration

`POST /api/document-chunks/bulk`에서 client embedding인 `embeddingSet`, `embedding`, `embeddingHash`를 제거했습니다. `POST /api/search/vector`도 제거되며 해당 path는 `404`입니다. 클라이언트는 chunk-only bulk payload를 보내고 text `q`가 필요한 검색은 `/api/search/semantic` 또는 `/api/search/hybrid`를 사용해야 합니다. semantic/hybrid request는 `q`만으로 server-managed embedding을 요청하며, 이전 vector/model/dimension/version/metric field를 보내면 validation error입니다.

각 client repository는 server rollout과 분리된 follow-up Plan2Agent task에서 다음 endpoint inventory/update를 완료해야 합니다.

1. source, generated client, integration test, fixture, README/API example에서 `/api/document-chunks/bulk`, `/api/search/vector`, `/api/search/semantic`, `/api/search/hybrid`와 `embeddingSet`, `embedding`, `embeddingHash`, `embeddingModel`, `embeddingDimension`, `embeddingVersion`, `distanceMetric`를 검색해 call site와 owner를 inventory로 기록합니다.
2. bulk writer는 chunk-only schema로 변경하고 client-side embedding 생성, model metadata 전달, vector serialization을 제거합니다.
3. vector search caller는 목적에 맞게 `POST /api/search/semantic` 또는 `POST /api/search/hybrid`의 `q` request로 옮기고, `/api/search/vector` 404를 compatibility fallback으로 취급하지 않습니다.
4. request/response types, mocks, fixtures, API docs, contract/integration tests를 함께 갱신해 unknown legacy field가 보내지지 않음을 검증합니다.
5. Plan2Agent task의 inventory 각 항목에 변경 call site와 실행한 client verification을 연결한 뒤에만 migration을 close합니다. 이 server task는 client source를 변경하지 않습니다.

## Path 처리

`sourcePath`는 저장 use case에서 정규화됩니다.

- 앞뒤 공백 제거
- Windows separator `\`를 `/`로 변환
- 중복 `/` 축약
- 앞의 `./` 제거

이 문서에서 `normalizedPath`는 정규화 후의 path를 의미합니다. REST 응답의 `sourcePath`와 DB 컬럼 `source_path`에는 `normalizedPath`가 저장됩니다.

이 문서에서 `rawSourcePath`는 클라이언트가 보낸 원본 path를 의미합니다. REST payload에는 별도 top-level `rawSourcePath` field가 없고, `sourceReference.path`가 원본 path 역할을 합니다. 서버는 `sourceReference.path`를 DB 컬럼 `raw_source_path`에 보존합니다.

문서와 chunk 응답의 `sourcePath`는 정규화된 path입니다. `/api/artifacts`, `/api/search/keyword`, `/api/search/semantic`, `/api/search/hybrid`의 `sourcePath` filter도 `normalizedPath` 기준으로 비교됩니다. 클라이언트가 로컬 파일과 다시 매칭할 때는 `sourcePath`, `artifactType`, `contentHash`, `snapshotVersion`, `sourceReference`를 함께 사용해야 합니다.

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
- 서버는 configured local provider가 있을 때만 server-managed embedding을 생성하며, client embedding 값을 받지 않습니다.
- 서버 내장 웹 UI는 제공하지 않습니다.
- status, diff, push, pull, conflict resolution, history UX는 P2A GUI/CLI가 담당합니다.

### Artifact lineage graph index

The memory server stores a derived artifact lineage index in PostgreSQL so local Plan2Agent clients can rebuild and push traceability data extracted from source-of-truth files. The index is additive and does not run harness, agent, or external AI workflows.

Schema additions:

- `artifact_nodes` stores stable UUID nodes scoped by `project_id` and optional `iteration_id`, with closed `node_kind` values for decisions, assumptions, clarifying questions, evidence, spec sections, documents, tasks, runs, and proposals.
- `artifact_edges` stores directed relationships from downstream artifacts to upstream/source artifacts. Supported edge types are `DERIVED_FROM`, `DEPENDS_ON`, `DISPOSES`, `EVIDENCED_BY`, `EXECUTED_FOR`, and `BLOCKS`.

Endpoints:

- `POST /api/graph/snapshots` replaces the graph snapshot for one `(projectId, iterationId)` scope in a single transaction and returns `{ "nodeCount": n, "edgeCount": m }`. Because the scope is fully replaced, every edge endpoint must be included in the same payload's `nodes` array.
- `GET /api/graph/nodes?nodeKind=task&query=...&limit=20` searches graph nodes by kind and label/content text so callers can find a trace entry `naturalKey`. `projectId` is optional: omit it to search across all projects for precedent discovery, then use each returned node's `projectId` when tracing. Supplying `iterationId` still requires `projectId` because iterations are project-scoped. Literal `%`, `_`, and `\` characters in `query` are escaped before `ILIKE` matching.
- `GET /api/graph/trace?projectId=...&naturalKey=...&direction=both&maxDepth=10` returns the reachable subgraph using recursive traversal. `projectId` remains required because natural keys such as `decision:ND-1` can collide across projects. `upstream` follows edge direction, while `downstream` traverses impact in reverse. If `iterationId` is omitted, the root is resolved project-wide and duplicate `naturalKey` matches are rejected as ambiguous.
- `POST /api/graph/snapshots` validates referenced `documentId`, `taskId`, and `runId` values before storage and rejects self-loop edges, so bad graph payloads return `400` instead of surfacing database constraint errors.

Write endpoints continue to require the local token header configured for `X-P2A-Local-Token`.
