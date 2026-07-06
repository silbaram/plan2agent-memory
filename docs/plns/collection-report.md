# 수집 리포트

run_slug: plan2agent-memory
title: Plan2Agent Memory 다음 이터레이션 조사
mode: existing-project
local_project: D:/projects/plan2agent-memory
created_at: 2026-07-06T00:00:00+00:00
status: complete

## 판정(Verdict)

Plan2Agent Memory Server의 v1-mvp는 견고하고 잘 테스트되었으며 깔끔하게 계층화된 headless artifact store로, 진짜 on-thesis 강점을 갖췄다 — content-hash 멱등성/dedup, lineage + 스냅샷 버전관리, multi-embedding-set 설계, 그리고 아키텍처 테스트로 강제되는 "임베딩 생성 없음 / agent 없음 / AI 호출 없음" 경계. 가장 레버리지가 큰 다음 이터레이션은 새 제품 표면이 아니라 **검색 품질과 그것을 게이트하는 기반**이다. 두 번째의 더 높은 리스크 트랙 — 되돌릴 수 있는 forgetting/GC와 identity-keyed 구조적 diff — 은 경쟁자가 미루는 진짜 whitespace를 노리나, 검증된 수요가 아니라 개발자 도구 신호에 기대므로, 설계 주도의 수요 게이트 베팅으로 다뤄야 한다.

## 권장 방향

세 계층으로 배열:

1. **Foundation & 품질 (먼저 — 저렴, 고확실, 팀 자체 로드맵과 일치).** ranked keyword 검색(`to_tsvector`/`ts_rank`) 활성화로 기존의 죽은 GIN 인덱스가 제 역할을 하게; task-graph 내부 `document_id` 링크 저장; 1.0 전에 pagination/응답 envelope 결정; bulk insert batch화; 관측성 확대.
2. **검색 품질 코어 (전략적 중심).** RRF fusion으로 hybrid keyword+vector retrieval 추가(table-stakes parity). 작은 재현 가능 retrieval-eval 하네스(고정 corpus recall@k)를 guardrail로 구축 — ANN 작업 전에 안착. 이후 vector 저장을 고정 차원 컬럼 + HNSW 인덱스로 재설계하되 pgvector recall-under-filter 처리(iterative_scan/ef_search, filter 컬럼 인덱스, >2000-dim은 halfvec)를 녹여 넣음. 이것이 유일한 고비용 항목; eval 하네스 없이 배포 금지.
3. **차별화 베팅 (구축 전 검증).** 기존 lineage 위에 되돌릴 수 있는 forgetting/GC + "as-of" 시점 조회 표면, 그리고 companion git-like GUI를 위한 task-graph/스냅샷의 identity-keyed 구조적 diff 서버 측 지원. 둘 다 인큐번트가 열어둔 gap을 노리나, 근거는 mem0/cognee/nbdime에서 개발자가 반복 요청하는 것 — P2A 사용자가 채택/지불한다는 증거가 아님. 실제 코딩 에이전트 팀과 짧은 검증을 먼저.

## 핵심 근거

- **로컬(evidence reviewer가 파일에서 spot-verify):** keyword LIKE가 GIN FTS 인덱스 우회(`PostgresSearchAdapters.kt:357-359` vs `V1__...sql:109-110`); untyped vector 컬럼이 ANN 차단(`V1__...sql:323`); `document_id` null 하드코딩이나 canonical lineage는 COALESCE로 생존(`PostgresStoreAdapters.kt:328-329`, `PostgresSearchAdapters.kt:230`); row-by-row bulk insert(`PostgresStoreAdapters.kt:401-402`). 방향은 `product-spec.md:93,97`에 이미 명시.
- **외부 table-stakes:** hybrid+RRF이 Supabase(docs hybrid-search 레시피), Weaviate, Mem0, Chroma, langchain4j 전반에서 표준.
- **외부 pgvector 제약:** filter-then-ANN recall(#259, #761), 2000-dim cap(#461), native hybrid 연산자 없음(#941).
- **외부 차별점 신호(개발자 도구 신호일 뿐):** forgetting/GC를 mem0/letta/graphiti/cognee가 미룸; temporal 버전관리 요청(graphiti #1166); 구조적 diff/identity(nbdime #303, dolt #3468, jsondiffpatch #79).

## 리스크

- **개발자 신호 ≠ 시장 수요.** 모든 외부 "사람들이 X를 계속 요청한다"는 약 4개 OSS 커뮤니티에서 옴. 엔지니어링 관심을 증명할 뿐 채택/지불 의사가 아님. 특히 3계층 베팅은 별도 검증 필요.
- **Spring AI의 gap에서 경쟁 우위를 읽으면 안 됨.** P2A는 headless artifact store이지 Spring AI 대체재가 아님; hybrid/rerank은 parity이지 moat가 아님. (이 과대 해석은 초안에 있었고 제거함.)
- **pgvector 지뢰는 설정 landmine**(recall-under-filter, dim cap, update-churn recall 저하)이며 사전에 설계하지 않으면 "버그"로 표면화.
- **ANN 재설계는 진짜 고비용**(스키마 마이그레이션)이며 eval 하네스 guardrail 없이 진행 금지; "Low 비용" 품질 항목은 정직하나, rank 2의 비용은 미검증 write-순서 가정에 의존.
- **chat-memory pain이 코딩 에이전트 artifact(task graph/run)에 1:1 전이되지 않을 수 있음**; memory-framework 신호는 인접하나 동일하지 않음.
- **task-graph용 구조적 diff는 niche 안의 niche**(외부 engagement 낮음) — 과투자 전 검증.

## 다음 단계

- 범위 산정 전 두 로컬 가정 spot-verify: (1) corpus가 한국어/CJK 위주인지(rank-1 keyword-ranking 효과와 analyzer 결정에 영향); (2) task-graph insert 시점에 내부 document UUID가 resolvable한지(rank-2 비용에 영향).
- 기존 `KeywordSearchPort` 뒤에서 `ts_rank` keyword ranking 프로토타입, public score는 opaque 유지; ranking 테스트 추가.
- 기존 포트를 융합하는 `tsvector`+vector RRF hybrid use case 프로토타입; per-arm score 노출; 각 arm 대비 벤치마크.
- vector 스키마를 건드리기 전 검색 포트에 연결된 작은 labeled recall@k corpus 구축.
- ANN 마이그레이션 설계: set별 typed 컬럼/partial 인덱스, iterative_scan/ef_search, halfvec 경로, 선택적 필터 하 recall@k.
- 3계층 수요(forgetting/GC, 구조적 diff)를 실제 코딩 에이전트 팀과 검증; 그 후에만 flag 뒤에서 spike.
- 선택적 handoff: 이 run은 요청 시 radar-handoff-packager로 P2A 프로젝트에 export 가능(`radar-native` 또는 `both`) — 여기선 수행 안 함.

## Run 노트

`tools/radar_run.py` init/validate 헬퍼는 실행 불가: 이 Windows 환경은 Microsoft Store Python 스텁만 노출(실제 인터프리터 없음)하므로, run 디렉토리와 `status: complete` 헤더를 도구 스키마에 맞춰 직접 작성했다. github-signal-scanner가 처음 별도의 `p2a-artifact-store` run을 작성했으며, 그 출력은 `_raw-github-signal/` 아래로 이 run에 통합했다. web-source-collector는 의도적으로 생략 — reference-discovery와 github-signal-scanner가 이미 문서 수준 근거를 커버함. 결론은 evidence-reviewer가 도전했고 확정 전 수정함(next-iteration-recommendations.md → "적용된 리뷰 수정" 참고).