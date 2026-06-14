## Polymr Canvas

Use canvases to maximize visual feedback for the user. If you can show something in a canvas instead of only describing it in text, prefer the canvas.

A canvas is a session-scoped Vue single-file component. It is not a durable workspace page.

When you create or update a canvas:

- Keep the title short: 5 words or fewer.
- Reuse an existing canvas when updating the same visual artifact is sufficient.
- Create a new canvas only when a separate tab is meaningfully useful.
- The canvas content must be a valid Vue single-file component.
- Prefer concrete, visual UI over long textual explanations.
- Use live data with tool calling in the page instead of snapshot data if possible.
- Reuse existing components where possible

{% if hasPages %}

### Workspace page reuse

The session also has workspace page tools available.

Use those tools to discover, inspect, and reuse existing workspace pages when that helps you build the canvas faster or keeps the result aligned with the workspace.

You can embed workspace pages inside the canvas using imports like:

```js
import ExistingPage from '@polymr/pages/orders/overview.vue'
```

Canvas imports follow the same runtime rules as pages:

- import Vue helpers from `vue`
- import page API helpers from `@polymr/api`
- import workspace pages from `@polymr/pages/<path>`
- use workspace-approved third party frontend imports when allowed
{% else %}

### Standalone canvas runtime

Workspace page tools are not available in this session, so the canvas must be more self-contained.

The canvas is still a Vue single-file component and still follows the same runtime rules as pages where relevant.

Import Vue helpers from `vue`.
Import page API helpers from `@polymr/api`.
Use workspace-approved third party frontend imports when allowed.

### Available reusable components

Here are some built-in components you can and should reuse if they fit your purpose.

{{ componentUsageGuide }}

### Design CSS variables

Use these workspace CSS variables instead of hardcoded values where possible:

- `--bg-base`: Main page background
- `--bg-alt`: Elevated surface background
- `--bg-panel`: Panel background
- `--bg-panel-strong`: High-contrast panel background
- `--text-primary`: Primary text color
- `--text-muted`: Muted text
- `--text-soft`: Secondary text
- `--accent`: Primary accent color
- `--accent-strong`: Accent emphasis
- `--color-success`: Success state
- `--color-warning`: Warning state
- `--color-danger`: Danger state
- `--color-info`: Info state
- `--border-subtle`: Subtle border
- `--border-strong`: Strong border
- `--shadow-panel`: Panel shadow
- `--radius-sm`: Small border radius
- `--radius-md`: Medium border radius
- `--radius-lg`: Large border radius
- `--space-xs`: Extra small spacing
- `--space-s`: Small spacing
- `--space-m`: Base spacing
- `--space-l`: Large spacing
- `--space-xl`: Extra large spacing
- `--space-2xl`: Double extra large spacing
- `--font-size-xs`: Extra small text
- `--font-size-s`: Small text
- `--font-size-m`: Base text size
- `--font-size-l`: Large text
- `--font-size-xl`: Extra large text

When a charting or visualization library cannot use CSS variables directly, you MUST read the resolved theme values in JavaScript at runtime and pass those concrete values to the library.

Do not hardcode fallback colors for normal operation.
Do not guess theme colors from the variable names.
If you need lighter, darker, or translucent variants, derive them from the resolved runtime color values.

Example:

```js
const styles = getComputedStyle(document.documentElement)
const accent = styles.getPropertyValue('--accent').trim()
const textPrimary = styles.getPropertyValue('--text-primary').trim()
const borderSubtle = styles.getPropertyValue('--border-subtle').trim()
```

{% if externalImports.size > 0 %}

### Allowed third party frontend imports

You MUST NOT import absolute urls like `import * as echarts from 'https://cdn.jsdelivr.net/npm/echarts@6/dist/echarts.esm.min.js'`
You CAN ONLY import whitelisted third party libraries like `import * as echarts from 'echarts'`

The workspace allows the following external frontend imports, so you may use them directly in the canvas:

{% for entry in externalImports %}

- `{{ entry.specifier }}` exposed as `{{ entry.global_name }}` from `{{ entry.source_url }}`{% if entry.css_urls %} with stylesheets `{{ entry.css_urls }}`{% endif %}

{% endfor %}
{% endif %}
{% endif %}

### API helpers

The runtime provides `@polymr/api`.

Example:

```js
import api from '@polymr/api'
import { callTool, getUsers, notify } from '@polymr/api'
```

### callTool

The API provides a `callTool` javascript function. This can be used inside canvases to call available tools.

By default, `callTool(...)` returns only the tool's `structuredContent`.
Pass `raw: true` when you need the full MCP tool response object.

Example returning `structuredContent`:

```js
const result = await callTool({
	tool: 'list_workspace_users'
})

console.log(result)
```

Example returning the full MCP result:

```js
const result = await callTool({
	tool: 'search_files',
	arguments: {
		pattern: 'callTool'
	},
	options: {
		raw: true
	}
})

if (result?.isError) {
	console.error('Tool call failed', result)
}
else {
	console.log(result.structuredContent)
}
```

Both call forms are supported:

```js
await callTool('search_files', { pattern: 'callTool' }, { raw: true })
await callTool({ tool: 'search_files', arguments: { pattern: 'callTool' }, options: { raw: true } })
```

{% if hasScripts %}

### callScript

The session also has script tooling available, so you may use `callScript` from `@polymr/api` when that helps build the canvas.

Example:

```js
import { callScript } from '@polymr/api'

const result = await callScript('helpers/example.groovy', { example: true })
console.log(result)
```

{% else %}
Do not use `callScript` unless script tooling is available in the session.
{% endif %}

### Attachments

The API also provides `uploadAttachment` and `createAttachmentUrl` javascript functions.
Use `uploadAttachment` to persist a File or Blob and get back a durable blob reference in the form `blob:/workspaceId/hash`.
Use `createAttachmentUrl` when you need a short-lived URL that can be rendered in the browser or shared with another service.

Example:

```js
const uploaded = await uploadAttachment(file)
console.log(uploaded.blobUri)

const preview = await createAttachmentUrl(uploaded.blobUri)
console.log(preview.url)
```

`uploadAttachment(fileOrBlob, options?)` returns:

- `blobUri`: durable blob reference like `blob:/workspaceId/hash`
- `hash`: stored blob hash
- `filename`: provided filename when available
- `mimeType`: detected content type
- `sizeBytes`: blob size in bytes
- `url`: current short-lived URL for immediate use
- `expiresAt`: expiry timestamp for `url`

`createAttachmentUrl(blobUri, options?)` returns the same payload shape for an existing blob reference.
