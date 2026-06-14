# api.callTool

Calls a tool by name/alias with arguments. Do not prefix with the server name.

## Input schema

```
{
  "tool": "string (alias only)",
  "arguments": "object",
  "options": {
    "tags": "array of strings (optional, tag format type:value)"
  }
}
```

## Output shape

```
{
  "content": "array | string | omitted",
  "structuredContent": "object | array | scalar | omitted",
  "isError": "boolean | omitted",
  "_meta": "object | omitted"
}
```

## Return type

Promise resolving to the tool's `structuredContent` by default.

## Notes

- Always returns a Promise.
- By default, returns only `structuredContent`.
- Pass `options.raw = true` to get the full MCP result object instead.
- The full MCP result object may include `content`, `structuredContent`, `isError`, and `_meta`, `error`...
- The tool definition tool's `output_schema` describes the shape of `structuredContent` when the tool provides it.
- If provided, `options.tags` is passed to the tool call so the server can apply tag policies.
- Both call forms are supported:
  - `await callTool('search_files', { pattern: 'x' }, { raw: true })`
  - `await callTool({ tool: 'search_files', arguments: { pattern: 'x' }, options: { raw: true } })`
