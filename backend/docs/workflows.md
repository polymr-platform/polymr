# Workflows and Recovery

## Overview

Polymr treats a workflow definition as a static graph blueprint. Workflow runs hold runtime state,
checkpoints, and current node metadata. Conversation workflows are implemented as a LangGraph-style
flow with checkpoints persisted to `workflow_run_checkpoints`.

## Workflow Definitions

`workflow_definitions` stores:

- `definition_json`: graph blueprint (type, start node, node definitions)
- `start_trigger`: how the workflow starts (`USER_PROMPT`, `LLM_PROMPT`, `SYSTEM_EVENT`, `MANUAL_START`)

For the default Conversation workflow, the definition JSON is a LangGraph-style graph:

```json
{
	"type": "graph",
	"name": "conversation",
	"start": "user_input",
	"nodes": {
		"user_input": {
			"type": "user_input"
		},
		"llm": {
			"type": "llm"
		},
		"tool_exec": {
			"type": "tool_exec"
		}
	},
	"edges": {
		"user_input": [
			"llm"
		],
		"llm": {
			"default": [
				"user_input"
			],
			"tool_exec": [
				"tool_exec"
			]
		},
		"tool_exec": {
			"default": [
				"llm"
			]
		}
	},
	"recovery": {
		"llm": "user_input",
		"tool_exec": "llm"
	}
}
```

## Workflow Runs

`workflow_runs` stores the current node and the latest checkpoint snapshot.

- `current_node`: last executed node
- `checkpoint_json`: last checkpoint state
- `status`: `RUNNING` or `PAUSED`

Checkpoints are stored in `workflow_run_checkpoints` with a step index, node id, and state JSON.

## Conversation Flow (Nodes)

Conversation is modeled as a loop with nodes:

1. `user_input` node
   - Accepts a user message and appends it to the session history.
   - Checkpoint is saved with `next=llm`.

2. `llm` node
   - Executes a streaming LLM call using the current message history.
   - On completion, appends the assistant message and checkpoints `next=user_input`.
   - Session status is set to `NEEDS_INPUT`.

3. `tool_exec` node
   - Placeholder for tool execution (tool calls are routed here).

## Recovery State

On restart or stale-runtime recovery, interrupted workflow runs are recovered from the last persisted checkpoint.
Recovery is cluster-aware: a server only recovers runs that were assigned to that same runtime server, or runs
without a runtime assignment during local startup recovery.

For recoverable runs:

- `checkpoint_json.status` is set to `recovery`
- `workflow_runs.status` is set to `QUEUED`
- `sessions.status` is set to `ACTIVE`
- `sessions.locked` is set to `true` while the run is expected to continue automatically

Recovery resumes from the last persisted checkpoint instead of waiting for a new user message.
This means:

- interrupted LLM turns are replayed from the last checkpoint
- interrupted tool executions are replayed from the last checkpoint
- user-input waits remain paused and unlocked
- pending tool approvals are surfaced again and remain paused/unlocked until a decision is made

If recovery reaches a paused approval or user-input state, the session is unlocked again as part of normal
checkpoint application. Otherwise the workflow continues through the regular execution path.

### Tool replay considerations

The current policy is to err on the side of replaying tool calls during recovery. This is the safest default for
most workflows because it allows unattended or worker-style flows to continue from the last durable checkpoint.

The main risk is non-idempotent tool calls that already fired externally but crashed before their result was
persisted. In that case recovery may replay the call.

This behavior may become more nuanced later, for example by allowing per-tool configuration to opt out of replay
or to require a different recovery strategy.

## Checkpointing

Every node transition writes a new `workflow_run_checkpoints` row and updates
`workflow_runs.checkpoint_json` to the latest state.

State JSON includes:

```json
{
  "status": "normal" | "recovery",
  "next": "user_input" | "llm",
  "updated_at": "2026-03-01T12:34:56Z"
}
```
