# etp-exports

Generic export service. It calls any external API (GET/POST/PUT/PATCH/DELETE), turns the JSON response into a table and returns it as a **CSV** or **XLSX** download.

- Java 21, Spring Boot 4.0.x (Jackson 3), Apache POI (streaming SXSSF)
- Package: `src/main/java/com/etp/export`

## Endpoint

`POST /api/v1/exports` → file download (`Content-Disposition: attachment`)

### GET example (everything in path)
```json
{
  "path": "http://order-service:8080/api/v1/orders/C1001?status=OPEN&size=500",
  "httpMethod": "GET",
  "fileType": "csv"
}
```

### POST example (body forwarded as-is)
```json
{
  "path": "http://order-service:8080/api/v1/orders/search",
  "httpMethod": "POST",
  "body": {
    "columns": ["orderId", "customerName", "amount"],
    "fromDate": "2026-09-01",
    "toDate": "2026-09-30"
  },
  "fileType": "XLSX"
}
```

| Field | Required | Notes |
|---|---|---|
| `path` | yes | Full `http(s)://` URL of the external API (relative works only if `etp.export.default-base-url` is set). |
| `httpMethod` | yes | GET / POST / PUT / PATCH / DELETE. `body` is sent only for POST/PUT/PATCH. |
| `body` | no | Any JSON (columns, filters, ...) passed to the external API unchanged. |
| `fileType` | yes | `CSV` / `XLSX` (case-insensitive). |
| `headers` | no | Forwarded to the external API, e.g. `Authorization`. |
| `fileName`, `sheetName` | no | Defaults: `export_yyyyMMdd_HHmmss`, `Data`. |

### Behaviour
- Only the `data` field of the response is used; `action`, `error`, `pagination`, `status`, `timestamp` etc. are ignored.
- File headers = keys of the objects inside `data`, in the order the API returns them. Each object = one row; `null` = empty cell.
- If the response has no `data` field the call fails with 502.
- Nested objects → dotted columns (`customer.name`); arrays of values → `"a, b"`; arrays of objects → JSON text in the cell.
- If the external API already returns `text/csv` or an XLSX file, it is passed through as is.
- XLSX: bold, frozen header with filters, numeric cells typed, rolls over to a new sheet after 1,048,575 rows.
- CSV: UTF-8 with BOM (Excel friendly), RFC 4180 quoting, formula-injection guard (`=`, `+`, `-`, `@`).
- Errors are returned as RFC 7807 `ProblemDetail` (400 validation, 403 host not allowed, 502 upstream error, 504 timeout).

### Try it
```bash
curl -X POST http://localhost:8095/api/v1/exports \
  -H "Content-Type: application/json" \
  -d '{"path":"https://jsonplaceholder.typicode.com/users","httpMethod":"GET","fileType":"xlsx","fileName":"users"}' \
  -OJ
```

### React download snippet
```js
const res = await fetch('/api/v1/exports', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(req) });
const blob = await res.blob();
const name = /filename="?([^";]+)"?/.exec(res.headers.get('Content-Disposition'))?.[1] ?? 'export';
const a = Object.assign(document.createElement('a'), { href: URL.createObjectURL(blob), download: name });
a.click(); URL.revokeObjectURL(a.href);
```

## Configuration (`application.yml`)
`etp.export.connect-timeout`, `read-timeout`, `default-base-url` (optional), `allowed-hosts` (set this outside local dev; supports `*.domain.com`), `max-rows`, `default-sheet-name`, `csv-utf8-bom`.

## Adding to a parent multi-module project
Replace the `spring-boot-starter-parent` block with your parent POM and add `<module>etp-exports</module>` to the parent.
