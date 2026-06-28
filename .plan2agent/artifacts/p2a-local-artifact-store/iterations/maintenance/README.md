# maintenance

작은 fix, 문서 수정, 패치성 변경을 append하는 상시 반복입니다.

task graph는 첫 fix가 생길 때 `gate-c-task-graph/task-graph.json`으로 생성합니다. 빈 task graph는 `.plan2agent/schemas/task-graph.schema.json`의 `tasks` 최소 1개 제약을 위반하므로 만들지 않습니다.
