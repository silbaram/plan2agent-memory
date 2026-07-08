# Reversible forgetting / GC + as-of query spike

created_at: 2026-07-06
status: spike-design
source_rank: docs/plns/next-iteration-recommendations.md rank 10

## 범위

이 문서는 lineage 기반 되돌릴 수 있는 forgetting/GC와 `asOf` 시점 조회를 바로 구현하기 전, 서버 계약을 좁히기 위한 설계 스파이크다. 현재 브랜치에서는 runtime delete, archive, as-of query API를 추가하지 않는다. 검색/저장 경로의 blast radius가 크고, 실제 사용자 수요가 검증되지 않았기 때문이다.

## 목표

- hard delete 대신 archive event를 저장해 복구 가능성을 보존한다.
- snapshot, chunk, embedding, task/run lineage를 유지한 채 "현재 보이는 것"과 "과거 시점에 보였던 것"을 분리한다.
- GC는 물리 삭제가 아니라 먼저 visibility 전환과 retention 후보 산출로 모델링한다.
- keyword/vector/hybrid 검색에서 같은 `asOf` 의미를 공유할 수 있게 한다.

## Non-goals

- 로컬 원본 파일 삭제 또는 merge 자동화.
- embedding 재생성.
- agent memory decay scoring.
- 사용자별 권한/tenant별 retention policy.
- 즉시 물리 삭제 GC.

## 제안 모델

### Event table

`artifact_events`를 추가해 visibility 변화를 append-only로 기록한다.

필드 초안:

| field | 설명 |
| --- | --- |
| `event_id` | 서버 canonical event UUID |
| `artifact_type` | 대상 artifact type |
| `artifact_id` | 대상 canonical ID |
| `project_id`, `iteration_id` | filter와 partitioning을 위한 scope |
| `event_type` | `ARCHIVED`, `RESTORED`, `SUPERSEDED`, `GC_CANDIDATE`, `GC_PURGED` |
| `reason` | 사용자가 넘긴 짧은 reason |
| `effective_at` | visibility가 바뀌는 논리 시각 |
| `created_at` | event 저장 시각 |
| `source_reference` metadata | 이벤트를 만든 원본 위치 또는 command 위치 |

`GC_PURGED`는 향후 물리 삭제가 승인될 때만 사용한다. 초기 구현에서는 `ARCHIVED`, `RESTORED`, `SUPERSEDED`만 허용한다.

### Current visibility view

조회 어댑터는 artifact별 최신 event를 계산해 다음 visibility를 얻는다.

| 최신 event | visible |
| --- | --- |
| 없음 | true |
| `ARCHIVED` | false |
| `SUPERSEDED` | false |
| `RESTORED` | true |

`SUPERSEDED`는 새 snapshot이 과거 snapshot을 대체했다는 의미이며, 복구 가능한 archive와 동일하게 검색 기본 결과에서 제외한다.

### As-of visibility

`asOf`가 있는 조회는 `effective_at <= asOf`인 event만 고려한다. artifact row 자체의 `created_at` 또는 snapshot `captured_at`이 `asOf`보다 늦으면 조회에서 제외한다.

기본식:

```sql
created_at <= :asOf
AND visibility_from_events(artifact_id, :asOf) = true
```

정확한 SQL은 artifact type별 union query에 맞춰 adapter 내부에서 풀어낸다. 함수로 숨기기보다 query plan을 확인할 수 있는 CTE/view부터 시작한다.

## API 초안

### Archive

`POST /api/artifacts/{artifactType}/{artifactId}/archive`

Request:

```json
{
  "reason": "obsolete generated plan",
  "effectiveAt": "2026-07-06T00:00:00Z",
  "sourceReference": {
    "canonicalServerId": "event-command-id",
    "uri": "file:///repo/.plan2agent/runs/run.json"
  }
}
```

Response는 생성된 event와 현재 visibility를 반환한다.

### Restore

`POST /api/artifacts/{artifactType}/{artifactId}/restore`

`ARCHIVED` 또는 `SUPERSEDED` 이후에도 복구 event를 추가할 수 있다. 복구는 과거 event를 삭제하지 않는다.

### As-of query

`GET /api/artifacts?asOf=2026-07-06T00:00:00Z`

`GET /api/search/keyword?q=...&asOf=2026-07-06T00:00:00Z`

`POST /api/search/vector`와 `POST /api/search/hybrid`는 request body에 `asOf`를 추가한다.

초기 구현에서는 `asOf`와 cursor를 함께 사용할 때 cursor 안에도 `asOf`를 포함하거나, cursor 생성 시 filter hash를 넣어 다른 `asOf` 재사용을 거부한다.

## 구현 순서

1. `artifact_events` migration과 event store port를 추가한다.
2. `/api/artifacts`에 `includeArchived=false`, `asOf` filter를 추가한다.
3. keyword/vector/hybrid 검색에 같은 visibility CTE를 적용한다.
4. archive/restore mutation API를 feature flag 뒤에 둔다.
5. retention 후보 조회 API를 read-only로 추가한다.
6. 실제 hard purge는 별도 승인 전까지 금지한다.

## 검증 기준

- archive 후 기본 artifact lookup과 keyword/vector/hybrid 검색에서 제외된다.
- restore 후 다시 조회된다.
- `asOf`가 archive 이전이면 조회되고, archive 이후이면 제외된다.
- `asOf`가 restore 이후이면 다시 조회된다.
- archive/restore event는 idempotency key 또는 event hash로 중복 저장을 막는다.
- cursor는 다른 `asOf`나 visibility filter에 재사용할 수 없다.

## 지금 할 수 없는 것

- 수요 검증 없이 hard purge까지 구현.
- visibility event가 없는 기존 cursor와 완전 호환 보장.
- ANN index recall과 visibility filter 조합의 성능 보장.
- 로컬 파일의 실제 삭제/복구 자동화.

## 다음 결정

Rank 10을 runtime 기능으로 승격하려면 먼저 다음 결정을 내려야 한다.

- archive 대상 단위: document snapshot, chunk, embedding, task/run 중 어디까지 허용할지.
- supersede 정책: 새 snapshot 저장 시 이전 snapshot을 자동 `SUPERSEDED` 처리할지.
- API flag 이름과 기본값.
- cursor에 filter hash를 도입할지, 현재 opaque cursor 버전을 올릴지.
- 물리 GC를 영구 금지할지, retention window 뒤 별도 admin API로 둘지.
