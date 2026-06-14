# api.callScript

Run a released Groovy script by path in the current workspace.

## Signature

```js
await callScript(path, input = null)
```

## Parameters

- `path` (string) - Script path. Slugs also work, but pages should prefer paths because that is how the design model knows scripts.
- `input` (object | null) - Input payload for the script.

## Return type

Promise resolving to the script result.

## Notes

- Matches the backend script helper shape: `api.callScript(path, input = null)`.
- The backend accepts either a script path or slug and resolves both to the same script.
- The script must exist in the current workspace and have a released version.
