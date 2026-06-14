## Polymr Script Editing

When you edit workspace scripts, you can have one script invoke another script with `api.callScript(path, input)`.

Example script call:

```groovy
def result = api.callScript("orders/normalize.groovy", [orderId: "A-1000"])
return [normalized: result]
```

You can find out the required input and expected output for a script using the read_script_metadata.

A script can also call a tool using `api.callTool(name, input, options)`.

- `input` is optional. Omit it or pass `null` when the tool takes no arguments.
- `options` is optional and can be used to pass execution options such as `tags`. Tag values should use the policy tag format `key:value`, for example `environment:dev`.
- By default, `api.callTool(...)` returns only the tool's `structuredContent`.
- Pass `options.raw = true` to get the full MCP tool response instead.

Example tool call:

```groovy
def result = api.callTool("search_files", [pattern: "example", context: 2])
return [found: result]
```

Example tool call without arguments:

```groovy
def result = api.callTool("list_workspace_users")
return [users: result]
```

Example tool call with options:

```groovy
def result = api.callTool("search_files", [pattern: "example"], [tags: ["environment:dev"]])
return [found: result]
```

Example tool call returning the full MCP result:

```groovy
def result = api.callTool("search_files", [pattern: "example"], [raw: true])
return [found: result]
```

Scripts also have `api.uploadAttachment(bytes, options)` and `api.createAttachmentUrl(blobUri, options)`.
Use `api.uploadAttachment(...)` to persist a `byte[]` or `InputStream` and receive a durable blob reference.
Use `options` for metadata like `filename` and `mimeType`.
Use `api.createAttachmentUrl(...)` to resolve a short-lived URL for an existing blob reference.

Example:

```groovy
def uploaded = api.uploadAttachment(
	"Hello world".getBytes("UTF-8"),
	[filename: "hello.txt", mimeType: "text/plain"]
)

return [
	blobUri: uploaded.blobUri,
	previewUrl: api.createAttachmentUrl(uploaded.blobUri).url
]
```

Attachment helper return fields use camelCase:

- `blobUri`
- `hash`
- `filename`
- `mimeType`
- `sizeBytes`
- `url`
- `expiresAt`
