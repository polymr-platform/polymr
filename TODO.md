# Long term

Alternative approach with lightweight tool descriptions (very lightweight), the ability to search for and resolve more data about tools and typescript for actually executing scripts of tools.
Triggers for workflows, MCP can expose triggers and push events to the server. (perhaps long polling?)

# Short term

compaction! especially for smaller models

clean up tool calls
-> the result should also be small, not "bigger" and white, less whitespace, use an icon to differentiate between request & response
-> if denied, have the request as normal, just a denied in the result

blob store: switch from byte-based blobs to stream-based reads
-> keep eager byte loading where it still makes sense for the database-backed implementation

MCP SSH tunnel + OAuth do not work together yet
-> MCP traffic can use SSH, but OAuth discovery/token flow is not tunnel-aware yet

## MCP Server blacklisting

- Add session-specific MCP server selection using `mcp.deny`, with presets and deny locking once a server has been used.
- Add session configuration checkboxes for workspace MCP servers (checked by default, unchecked = add to `mcp.deny`).
- Disable unchecking for servers already used in the session; show a tooltip explaining why.
- Add preset UI: apply preset to session, save current selection as new preset, overwrite preset.
- Persist presets in workspace scope (name + deny list), apply to new sessions by default when selected.

## Public endpoints

Attachments are already somewhat secure with time limited links for download.
Ui assets are currently unsecured because we want to take advantage of frontend caching of shared resources. The current assumption is that ui assets contain no "sensitive" data as that should be in structuredContent.
May need to revisit both these options in the future. 

- Support script rename/path changes in `polymr_scripts` after refactoring script dependency handling. This includes safe updates for name/path-derived identifiers and any references that depend on them.

## Page runtime external imports

- Keep workspace `external_frontend_imports` as the source of truth for browser-delivered page dependencies.
- Validate entries on save more strictly in the backend: require object shape, `specifier`, optional `version`, `global_name`, `source_url`, optional `integrity`.
- Prefer pinned versions for shared libraries; treat missing version as allowed but discouraged.
- Add workspace detail UI for structured editing instead of raw JSON textarea once the format stabilizes.
- Add backend persistence migration for `polymr.workspaces.external_frontend_imports` in the dev/prod schema setup.
- Add runtime caching/in-flight deduplication for external script loads instead of relying on DOM lookup only.
- Add support for SRI and crossorigin attributes when loading external frontend imports.

## Future ESM migration plan for pages

- Add a page/workspace build-mode toggle: `global` first, `esm` optional.
- Keep `external_frontend_imports` compatible with both modes by storing resolution metadata, not compiled output details.
- Extend dependency entries with ESM-capable URLs or delivery metadata so one workspace record can generate either globals or import maps.
- Emit a second compiler output contract for ESM pages: module source plus any loader metadata, instead of only `compiled_bundle` function bodies.
- Replace `new Function(...)` loaders with dynamic `import()` for ESM mode in both page preview and page runtime.
- Generate import maps from workspace external frontend imports in ESM mode and inject them before module evaluation.
- Decide how `@polymr/pages/...` resolves in ESM mode: import map aliases, generated blob URLs, or explicit rewritten module URLs.
- Keep global mode and ESM mode side-by-side during rollout so workspaces/pages can switch back if needed.
- Add migration checks so a page cannot switch to ESM while using unsupported import forms/runtime features.
- Once ESM is proven, reassess whether global mode remains a compatibility mode or becomes deprecated.
