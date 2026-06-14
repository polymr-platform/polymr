# Pages: Dynamic SFC Design

## Goals

- Provide dynamic Vue SFC pages/components with a draft -> test -> approve lifecycle.
- Allow users to opt-in to pages; menu shows only installed, menu-visible pages.
- Use a curated design catalog (fixed JSON + dynamic entries).
- Enforce import allowlists and safe usage patterns.

## Core Concepts

- Page types:
  - Primary page: routable and menu-visible.
  - Subpage: routable, menu-hidden.
  - Component: non-routable, importable only.
- Routes start with `pages/<slug>` with optional params (e.g. `pages/orders/{orderId}`).
- Dependencies are explicit and recursive (list page can depend on detail page; detail can depend on a component).

## Data Model

- `sfc_pages`
  - id, workspace_id, name, description, slug
  - type: `page` | `component`
  - menu_visible (page only)
  - route_path (e.g. `pages/<slug>` + optional param suffix)
  - active_version_id
  - created_by, created_at, updated_at
- `sfc_page_versions`
  - id, page_id, status: `draft` | `compiled` | `approved` | `rejected`
  - source_sfc, compiled_bundle, compile_errors
  - created_by, created_at, approved_by, approved_at
- `sfc_page_dependencies`
  - page_id, depends_on_id (page or component)
- `sfc_page_installations`
  - user_id, page_id, installed_at

## Lifecycle

- Draft created on demand when entering edit mode.
- Design session exists only during drafting; ends on approval or draft deletion.
- Test compiles in browser; success unlocks approval.
- Approve promotes compiled draft to active version.
- WebSocket event after approve: refresh menu/page.

## Design Session

- Embedded session view (familiar UI) in a page editor shell.
- Clean session semantics: create/reuse active draft session; archive on approval or delete draft.
- Design-only tools; strict prompt rule: do not call non-design tools.

## Tabbed Editor Layout

- Design: embedded conversation session.
- Source: view latest draft source.
- Test: compile in browser, show errors or render inline preview.
- Overview: always available; approval + dependency editor + third-party import allowlist.

## Catalog (Fixed + Dynamic)

- Fixed catalog (shipped JSON):
  - Whitelisted Polymr components + props/slots/examples
  - CSS variables and usage guidance
  - Utilities (markdown, formatting, etc.)
  - Helper API functions (no REST endpoints exposed)
- Dynamic catalog (generated per workspace/page):
  - Custom pages/components with config options
  - Third-party import allowlist
- MCP tools provided via tool specs; not embedded in catalog text.

## Helper API (Frontend)

- Prefer JS helper functions over raw REST in design prompts:
  - `api.getUsers()`, `api.getWorkspace()`, `api.getSessions()`, etc.
  - `api.callMcpTool(name, args)` (blocked or discouraged in design sessions)

## Security & Guardrails

- Imports:
  - Global built-in allowlist + per-page third-party allowlist.
  - Reject dynamic import/eval.
- No server-side recompile (trust workspace).
- Source always visible to the human steering the LLM.
- Approval required to activate for others; opt-in controls visibility.

## Session Visibility States

- Visible: normal.
- Hidden: never shown in session list.
- Flexible: hidden while locked, becomes visible when unlocked or stuck.

## Open Questions

- Soft-delete or epoch-based cleanup for design session history.
- Approval permissions (any user vs admin).
- Route param syntax conversion (`{id}` vs `:id`).
- Component metadata source (manual vs docgen).
