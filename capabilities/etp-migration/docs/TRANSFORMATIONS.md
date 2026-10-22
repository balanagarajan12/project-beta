# Field transformations

How to configure `fieldMappings` for the migration engine. Behavior below is what `TransformationEngine` does today.

Rules for one entity run in `sequenceNo` order. A later rule that writes the same `targetField` replaces the earlier value. Disabled rules are skipped. `LOOKUP` and `CUSTOM` can be saved and then fail the batch when the worker applies them.

Property names on each rule:

| Property | Required | Notes |
|----------|----------|--------|
| `sequenceNo` | Yes | Integer greater than 0. Unique per mapping |
| `targetField` | Yes | Name written on the target record |
| `transformationType` | Yes | One of the values in the quick reference |
| `entity` | Yes for `ENTITY_RELATIONSHIP` | Entity name from the mapping |
| `sourceField` | Depends on the type | One column, except `CONCAT` (see below) |
| `transformationExpression` | No | Used only by `CONCAT` |
| `defaultValue` | No | Used by `DEFAULT` |
| `configuration` | No | Type-specific object. Omitted means `{}` |
| `mandatory` | No | Default `false`. After transform, a null or blank target value fails validation |
| `enabled` | No | Default `true` |

Send the list as `fieldMappings` on `POST /api/v1/migration-configurations` (the JSON property `rules` is the same list).

```json
{
  "fieldMappings": [
    {
      "sequenceNo": 1,
      "entity": "GeographicAddress",
      "sourceField": "street_name",
      "targetField": "street_name",
      "transformationType": "DIRECT",
      "enabled": true
    }
  ]
}
```

Source values in the examples:

```json
{
  "addr1": "123 Main St",
  "padded": "  123 Main St  ",
  "city": "salem",
  "state": "TN",
  "zip": "636001",
  "blank": "  ",
  "combined": "123 Main St, Salem",
  "messy": "123   Main    St",
  "opened": "09/20/2026",
  "street_name": "Maple"
}
```

CONCAT examples that name both `street_name` and `city` use `city = "Austin"` instead of `salem`.

A missing source column is null. The engine does not trim a value unless the type says it does.

---

## Quick reference

| `transformationType` | Configure | Result |
|----------------------|-----------|--------|
| `DIRECT` | `sourceField` | Same value, or `null` if the column is missing |
| `TRIM` | `sourceField` | Trimmed string, or `null` if the column is missing |
| `UPPERCASE` | `sourceField` | Upper case (`Locale.ROOT`), or `null` |
| `LOWERCASE` | `sourceField` | Lower case (`Locale.ROOT`), or `null` |
| `DEFAULT` | `defaultValue`, optional `sourceField` | `defaultValue` when the source is null, missing, or blank. Otherwise the source value |
| `CONCAT` | `transformationExpression` | Joins fields and quoted text. `street_name + ' - ' + city` → `Maple - Austin` |
| `CONCAT` | `configuration.sourceFields` and `separator`, no expression | Joins those columns with `separator` |
| `CONCAT` | `sourceField` `street_name,city`, no expression and no `separator` | `MapleAustin` |
| `SPLIT` | `configuration.delimiter` and `configuration.targetFields` | One trimmed value per target name |
| `REGEX` | `configuration.pattern`, optional `replacement` | Java replace-all |
| `CONDITIONAL` | `configuration.condition.operator` plus `thenValue` and `elseValue` | One of the two result values |
| `DATE_FORMAT` | `configuration.sourceFormat` and `targetFormat` | Reformatted calendar date, or `null` when the source is blank |
| `LOOKUP`, `CUSTOM` | Do not use | `MIGRATION_UNSUPPORTED_TRANSFORMATION` at apply time |

---

## DIRECT

Copies one column onto `targetField`. The value is not trimmed.

```json
{
  "sequenceNo": 1,
  "entity": "GeographicAddress",
  "sourceField": "addr1",
  "targetField": "streetName",
  "transformationType": "DIRECT"
}
```

`addr1 = "123 Main St"` → `streetName = "123 Main St"`. A missing column → `null`.

---

## TRIM

Same copy as `DIRECT`, then `trim()`.

```json
{
  "sequenceNo": 2,
  "entity": "GeographicAddress",
  "sourceField": "padded",
  "targetField": "streetName",
  "transformationType": "TRIM"
}
```

`"  123 Main St  "` → `"123 Main St"`. A missing column stays `null`, not `""`.

---

## UPPERCASE and LOWERCASE

Case change uses `Locale.ROOT`. The value is not trimmed. `null` stays `null`.

```json
{
  "sequenceNo": 3,
  "entity": "GeographicAddress",
  "sourceField": "city",
  "targetField": "city",
  "transformationType": "UPPERCASE"
}
```

`salem` → `SALEM`.

```json
{
  "sequenceNo": 4,
  "entity": "GeographicAddress",
  "sourceField": "state",
  "targetField": "state",
  "transformationType": "LOWERCASE"
}
```

`TN` → `tn`.

---

## DEFAULT

When the source is null, missing, or blank (including `"  "`), the target is `defaultValue`. Otherwise the target is the source value, not a trimmed copy.

```json
{
  "sequenceNo": 5,
  "entity": "GeographicAddress",
  "sourceField": "blank",
  "targetField": "state",
  "transformationType": "DEFAULT",
  "defaultValue": "UNKNOWN"
}
```

`"  "` → `"UNKNOWN"`. `state = "TN"` with `sourceField` `state` → `"TN"`.

Omit `sourceField` when the target is always the constant. The source is then treated as blank, so the target is `defaultValue`.

---

## CONCAT

Two ways to configure it. If `transformationExpression` is non-blank, that expression is the output. `separator` is not used. If the expression is omitted or blank, the engine joins columns with `configuration.separator` (default `""`).

### Which columns are read

1. `configuration.sourceFields`, when it is a non-empty JSON array, is the column list. A comma-separated `sourceField` is ignored.
2. Otherwise a `CONCAT` `sourceField` of `street_name,city` is the two columns `street_name` and `city`. Spaces around each comma are trimmed. Empty pieces are dropped.
3. For every other transformation type, `sourceField` is a single column name. A comma is not special.
4. When `sourceFields` is absent, field names inside the expression are also copied from the source row.

`sourceFields` must be an array. A string is not read as a list.

### Expression

```
value ( '+' value )+
value = identifier | 'quoted text' | "quoted text"
identifier = letter or '_', then letters, digits, or '_'
```

Whitespace between `+` and the values is ignored. Whitespace inside quotes is kept. `street_name + ' - ' + city` is the three characters space, hyphen, space between the values.

There is no other operator. Parentheses, functions, comparisons, and qualified names such as `a.b` are rejected with `MIGRATION_TRANSFORMATION_ERROR` and message `CONCAT expression is invalid`.

A backslash inside quotes escapes the next character. `'\''` is one single quote. That escape path is implemented and has no test yet.

A null field is left out. The quoted text around it stays. A blank string such as `"  "` is kept. An unknown field name is null. It does not fail the rule.

### `street_name + ' - ' + city`

`street_name = "Maple"`, `city = "Austin"`.

```json
{
  "sequenceNo": 6,
  "entity": "AddressLocation",
  "sourceField": "street_name,city",
  "targetField": "name",
  "transformationType": "CONCAT",
  "transformationExpression": "street_name + ' - ' + city"
}
```

Output: `Maple - Austin`.

`city` null → `Maple - ` (the trailing space is the literal).

### `street_name + '-' + city`

Same rule with:

```json
"transformationExpression": "street_name + '-' + city"
```

Output: `Maple-Austin`. The hyphen-only literal uses the same quoted-text path as the tested `'/'` expression (`Maple/Austin`). This exact hyphen string is not asserted in a test.

### `city + ', ' + street_name`

```json
"transformationExpression": "city + ', ' + street_name"
```

Output: `Austin, Maple`. The tested order is the reverse, `street_name + ', ' + city` → `Maple, Austin`. Pieces are appended from left to right. This reversed example is not a separate test.

Other tested literals: `street_name+'/'+city` → `Maple/Austin`.

### `sourceFields` and `separator` (no expression)

```json
{
  "sequenceNo": 8,
  "entity": "GeographicAddress",
  "sourceField": "street_name,city",
  "targetField": "full",
  "transformationType": "CONCAT",
  "configuration": {
    "sourceFields": ["addr1", "city", "state", "zip"],
    "separator": ", "
  }
}
```

`addr1 = "123 Main St"`, `city = "salem"`, `state = "TN"`, `zip = "636001"` → `123 Main St, salem, TN, 636001`.

`sourceField` is `street_name,city` and is not used, because `sourceFields` is set.

A null column becomes an empty piece and the separator is still written. That null case is not covered by a test. A blank `"  "` is kept as text.

### Comma-separated `sourceField` and no expression

```json
{
  "sequenceNo": 7,
  "entity": "AddressLocation",
  "sourceField": "street_name,city",
  "targetField": "name",
  "transformationType": "CONCAT"
}
```

Output: `MapleAustin`. The comma only splits names. It is not a separator. There is no default hyphen.

### Invalid CONCAT

| Configuration | Result |
|---------------|--------|
| Expression `street_name +` or `street_name city` or an unclosed quote | `CONCAT expression is invalid` |
| No expression, no `sourceField`, and no `sourceFields` | `CONCAT requires sourceFields` |
| Unknown field in an otherwise valid expression | Not an error. That piece is empty |

These three failures are what the parser does. They are not asserted by a test yet.

---

## SPLIT

Splits one source value on a literal delimiter (not a regular expression). Each piece is trimmed. The rule `targetField` is still required by the API. It is not written unless that name is also in `targetFields`.

```json
{
  "sequenceNo": 9,
  "entity": "GeographicAddress",
  "sourceField": "combined",
  "targetField": "unused",
  "transformationType": "SPLIT",
  "configuration": {
    "delimiter": ",",
    "targetFields": ["streetName", "city"]
  }
}
```

`"123 Main St, Salem"` → `streetName = "123 Main St"`, `city = "Salem"`.

A null source sets every name in `targetFields` to `null`. A missing piece is `null`. A piece that is empty after trim is `""`. An empty `delimiter` or a missing `targetFields` array fails with `SPLIT requires delimiter and targetFields`. Those edges are not covered by a test.

---

## REGEX

`pattern` is a Java regular expression. `replacement` defaults to `""` and follows `Matcher.replaceAll` (`$` and `\` are special in the replacement).

```json
{
  "sequenceNo": 10,
  "entity": "GeographicAddress",
  "sourceField": "messy",
  "targetField": "streetName",
  "transformationType": "REGEX",
  "configuration": {
    "pattern": "\\s+",
    "replacement": " "
  }
}
```

`"123   Main    St"` → `"123 Main St"`. A null source → `null`.

Missing `pattern` → `REGEX requires pattern`. Pattern `[` → `REGEX pattern is invalid`. Those errors are not covered by a test.

---

## CONDITIONAL

`configuration.condition` is required. `condition.operator` is compared after upper case (`equals` and `EQUALS` are the same). `condition.sourceField` defaults to the rule `sourceField`. If `thenValue` or `elseValue` is omitted, that branch writes `null`.

```json
{
  "sequenceNo": 11,
  "entity": "GeographicAddress",
  "sourceField": "state",
  "targetField": "stateName",
  "transformationType": "CONDITIONAL",
  "configuration": {
    "condition": {
      "operator": "EQUALS",
      "sourceField": "state",
      "value": "TN"
    },
    "thenValue": "TENNESSEE",
    "elseValue": "OTHER"
  }
}
```

`state = "TN"` → `TENNESSEE`. Any other value → `OTHER`.

| `operator` | True when | Tested |
|------------|-----------|--------|
| `EQUALS` | String forms are equal. `null` and `""` both become `""` | Yes |
| `NOT_EQUALS` | `EQUALS` is false | No |
| `IS_NULL` | The source value is Java `null`. `""` is not null | No |
| `IS_NOT_NULL` | The source value is not `null` | No |
| `IS_BLANK` | `null` or whitespace | No |
| `IS_NOT_BLANK` | Not blank | No |

Any other operator → `CONDITIONAL operator is not supported`. A missing `condition` object → `CONDITIONAL requires condition`.

---

## DATE_FORMAT

Parses the trimmed source as a calendar date (`LocalDate`) and writes the formatted string. Pattern letters are `DateTimeFormatter` letters (`MM` is month, `mm` is minute). A time in the source text is not parsed by this type.

```json
{
  "sequenceNo": 12,
  "entity": "GeographicAddress",
  "sourceField": "opened",
  "targetField": "openedOn",
  "transformationType": "DATE_FORMAT",
  "configuration": {
    "sourceFormat": "MM/dd/yyyy",
    "targetFormat": "yyyy-MM-dd"
  }
}
```

`09/20/2026` → `2026-09-20`. Null or blank source → `null`.

Missing either format → `DATE_FORMAT requires sourceFormat and targetFormat`. An unparseable value or a bad pattern → `DATE_FORMAT value is invalid`. Those errors are not covered by a test.

---

## LOOKUP and CUSTOM

The API and the database accept these names. `TransformationEngine` rejects them before writing a value.

```json
{
  "sequenceNo": 13,
  "entity": "GeographicAddress",
  "sourceField": "city",
  "targetField": "city",
  "transformationType": "LOOKUP"
}
```

Error code `MIGRATION_UNSUPPORTED_TRANSFORMATION`. Message: `Transformation type LOOKUP is not supported`. `CUSTOM` uses the same failure with `CUSTOM` in the message.

---

## Mandatory fields

`mandatory: true` is checked after the transform, on `targetField`. Null or blank fails with `MIGRATION_VALIDATION_ERROR` and message `Mandatory target field is missing`. The record is not loaded. A non-mandatory blank value is stored.

```json
{
  "sequenceNo": 2,
  "entity": "GeographicAddress",
  "sourceField": "street_name",
  "targetField": "streetName",
  "transformationType": "TRIM",
  "mandatory": true
}
```

---

## What is covered by tests

`TransformationEngineTest` asserts the happy path for each executed type, these CONCAT cases, and rejection of `LOOKUP` and `CUSTOM`:

- `sourceFields` + `separator` `", "` → `123 Main St, salem, TN, 636001`
- `sourceField` `street_name,city` and no expression → `MapleAustin`
- `street_name + ' - ' + city` → `Maple - Austin`
- `street_name+'/'+city` → `Maple/Austin`
- `street_name + ', ' + city` → `Maple, Austin`
- expression with `city` null → `Maple - `

`ValidationEngineTest` asserts that a blank mandatory target fails and a present value does not.

Items marked “not covered by a test” in the sections above are still what the current methods do. Do not treat `LOOKUP`, `CUSTOM`, or operators other than `+` and the six `CONDITIONAL` names as available.
