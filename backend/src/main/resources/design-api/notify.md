# api.notify

Shows a toast notification to the user.

## Input schema

```
{
  "message": "string",
  "variant": "info|success|warning|error" (optional)
}
```

## Output schema

```
{}
```

## Return type

Promise resolving when the notification is shown.

## Notes

- Always returns a Promise.

## Example

```js
await api.notify({ message: 'Saved!', variant: 'success' })
```
