# 이 run 읽는 법 (plan2agent-memory)

Feature Radar existing-project 조사 run이다. 파일은 결과 / 근거 / 범위 / 원본으로 나뉜다.
파일은 일부러 flat 구조로 둔다 — `tools/radar_run.py validate`와 `tools/radar_handoff.py`가
flat 구조를 전제로 동작하므로 하위 폴더로 옮기지 말 것.

## 결과 (여기만 봐도 "다음에 뭘 고도화할지" 나옴)
- collection-report.md — 최상위 요약. 판정(Verdict) + 권장 방향 3계층. 바쁘면 이거 하나.
- next-iteration-recommendations.md — 11개 후보 우선순위 표(action/비용/리스크/신뢰도/다음 단계). 실제 착수 목록.

## 근거 (추천이 왜 그런지 역추적용)
- research-bundle.md — 분석 본문(로컬 vs 외부 비교, 해석).
- signal-map.md — 신호(S1~S23) ↔ 출처/함의/신뢰도 매핑.
- source-candidates.md — 출처 레지스트리(LOCAL/WEB/GH) + URL + 신뢰도.
- local-project-scan.md — 로컬 코드 근거(path:line).
- capability-gap-analysis.md — 현재 구현 vs 외부 신호 already/partial/missing 대조.

## 범위 (결과 문서 아님)
- research-plan.md — 무엇을 조사했고 무엇이 범위 밖인지에 대한 계약.

## 원본 (중간 자료)
- _raw-github-signal/ — GitHub 스캐너 원본 출력.

## 추적 경로
next-iteration-recommendations 추천 → research-bundle 주장 → signal-map 신
로컬 항목은 → local-project-scan 의 path:line.

읽는 순서: collection-report → next-iteration-recommendations (결정) → 필  .
