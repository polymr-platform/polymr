## Polymr Page Editing

When you edit workspace pages, each page is a Vue single-file component stored at a path ending in `.vue`.

Use page paths like:

```text
orders/overview.vue
admin/users/detail.vue
```

Pages can depend on other pages or shared runtime imports, so prefer targeted edits and read the current page content before making large changes.
Read page metadata only when you specifically need runtime configuration such as route suffix, query params, input params, icon, page type, component usage guide, or import allowlist.

Import page API helpers from `@polymr/api`.
Import other workspace pages by their page path using `@polymr/pages/<path>`.

Example:

```js
import api from '@polymr/api'
import { callScript, callTool, getUsers, navigateTo, notify } from '@polymr/api'
import RecipeCard from '@polymr/pages/components/RecipeCard.vue'
```

### callTool

The API provides a `callTool` javascript function. This can be used in the pages themselves to call any of the available tools listed in the design catalogue.

By default, `callTool(...)` returns only the tool's `structuredContent`.
Pass `raw: true` when you need the full MCP tool response object.

Example returning `structuredContent`:

```js
const result = await callTool({
	tool: "pages.list",
	arguments: {
		workspace_id: workspaceId
	}
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

### callScript

The API also provides a `callScript` javascript function. This can be used in the pages themselves to run a groovy script by its path.

Example:

```js
const session = await callScript('helpers/startSession.groovy', {
	title: 'Design review',
	participant_ids: selectedUserIds.value,
})

if (!session?.id) {
	console.error('Script call did not return a session id')
}
```

### Attachments

The API also provides `uploadAttachment` and `createAttachmentUrl` javascript functions.
Use uploadAttachment to persist a File or Blob and get back a durable blob reference in the form `blob:/workspaceId/hash`.
Use createAttachmentUrl when you need a short-lived URL that can be rendered in the browser or shared with another service.

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

### Navigation

The API also provides a `navigateTo` javascript function. Use the Polymr page path, not a frontend URL.

Example:

```js
await navigateTo('recipes/detail.vue', { id, tab: 'ingredients' })
```

Path parameters and query parameters both come from the second argument:

- Path parameters are the keys referenced by the page route suffix, such as `id` in a suffix like `/{id}`. These values are inserted into the URL path.
- Query parameters are any remaining keys that are not used by the route suffix. These values are added to the query string.

So if the page has a route suffix like `/{id}`, this example navigates to that page detail route with `id` in the path and `tab=ingredients` in the query string.

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
