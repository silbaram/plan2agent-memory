# GCP Cloud Run 배포

이 프로젝트는 Cloud Run에서 실행하고, Cloud SQL for PostgreSQL과 pgvector를 저장소로 사용할 수 있다.

## 콘솔에서 이미 완료한 항목

- Cloud SQL PostgreSQL 인스턴스: `p2a-memory-db`
- 리전: `us-central1`
- 데이터베이스: `p2a_artifact_store`
- 앱 접속 유저: `p2a`
- `p2a_artifact_store` 데이터베이스에 `postgres` 유저로 접속해서 extension과 권한 설정 실행:

```sql
CREATE EXTENSION IF NOT EXISTS vector;
GRANT CONNECT, CREATE ON DATABASE p2a_artifact_store TO p2a;
GRANT USAGE, CREATE ON SCHEMA public TO p2a;
```

## 필요한 IAM 권한

Cloud Run 런타임 서비스 계정에 `Cloud SQL Client` 역할을 부여해야 한다.

기본 Cloud Run 런타임 서비스 계정을 쓰는 경우 멤버는 보통 아래 형태다.

```text
PROJECT_NUMBER-compute@developer.gserviceaccount.com
```

부여할 역할:

```text
Cloud SQL Client
```

## 로컬 수동 배포

CI/CD 없이 내 컴퓨터에서 직접 Cloud Run 배포 명령을 실행하는 방식이다.

장점:

- CI/CD 구성 비용과 시간이 들지 않는다.
- 가장 단순하다.
- Cloud Build Trigger, GitHub 연결, Secret Manager 없이도 바로 배포할 수 있다.

단점:

- 새 버전을 배포할 때마다 내 컴퓨터에서 명령을 다시 실행해야 한다.
- `--set-env-vars`에 DB 비밀번호와 API 토큰이 평문으로 들어간다.
- 나중에 운영용으로 바꿀 때는 Secret Manager와 CI/CD로 옮기는 것이 좋다.

흐름:

```text
로컬 터미널에서 gcloud run deploy 실행
-> Google Cloud가 현재 소스 업로드
-> Cloud Build가 Spring Boot 앱 빌드
-> 컨테이너 이미지 생성
-> Cloud Run에 새 revision 배포
```

먼저 현재 gcloud 프로젝트를 설정한다.

```bash
gcloud auth login
gcloud config set project PROJECT_ID
```

이 repository root에서 배포한다.

```bash
gcloud run deploy p2a-memory \
  --source . \
  --region us-central1 \
  --min-instances 0 \
  --max-instances 1 \
  --memory 1Gi \
  --allow-unauthenticated \
  --set-env-vars "P2A_DB_URL=jdbc:postgresql:///p2a_artifact_store?cloudSqlInstance=PROJECT_ID:us-central1:p2a-memory-db&socketFactory=com.google.cloud.sql.postgres.SocketFactory&cloudSqlRefreshStrategy=lazy,P2A_DB_USERNAME=p2a,P2A_DB_PASSWORD=CLOUD_SQL_P2A_PASSWORD,P2A_LOCAL_TOKEN=LONG_RANDOM_TOKEN,SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=3"
```

아래 값은 실제 값으로 바꾼다.

- `PROJECT_ID`
- `CLOUD_SQL_P2A_PASSWORD`
- `LONG_RANDOM_TOKEN`

비밀번호나 토큰에 쉼표가 들어 있으면 `--set-env-vars` 대신 Cloud Run 콘솔에서 환경변수를 직접 넣는 편이 안전하다.

## 확인

```bash
SERVICE_URL=$(gcloud run services describe p2a-memory --region us-central1 --format='value(status.url)')
curl "$SERVICE_URL/api/health"
curl -H "X-P2A-Local-Token: LONG_RANDOM_TOKEN" "$SERVICE_URL/api/artifacts"
```

`/api/health`는 `UP`을 반환해야 한다. 아직 데이터가 동기화되지 않았다면 `/api/artifacts`는 빈 page를 반환하는 것이 정상이다.

## Cloud Build Trigger로 CI/CD 구성

수동 배포 대신 GitHub `main` 브랜치에 push될 때마다 자동으로 Cloud Run에 배포하려면 Cloud Build Trigger를 사용한다.

권장 흐름:

```text
GitHub main push
-> Cloud Build Trigger 실행
-> gcloud run deploy --source . 실행
-> Cloud Build가 Spring Boot 앱을 컨테이너로 빌드
-> Cloud Run 새 revision 배포
```

이 방식은 처음 구성하기 쉽다. 소규모 개인 사용에서는 Cloud Build 무료 build-minute 범위 안에 들어갈 가능성이 높고, 실제 월 비용 대부분은 Cloud Build가 아니라 Cloud SQL에서 발생한다.

### 1. 필요한 API

Google Cloud Console에서 아래 API가 켜져 있어야 한다.

- Cloud Build API
- Cloud Run Admin API
- Artifact Registry API
- Secret Manager API
- Cloud SQL Admin API

### 2. Secret Manager에 비밀값 저장

Cloud Run 환경변수에 비밀번호와 토큰을 평문으로 넣지 않기 위해 Secret Manager를 사용한다.

생성할 secret:

- `p2a-db-password`: Cloud SQL `p2a` 유저 비밀번호
- `p2a-local-token`: API 호출에 사용할 `X-P2A-Local-Token` 값

콘솔에서 만들거나, 로컬 gcloud에서 아래처럼 만들 수 있다.

```bash
printf '%s' 'CLOUD_SQL_P2A_PASSWORD' | gcloud secrets create p2a-db-password --data-file=-
printf '%s' 'LONG_RANDOM_TOKEN' | gcloud secrets create p2a-local-token --data-file=-
```

이미 secret이 있으면 새 버전을 추가한다.

```bash
printf '%s' 'CLOUD_SQL_P2A_PASSWORD' | gcloud secrets versions add p2a-db-password --data-file=-
printf '%s' 'LONG_RANDOM_TOKEN' | gcloud secrets versions add p2a-local-token --data-file=-
```

### 3. Cloud Run 런타임 서비스 계정 권한

Cloud Run에서 실행되는 앱이 Cloud SQL과 Secret Manager를 읽을 수 있어야 한다.

기본 런타임 서비스 계정은 보통 아래 형태다.

```text
PROJECT_NUMBER-compute@developer.gserviceaccount.com
```

이 서비스 계정에 아래 역할을 부여한다.

- `Cloud SQL Client`
- `Secret Manager Secret Accessor`

`Secret Manager Secret Accessor`는 프로젝트 전체에 부여해도 되지만, 더 깔끔하게 하려면 `p2a-db-password`, `p2a-local-token` 두 secret에만 부여한다.

### 4. Cloud Build 서비스 계정 권한

Cloud Build Trigger가 Cloud Run 배포를 실행해야 한다.

Cloud Build가 사용하는 서비스 계정은 Trigger 상세 화면에서 확인한다. 보통 아래 중 하나다.

```text
PROJECT_NUMBER@cloudbuild.gserviceaccount.com
PROJECT_NUMBER-compute@developer.gserviceaccount.com
```

이 서비스 계정에 아래 역할을 부여한다.

- `Cloud Run Admin`
- `Cloud Build Service Account`
- `Cloud Run Builder`
- `Artifact Registry Writer`
- `Service Account User`

`Service Account User`는 Cloud Run 런타임 서비스 계정에 대해 부여하면 된다.

### 5. cloudbuild.yaml 확인

CI/CD를 쓰려면 repository root에 `cloudbuild.yaml`이 있어야 한다. 이 repo에는 아래 구성이 포함되어 있다.

```yaml
steps:
  - name: gcr.io/google.com/cloudsdktool/cloud-sdk:slim
    entrypoint: gcloud
    args:
      - run
      - deploy
      - p2a-memory
      - --source
      - .
      - --region
      - us-central1
      - --min-instances
      - "0"
      - --max-instances
      - "1"
      - --memory
      - 1Gi
      - --allow-unauthenticated
      - --set-env-vars
      - P2A_DB_URL=jdbc:postgresql:///p2a_artifact_store?cloudSqlInstance=$PROJECT_ID:us-central1:p2a-memory-db&socketFactory=com.google.cloud.sql.postgres.SocketFactory&cloudSqlRefreshStrategy=lazy,P2A_DB_USERNAME=p2a,SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=3
      - --set-secrets
      - P2A_DB_PASSWORD=p2a-db-password:latest,P2A_LOCAL_TOKEN=p2a-local-token:latest

options:
  logging: CLOUD_LOGGING_ONLY
```

`$PROJECT_ID`는 Cloud Build가 자동으로 치환하는 내장 변수다. 직접 바꾸지 않아도 된다.

이 파일은 Cloud Build가 `gcloud run deploy --source .`를 호출하는 단순 구성이다. 빌드 시간이 늘어나면 나중에 Dockerfile 또는 명시적인 image build 방식으로 바꿀 수 있다.

### 6. Cloud Build Trigger 생성

Google Cloud Console에서:

1. Cloud Build 이동
2. Triggers 이동
3. Connect repository 선택
4. GitHub repository 연결
5. Create trigger 선택
6. 설정:
   - Name: `p2a-memory-main-deploy`
   - Event: Push to a branch
   - Branch: `^main$`
   - Configuration: Cloud Build configuration file
   - Location: `/cloudbuild.yaml`
7. 저장

이후 `main` 브랜치에 push하면 자동 배포된다.

### 7. CI/CD 배포 확인

Trigger가 성공한 뒤:

```bash
SERVICE_URL=$(gcloud run services describe p2a-memory --region us-central1 --format='value(status.url)')
curl "$SERVICE_URL/api/health"
curl -H "X-P2A-Local-Token: LONG_RANDOM_TOKEN" "$SERVICE_URL/api/artifacts"
```

`/api/health`가 `UP`이고 `/api/artifacts`가 응답하면 Cloud Run 앱과 Cloud SQL 연결이 모두 정상이다.

### 8. 비용 메모

- Cloud Build Trigger 자체는 상시 비용이 없다.
- 빌드가 실행된 시간만 build-minute으로 계산된다.
- 소규모 사용에서는 Cloud Build 무료 build-minute 범위 안에 들어갈 가능성이 높다.
- Cloud Run은 `min-instances 0`이면 요청이 없을 때 거의 비용이 없다.
- 월 비용 대부분은 계속 켜져 있는 Cloud SQL `p2a-memory-db`에서 발생한다.
