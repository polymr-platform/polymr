## createSession

Create a new conversation session in the current workspace.

### Signature

```groovy
api.createSession(payload)
api.createSession(title, context = null, participants = null)
```

### Parameters

- `payload` (map) - Session payload. Matches the backend `sessions.create` service schema.
- `title` (string) - Session title.
- `context` (map | null) - Optional context object.
- `participants` (list | null) - Optional list of participant user IDs. If provided and non-empty, the session is PRIVATE and the list is used as the whitelist. If null/empty, visibility defaults to WORKSPACE.

### Example

```groovy
def session = api.createSession("Onboarding", [
  summary: "Warm intro for new customer",
  notes: "Focus on integration steps"
])

return [session_id: session?.id]
```

```groovy
def session = api.createSession(
  "Escalation",
  null,
  ["c8f4c9b3-7f4c-4a92-8ea0-5b2e6e7b7a1b"]
)
```
