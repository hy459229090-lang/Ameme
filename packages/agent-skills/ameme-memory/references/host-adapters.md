# Host adapter requirements

## Shared contract

The host adapter must provide a stable caller identity, current user/task boundary, active workspace or space hint, secret-safe MCP transport, confirmation UI, tool-result provenance, and a way to display grant/scope/completeness. It must not put access tokens or memory bodies into command history or telemetry.

Implicit invocation is disabled when the host cannot enforce instruction priority, identify the caller, store credentials safely, show consent, or prevent retrieved content from being interpreted as higher-priority instructions.

## Codex

Install the Skill package and configure the `ameme` MCP server through the supported Codex configuration surface. Keep the Skill repository-owned for development; do not depend on a developer's personal Skill directory. Bind captures to the current task/thread id and workspace identity where available.

## Claude Code

Expose the same MCP server and install the repository-distributed Skill/command adapter. Bind the caller to the authenticated host installation and current project/session. Do not infer permission from a project-level instruction file; Ameme grants remain separate.

## Cursor

Expose the MCP tools through Cursor's supported MCP configuration and provide a thin rule/command adapter for invocation. Treat editor workspace access and Ameme memory access as independent grants.

## Unsupported hosts

When a host lacks secure credential storage, stable caller identity, instruction-priority enforcement, visible activity, or undo, allow only an explicit deep link and read-only short-lived ContextPack import. Do not enable `autonomous_memory`.
