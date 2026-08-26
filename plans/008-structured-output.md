# Plan 008: Enforce structured JSON output from Gemini and parse with kotlinx.serialization

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md`.
>
> **Drift check (run first)**: This repo has NO commits yet. Compare live
> code against the "Current state" excerpts below; on mismatch, STOP.

## Status

- **Priority**: P2
- **Effort**: M
- **Risk**: MED (depends on the pinned Gemini SDK supporting responseMimeType)
- **Depends on**: plans/007 (tests must exist first — this refactor is guarded by them)
- **Category**: tech-debt / direction
- **Planned at**: no commits yet (initial staged state), 2026-08-26

## Why this matters

Today the app asks Gemini to "Return ONLY a JSON object" and then hand-strips
markdown fences before parsing. LLMs violate that instruction regularly, and
the string-hack parser silently degrades data (empty strings, 0.0 amounts).
The Google generative AI SDK supports `responseMimeType = "application/json"`
plus a `responseSchema`, which makes the model emit raw JSON. Combined with a
kotlinx.serialization `@Serializable` DTO, parsing becomes declarative,
validated, and covered by plan 007's test suite.

## Current state

- `app/src/main/java/com/example/expense_ai/GeminiManager.kt:10-13, 16-33`:

```kotlin
private val generativeModel = GenerativeModel(
    modelName = "gemini-3-flash-preview",
    apiKey = BuildConfig.GEMINI_API_KEY
)
...
val inputContent = content {
    image(bitmap)
    text(prompt)
}
val response = generativeModel.generateContent(inputContent)
```

No `generationConfig` anywhere.

- `app/src/main/java/com/example/expense_ai/scanner/ExpenseParser.kt` —
  pure parser created by plans/007 (manual jsonObject navigation + fence
  stripping), guarded by `app/src/test/java/com/example/expense_ai/ExpenseParserTest.kt`.

- Dependencies (`gradle/libs.versions.toml`): `googleAi = "0.9.0"`
  (`com.google.ai.client.generativeai:generativeai`), `kotlinxSerialization = "1.6.3"`.

Repo conventions: kotlinx.serialization already on the classpath and the
serialization plugin is applied (`app/build.gradle.kts:7`).

## Commands you will need

| Purpose    | Command                                | Expected on success |
|------------|----------------------------------------|---------------------|
| Compile    | `./gradlew :app:compileDebugKotlin -q` | exit 0              |
| Unit tests | `./gradlew :app:testDebugUnitTest -q`  | exit 0              |

## Scope

**In scope**:
- `app/src/main/java/com/example/expense_ai/GeminiManager.kt`
- `app/src/main/java/com/example/expense_ai/scanner/ExpenseParser.kt`
- `app/src/test/java/com/example/expense_ai/ExpenseParserTest.kt`

**Out of scope**:
- Changing the prompt's requested fields/keys.
- ReceiptScanner call-site signature changes beyond what Step 3 requires.
- Model-name changes.

## Git workflow

- Branch: `advisor/008-structured-output`
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Add a @Serializable DTO

In `ExpenseParser.kt`, add:

```kotlin
@kotlinx.serialization.Serializable
data class ExpenseDraft(
    val date: String = "",
    val amount: Double = 0.0,
    val merchantName: String = "",
    val category: String = "",
    val bankName: String = "",
    val type: String = "EXPENSE"
)
```

Defaults preserve today's lenient behavior for missing keys.

### Step 2: Configure JSON mode in GeminiManager

Change `extractExpenseData` to build the model call with a generation config:

```kotlin
private val jsonModelConfig = com.google.ai.client.generativeai.type.generationConfig(
    responseMimeType = "application/json"
)
```

and construct a second `GenerativeModel` instance `jsonGenerativeModel`
using it (keep the original for `generateResponse`). Use
`jsonGenerativeModel.generateContent(inputContent)` in `extractExpenseData`.
If the SDK 0.9.0 API surface differs from the names above, consult its
sources via your IDE and adapt minimally; do not change libraries.

**Verify**: `./gradlew :app:compileDebugKotlin -q` → exit 0

### Step 3: Simplify the parser

With JSON mode enforced, fences should disappear. Rewrite
`ExpenseParser.parse` as:

```kotlin
fun parse(responseText: String): ParsedExpense? {
    return try {
        val draft = Json { ignoreUnknownKeys = true }
            .decodeFromString(ExpenseDraft.serializer(), responseText.trim())
        ParsedExpense(draft.date, draft.amount, draft.merchantName, draft.category, draft.bankName, draft.type)
    } catch (e: Exception) {
        null
    }
}
```

Keep `ParsedExpense` unchanged so ReceiptScanner needs no edits.

### Step 4: Update tests

In `ExpenseParserTest.kt`:
- Cases 1, 3-6 (plain JSON, defaults, invalid, empty) keep their expectations — they should still pass.
- Case 2 (fenced markdown) now documents NEW behavior: fences are no longer
  stripped, so fenced input returns null. Update the test to assert null with
  a comment: "JSON-mode responses are raw; fences indicate a non-compliant
  response and are treated as parse failure."
- Case 7 (bare fence) likewise asserts null now.
- ADD case 8: unknown extra keys in valid JSON parse fine
  (`ignoreUnknownKeys`) — e.g. an extra `"currency": "THB"` field.

**Verify**: `./gradlew :app:testDebugUnitTest -q` → all pass including updated cases

## Test plan

Covered in Step 4. Manual verification (operator, device): scan one real
receipt, confirm logcat shows successful insert and stored fields look sane.

## Done criteria

- [ ] `./gradlew :app:compileDebugKotlin -q` exits 0
- [ ] `./gradlew :app:testDebugUnitTest -q` exits 0 with ≥8 tests in ExpenseParserTest
- [ ] `grep -n "removeSurrounding" app/src/main/java/com/example/expense_ai/scanner/ExpenseParser.kt` returns no matches
- [ ] `grep -n "responseMimeType" app/src/main/java/com/example/expense_ai/GeminiManager.kt` returns exactly one match
- [ ] No files outside the in-scope list modified (`git status`)
- [ ] No dependency version changes in `gradle/libs.versions.toml`
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back if:

- The SDK at version 0.9.0 does not expose `responseMimeType` (or equivalent) — report the actual API instead of upgrading the library or switching SDKs.
- Plan 007's parser/tests are absent — execute plans/007 first.
- More than trivial API-shape adaptation is needed in Step 2 (beyond renaming) — report findings.

## Maintenance notes

- If the model name changes later, re-verify JSON-mode compliance manually once.
- Reviewer should scrutinize that `generateResponse` still uses the ORIGINAL
  GenerativeModel (plain text chat), not the JSON-configured one.
