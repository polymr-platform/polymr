## callScript

Run a released Groovy script by slug in the current workspace.

### Signature

```groovy
api.callScript(slug, input = null)
```

### Parameters

- `slug` (string) - Script slug.
- `input` (map | null) - Input payload for the script.

### Example

```groovy
def result = api.callScript("normalize-order", [orderId: "A-1000"])
return [normalized: result]
```
