# p2a-local-artifact-store — 반복 인덱스 (Iteration Index)

<!-- p2a:active-iteration=v1-mvp -->

Progress: [A:complete] -> [B:approved] -> [C:valid] -> [D:passed]

> 정본: iterations/<iter-id>/gate-*, current-spec.json
> 반복 history, close 기준점, handoff 기준점을 누적 렌더링합니다.

## 1. 진행 상태

- 활성 기능 반복: v1-mvp (active)
- maintenance: iterations/maintenance (상시)
- current-spec: current-spec.json (effective → iterations/v1-mvp/gate-b-spec/spec.json)

## 2. 게이트별

### Gate A - Intake decisions

- 상태: present
- 정본 파일: `iterations/v1-mvp/gate-a-intake/intake.json`

### Gate B - Spec approval

- 상태: approval=approved, open_decisions=0
- 정본 파일: `iterations/v1-mvp/gate-b-spec/spec.json`

#### Gate B approval audit

- Approved by: user
- Approved at: 2026-06-28
- Approved artifacts: `iterations/v1-mvp/gate-b-spec/product-spec.md`, `iterations/v1-mvp/gate-b-spec/implementation-plan.md`, `iterations/v1-mvp/gate-b-spec/spec.json`
- Approval note: Gate B approval preserved from greenfield status during iteration init.

### Gate C - Task graph validation

- 상태: 17 task(s)
- 정본 파일: `iterations/v1-mvp/gate-c-task-graph/task-graph.json`

### Gate D - Review blockers

- 상태: blocking_issues=0
- 정본 파일: `iterations/v1-mvp/gate-d-review/review.json`

## 3. 열린 결정 / 반복 목록

- current-spec open_decisions: 0

| 반복 | 상태 | task | 게이트 | 위치 |
| --- | --- | --- | --- | --- |
| v1-mvp | active | 17(todo 13·in_progress 0·done 4·blocked 0) | A✅ B✅(approved) C✅ D✅(blocker 0) | iterations/v1-mvp/ |
| maintenance | 상시 active | 0 (graph 미생성) | task graph only | iterations/maintenance/ |

### Close Audit

아직 close된 반복이 없습니다.

### Handoff Audit

아직 handoff 기록이 없습니다.

## 4. 다음

- 새 기능 → `p2a_iteration open --iteration-id <next> --idea <text>`
- 작은 fix → `p2a_iteration maintenance add ...`
- 검증 → `p2a_iteration validate --artifacts <dir>` (closed iteration archive audit 기본 수행)

## 5. 변경 이력

- status generated from current-spec.json for active iteration `v1-mvp`.
