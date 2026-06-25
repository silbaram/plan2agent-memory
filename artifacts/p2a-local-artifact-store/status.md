# P2A 로컬 산출물 저장 서버 - 상태

## 1. 진행 상태

Progress: [A complete] -> [B current:draft] -> [C pending] -> [D pending]

Gate A intake는 사용자 요구사항을 기반으로 완료되었습니다. Gate B product spec과 implementation plan은 draft로 작성되었고, Gate C task graph는 Gate B 명시 승인 전까지 생성하지 않습니다.

## 2. 게이트별

### Gate A

- 상태: complete
- 산출물: `gate-a-intake/intake.json`, `gate-a-intake/intake.md`
- 근거: `intake.status`는 `ready_for_spec`이며 `needs_user_decision`은 비어 있습니다.

### Gate B

- 상태: current, draft
- 산출물: `gate-b-spec/product-spec.md`, `gate-b-spec/implementation-plan.md`, `gate-b-spec/spec.json`
- 근거: `spec.approval`은 `draft`이고 `spec.open_decisions`는 비어 있습니다.
- 다음 전환 조건: 사용자가 Gate B 산출물을 명시 승인해야 합니다.

### Gate C

- 상태: pending
- 산출물: `gate-c-task-graph/task-graph.json`
- 근거: Gate B가 아직 approved가 아니므로 task graph 생성을 보류합니다.

### Gate D

- 상태: pending
- 산출물: `gate-d-review/review-report.md`, `gate-d-review/review.json`
- 근거: Gate C task graph가 아직 없습니다.

## 3. 열린 결정 / 질문

- 열린 needs_user_decision: 없음
- CQ-1: 외부 ID 우선, 서버 UUID 보조로 가정했습니다.
- CQ-2: cosine 기본값과 exact vector search 우선으로 가정했습니다.
- CQ-3: 같은 sourcePath/artifactType의 다른 content hash는 새 snapshotVersion으로 저장한다고 가정했습니다.

## 4. 다음

사용자가 Gate B product spec과 implementation plan을 검토하고 승인하거나 수정 요청을 줍니다.

## 5. 변경 이력

- 2026-06-26: Gate A intake를 작성하고 `ready_for_spec` 상태로 저장했습니다.
- 2026-06-26: Gate B product spec, implementation plan, spec.json을 draft로 저장했습니다.
