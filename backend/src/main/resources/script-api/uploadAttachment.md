## uploadAttachment

Persist attachment content in the current workspace and return a durable blob reference.

### Signature

```groovy
api.uploadAttachment(bytes, options = null)
```

### Parameters

- `bytes` (byte[] | InputStream) - Attachment payload.
- `options` (map | null) - Optional metadata like `filename` and `mimeType`.

Example:

```groovy
api.uploadAttachment(
	"Hello".getBytes("UTF-8"),
	[filename: "hello.txt", mimeType: "text/plain"]
)
```

### Return value

Returns a map with camelCase fields:

- `blobUri`
- `hash`
- `filename`
- `mimeType`
- `sizeBytes`
- `url`
- `expiresAt`
