## createAttachmentUrl

Create a short-lived URL for an existing blob reference in the current workspace.

### Signature

```groovy
api.createAttachmentUrl(blobUri, options = null)
```

### Parameters

- `blobUri` (string) - Durable blob reference in the form `blob:/workspaceId/hash`.
- `options` (map | null) - Optional metadata like `filename`.

### Return value

Returns a map with camelCase fields:

- `blobUri`
- `hash`
- `filename`
- `mimeType`
- `sizeBytes`
- `url`
- `expiresAt`
