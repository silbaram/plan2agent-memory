# Plan2Agent Project Harness

This repository owns its Plan2Agent planning and development loop in-place.

## Start a greenfield plan

1. Open Claude Code, Codex, or Gemini in this directory and run:

   `/p2a-harness "<one sentence idea>"`

   Planning Gates A-D write artifacts under `.plan2agent/artifacts/<project>/gate-*`.

2. Convert approved planning artifacts into the iteration structure:

   `node .plan2agent/scripts/p2a_iteration.mjs init --artifacts .plan2agent/artifacts/<project>`

3. Develop from ready tasks and track execution:

   - `node .plan2agent/scripts/p2a_execute.mjs plan|start|finish|status`
   - `node .plan2agent/scripts/p2a_orchestrate.mjs plan|handoff`
   - `node .plan2agent/scripts/p2a_proposals.mjs mine|review|curate|draft-patch|approve-draft|digest`
   - `node .plan2agent/scripts/p2a_tasks.mjs ready|prompt|start|done`
   - `node .plan2agent/scripts/p2a_runs.mjs start|verify|finish`

4. Open the next iteration in this same project:

   `node .plan2agent/scripts/p2a_iteration.mjs open|draft|context|promote-tasks`
