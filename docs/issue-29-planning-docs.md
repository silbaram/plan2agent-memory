# P2A 승인 기획 Markdown만 선별 동기화하는 `planning-docs` 프로필

- Source: https://github.com/silbaram/plan2agent-memory/issues/29
- Repository: `silbaram/plan2agent-memory`
- Issue: `#29`
- Captured: 2026-08-17

## 배경

현재 P2A Memory 동기화는 문서뿐 아니라 task, run, evidence, chunk, graph까지 폭넓게 수집할 수 있다. 그러나 초기 목적은 전체 실행 이력을 백업하는 것이 아니라, 다음 개발과 반복 기획에서 다시 참고할 가치가 있는 **승인된 기획 문서**를 저장·검색하는 것이다.

과도한 run/evidence 동기화는 검색 노이즈, embedding 비용, 저장량과 계보 복잡도를 증가시킨다. Phase 1에서는 P2A가 생성하는 Markdown 기획 문서만 선별 동기화하는 편이 목적과 운영 비용에 더 잘 맞는다.

## 목표

P2A 최초 개발과 기능 반복에서 생성된 승인된 Markdown 기획 문서만 Memory 서버로 동기화하는 `planning-docs` 프로필을 제공한다.

Memory는 검색·열람을 위한 파생 저장소이며, 로컬 `.plan2agent` JSON 산출물은 계속 정본으로 유지한다.

## P2A 문서 생성 규칙

### 최초 개발 및 기능 반복

각 iteration에서 다음 문서가 생성된다.

- `gate-b-spec/product-spec.md`
- `gate-b-spec/implementation-plan.md`
- `gate-a-intake/intake.md` — 사용자가 명시적으로 요청한 경우에만 존재

기능 반복의 Gate B Markdown은 이전 기준선을 반영한 delta-first 문서다.

### 유지보수

작은 fix, 문서 수정, 패치성 변경은 `iterations/maintenance`의 task graph 중심으로 처리하며 Gate A/B 기획 Markdown을 다시 생성하지 않는다.

사용자 흐름, API, 데이터 모델, 보안·운영 정책, 성공 기준처럼 제품 의미가 바뀌는 변경은 maintenance가 아니라 새 기능 iteration으로 열어야 하며, 이 경우 Gate B Markdown이 다시 생성된다.

## 제안 동작

예시 CLI:

```bash
p2a memory push \
  --artifacts .plan2agent/artifacts/<project-id> \
  --profile planning-docs \
  --dry-run
```

실제 외부 쓰기는 기존 정책처럼 명시적 승인 옵션을 요구한다.

### 포함

- Gate B가 승인된 iteration의 `product-spec.md`
- Gate B가 승인된 iteration의 `implementation-plan.md`
- 존재하고 Gate A가 완료된 경우에만 `intake.md`
- 최초 iteration과 이후 기능 iteration 모두 포함
- archived iteration도 기획 history로 포함

### 제외

- maintenance task graph와 `iterations/maintenance/README.md`
- task와 run record
- verification, acceptance, visual review evidence
- tool trace, command output, preflight 결과
- `status.md`와 같은 generated index
- baseline 복사본과 handoff 중복본
- Memory search/recall 결과
- 내부 생성 chunk 파일 자체

검색용 chunk는 Memory 서버가 업로드된 Markdown 원문에서 내부적으로 생성하되, P2A 소스 artifact로 재동기화하지 않는다.

## 문서 identity와 metadata

문서의 안정적인 identity는 다음 조합으로 만든다.

```text
projectId + iterationId + documentType
```

최소 metadata:

```json
{
  "projectId": "example-project",
  "iterationId": "iter-001",
  "documentType": "product_spec",
  "gate": "gate-b",
  "approval": "approved",
  "sourcePath": "iterations/iter-001/gate-b-spec/product-spec.md",
  "contentHash": "sha256:...",
  "canonicalJsonPath": "iterations/iter-001/gate-b-spec/spec.json",
  "canonicalJsonHash": "sha256:..."
}
```

## 무결성 규칙

- Markdown은 검색·열람용 projection이며 JSON이 정본이다.
- 업로드 전에 P2A validator로 Markdown과 정본 JSON의 상태를 검증한다.
- Gate B 승인 전 draft는 업로드하지 않는다.
- 동일 identity와 동일 content hash는 다시 저장하지 않는다.
- 동일 identity의 hash가 변경되면 새 snapshot으로 기록한다.
- handoff로 복사된 동일 문서는 source identity/hash를 기준으로 중복 제거한다.
- 기본 검색은 최신 snapshot을 반환하고, iteration history 조회에서는 이전 snapshot을 명시적으로 조회할 수 있어야 한다.

## 완료 조건

- [x] `planning-docs` 선택 정책이 별도 모듈 또는 명시적 프로필로 구현된다.
- [x] 최초 및 반복 iteration의 승인된 `product-spec.md`, `implementation-plan.md`가 선택된다.
- [x] 조건을 만족하는 `intake.md`만 선택된다.
- [x] maintenance, task, run, evidence, generated index와 recall 결과가 제외된다.
- [x] 승인 상태와 canonical JSON hash가 검증된다.
- [x] stable identity와 content hash 기반으로 idempotent push가 보장된다.
- [x] dry-run이 포함·제외 파일, 제외 사유, 예상 snapshot 수를 출력한다.
- [x] 동일 문서의 handoff 복사본이 중복 저장되지 않는다.
- [x] 최초 개발, 기능 반복, maintenance, 승인 전 draft, archived iteration에 대한 테스트가 추가된다.

## 비목표

- run/evidence 장기 보관
- agent 대화 memory
- tool trace 검색
- LLM을 이용한 “의미 있는 문서” 자동 판정
- 유지보수 작업의 전체 이력 동기화
- 로컬 `.plan2agent` 정본 대체

## 기대 효과

- 이전 프로젝트·iteration의 승인 기획을 정확하게 검색할 수 있다.
- 실행 로그가 기획 검색 결과를 오염시키지 않는다.
- 저장·embedding 비용과 운영 복잡도를 줄인다.
- 향후 Cognee, Graphiti, Qdrant 등 외부 backend를 사용하더라도 P2A 쪽에서는 동일한 선별 정책과 metadata 계약을 유지할 수 있다.
