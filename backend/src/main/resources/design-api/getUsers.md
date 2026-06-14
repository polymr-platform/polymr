# api.getUsers

Returns the list of workspace users.

## Input schema

```
{}
```

## Output schema

```
{
  "users": [
    {
      "id": "uuid",
      "email": "string",
      "name": "string",
      "avatar_url": "string|null"
    }
  ]
}
```

## Return type

Promise resolving to an object with a `users` array.

## Notes

- Always returns a Promise.
- Suitable for populating lists and selects.

## Example

```js
const { users } = await api.getUsers();
```
