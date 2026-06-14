## callTool

Call a backend MCP tool by its name/alias (no server prefix). Use this from Groovy scripts via the `api` object. The tool name is `callTool` (no `api.` prefix) when requesting the definition.

### Signature

```groovy
api.callTool(tool, arguments = null, options = null)
```

### Parameters

- `tool` (string | map) - Tool name/alias or an object with `tool` and `arguments`.
- When asking for the definition, use `callTool` (not `api.callTool`).
- `arguments` (map | null) - Arguments for the tool.
- `options` (map | null) - Optional metadata. Supports `tags` (array or string).

### Return value

By default, returns only the tool's `structuredContent`.
Pass `options.raw = true` to get the full MCP tool response object instead.
When a tool definition includes an `output_schema`, that schema describes the shape of the `structuredContent` field.

Typical fields in the raw response include:

- `content`
- `structuredContent`
- `isError`
- `_meta`

### Example

```groovy
def response = api.callTool(
  "list_customers",
  [status: "active"],
  [tags: ["environment:prod"]]
)

return [results: response]
```

Raw response example:

```groovy
def response = api.callTool(
  "list_customers",
  [status: "active"],
  [raw: true, tags: ["environment:prod"]]
)

return [structured: response.structuredContent, raw: response]
```
