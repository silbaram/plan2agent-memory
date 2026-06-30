# DTO와 비즈니스 코드 평면 배치 원인 검토

## 검토 범위

- 현재 Kotlin package/file 구조
- Plan2Agent Gate B/C 산출물의 architecture 및 task 지시
- 프로젝트에 포함된 AI agent/skill 지시문

## 결론

현재 구조는 hexagonal boundary 자체가 깨진 상태라기보다, MVP task가 `rest adapter` 내부에서 DTO, validation, mapper를 한 번에 구현하도록 지시했고 이를 파일 단위로 세분화하지 않아 `adapter/in/rest` 패키지 안에 DTO와 변환 코드가 크게 모인 상태다. application core는 별도 `application/usecase`, `application/port`, `domain` 패키지에 있고, architecture test도 adapter/framework 의존이 core로 들어오지 않도록 감시한다.

따라서 “DTO와 비즈니스 코드가 평면적으로 섞였다”는 체감의 주 원인은 다음과 같다.

1. REST adapter task의 acceptance criteria가 controller, request/response DTO, validation, response mapping을 한 작업 단위로 묶었다.
2. Gate B architecture는 핵심 패키지를 큰 레이어/어댑터 단위로만 지정하고, REST adapter 내부의 하위 패키지(`dto`, `mapper`, `validation`)까지 강제하지 않았다.
3. `WriteRestDtos.kt`/`QueryRestDtos.kt`가 request/response DTO뿐 아니라 command/domain 변환, 기본값, enum parsing, 필수값 검증 helper까지 포함한다.
4. AI agent/skill 지시문은 “core가 adapter에 의존하지 말라”는 boundary는 강조하지만, adapter 내부 파일 응집도나 패키지 깊이 기준은 별도로 제시하지 않는다.

## 코드 구조 확인

현재 main Kotlin source는 아래 경계로 나뉜다.

- `domain`: 순수 domain model/value object
- `application/usecase`: command/query model과 use case service
- `application/port/in`, `application/port/out`: inbound/outbound port
- `adapter/in/rest`: Spring MVC controller, REST DTO, mapper/validation
- `adapter/out/postgres`, `adapter/out/security`: persistence/security adapter
- `config`: Spring configuration

문제 지점은 특히 `adapter/in/rest`다. 예를 들어 `WriteRestDtos.kt`는 request/response DTO 선언 이후 `toCommand`, `toDomain`, `toResponse`, `requireText`, enum parser까지 한 파일에 둔다. 이 코드는 비즈니스 규칙의 source of truth라기보다 HTTP boundary 변환/입력 검증이지만, 파일 이름과 패키지 구조상 DTO와 변환 로직이 평면적으로 보인다.

반대로 실제 use case business orchestration은 `WriteUseCaseService`에 남아 있다. 예를 들어 project/iteration/document/task 관계 검증, idempotency, dependency validation, transaction boundary는 application service에서 수행한다.

## Plan2Agent 산출물에서 확인한 원인

### Gate B architecture

Gate B spec은 pragmatic hexagonal architecture를 선택하고 핵심 패키지를 `domain`, `application/usecase`, `application/port/in`, `application/port/out`, `adapter/in/rest`, `adapter/out/postgres`, `adapter/out/search`, `adapter/out/security`, `config`로 나누도록 했다. 동시에 REST controller는 request/response DTO를 command/result 객체로 변환하는 inbound adapter라고 명시했다.

하지만 이 지시는 `adapter/in/rest/dto`, `adapter/in/rest/mapper`, `adapter/in/rest/validation`, `adapter/in/rest/controller` 같은 하위 패키지 분리 기준은 요구하지 않는다. 즉, “adapter 내부 평면화”는 architecture 결정의 빈칸에서 발생했다.

### Gate C task graph

Gate C task graph는 write REST adapter task에서 “REST controllers, request/response DTOs, validation, and error mapping”을 한 task에 넣고, acceptance criteria도 “Controllers map HTTP DTOs to write application commands/results”로 둔다. query/search REST adapter도 동일하게 controller, DTO, validation, error mapping을 한 task에 묶는다.

이 task 설계는 구현자가 단일 `WriteRestDtos.kt`, `QueryRestDtos.kt`에 DTO와 mapper/helper를 모으기 쉬운 지시다. 특히 “작업 범위는 rest adapter”까지만 제한되어 있어, 내부 모듈 분할에 대한 피드백 루프가 없다.

## AI agent/skill 지시문에서 확인한 부분

`p2a-review` skill은 spec/task graph의 승인 상태, open decision, dependency cycle, acceptance criteria, source references, technology reconnaissance evidence를 점검한다. 즉, 구조 리뷰 시 “adapter 내부 DTO/mapper 평면화” 같은 코드 organization smell은 필수 체크 목록에 없다.

`p2a-spec` skill은 architecture field를 만들고 technology reconnaissance를 요구하지만, 생성해야 할 package 세분화 규칙은 spec 내용에 의존한다. 현재 spec이 하위 package 기준을 명시하지 않았기 때문에 이후 task graph/implementation agent가 이를 강제받지 않았다.

`p2a-task-author` skill은 task마다 `targetArea`, acceptance criteria, source spec refs를 요구하지만, “한 task가 controller + DTO + validation + mapper를 모두 소유하지 않게 나누라”는 규칙은 없다. 결과적으로 REST adapter 구현 task가 과밀해졌다.

## 위험도 판단

- Blocking architecture violation: 낮음. architecture test가 application/domain core의 adapter/framework dependency 유입을 막고 있다.
- Maintainability risk: 중간. REST DTO 파일이 커지고 변환/validation helper까지 포함하면서 신규 endpoint 추가 시 충돌과 회귀 위험이 증가한다.
- Misplaced business logic risk: 중간. 현재는 relationship/idempotency 등 핵심 규칙이 use case service에 있지만, DTO mapper에 기본값/필수값/enum parsing이 계속 쌓이면 adapter validation과 business validation 경계가 흐려질 수 있다.

## 개선 제안

1. `adapter/in/rest` 내부를 기능 또는 역할 기준으로 분리한다.
   - 역할 기준: `dto`, `mapper`, `validation`, `controller`
   - 기능 기준: `project`, `iteration`, `document`, `task`, `run`, `search`
2. `WriteRestDtos.kt`와 `QueryRestDtos.kt`를 먼저 분할한다.
   - DTO 전용 파일: request/response data class만 유지
   - mapper 파일: `toCommand`, `toResponse`, `toDomain`
   - validation/parser 파일: `requireText`, enum parsing, metadata validation
3. Plan2Agent task authoring 규칙에 “REST adapter 구현 task는 DTO/mapper/validation/controller가 일정 규모 이상이면 별도 file 또는 package로 분리한다”는 acceptance criterion을 추가한다.
4. architecture test는 core boundary만 감시하므로, 필요하면 REST adapter 파일 크기 또는 package rule을 별도 static check로 추가한다.

## 다음 실행 후보

- 저위험 리팩터링: REST adapter 내부 파일만 분리하고 public API/JSON contract는 변경하지 않는다.
- Plan2Agent 개선: `.agents/skills/p2a-task-author/SKILL.md` 또는 project-level guide에 package granularity criterion을 추가해 향후 agent가 같은 구조를 반복하지 않게 한다.
