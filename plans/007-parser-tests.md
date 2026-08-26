# Plan 007: Extract receipt JSON parsing into a pure, unit-tested parser

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
- **Risk**: LOW
- **Depends on**: plans/002 (logging/markFailed call sites in processReceipt)
- **Category**: tests
- **Planned at**: no commits yet (initial staged state), 2026-08-26

## Why this matters

The only nontrivial logic in the app — turning Gemini's text response into an
`Expense` — lives inline inside `ReceiptScanner.processReceipt`, unreachable
by JVM unit tests (the class needs Context). The repo's test suite is two
Android Studio templates; there is no verification baseline for real code.
This plan extracts parsing into a pure Kotlin object and characterizes it
with JUnit tests, establishing the baseline that plans/008 will refactor
against.

## Current state

- `app/src/main/java/com/example/expense_ai/scanner/ReceiptScanner.kt:63-79`:

```kotlin
val cleanJson = jsonResponse.trim().removeSurrounding("```json", "```").trim()

try {
    val json = Json.parseToJsonElement(cleanJson).jsonObject
    val expense = Expense(
        date = json["date"]?.jsonPrimitive?.content ?: "",
        amount = json["amount"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
        merchantName = json["merchantName"]?.jsonPrimitive?.content ?: "",
        category = json["category"]?.jsonPrimitive?.content ?: "",
        bankName = json["bankName"]?.jsonPrimitive?.content ?: "",
        type = json["type"]?.jsonPrimitive?.content ?: "EXPENSE",
        filePath = path
    )
    expenseDao.insertExpense(expense)
} catch (e: Exception) {
    ...
}
```

(If plans/004 landed first, `filePath` is now `imageId`; adapt names.)

- Existing test files are templates:
  - `app/src/test/java/com/example/expense_ai/ExampleUnitTest.kt`
  - `app/src/androidTest/java/com/example/expense_ai/ExampleInstrumentedTest.kt`
- Test deps available (`app/build.gradle.kts:65`): `libs.junit` → junit 4.13.2.
- The Expense entity has a `timestamp: Long = System.currentTimeMillis()`
  default — parser must construct with explicit defaults so tests stay deterministic
  where possible.

Repo conventions: JUnit 4 style (see template ExampleUnitTest.kt); plain
assertions; no mocking library is configured — do NOT add one.

## Commands you will need

| Purpose    | Command                                  | Expected on success |
|------------|------------------------------------------|---------------------|
| Compile    | `./gradlew :app:compileDebugKotlin -q`   | exit 0              |
| Unit tests | `./gradlew :app:testDebugUnitTest -q`    | exit 0              |

## Scope

**In scope**:
- `app/src/main/java/com/example/expense_ai/scanner/ExpenseParser.kt` (create)
- `app/src/main/java/com/example/expense_ai/scanner/ReceiptScanner.kt`
- `app/src/test/java/com/example/expense_ai/ExpenseParserTest.kt` (create)

**Out of scope**:
- `GeminiManager.kt` — network behavior stays untested here.
- Changing parse semantics beyond what extraction requires (behavior-preserving move).
- Adding Robolectric/MockK/any new dependency.
- plans/008's structured-output change — do NOT pre-implement it.

## Git workflow

- Branch: `advisor/007-parser-tests`
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Create the pure parser

Create `app/src/main/java/com/example/expense_ai/scanner/ExpenseParser.kt`
in package `com.example.expense_ai.scanner`, moving the logic verbatim:

```kotlin
object ExpenseParser {
    /** Returns null when the input cannot be parsed into an Expense draft. */
    fun parse(responseText: String): ParsedExpense? {
        val cleanJson = responseText.trim().removeSurrounding("```json", "```").trim()
        return try {
            val json = Json.parseToJsonElement(cleanJson).jsonObject
            ParsedExpense(
                date = json["date"]?.jsonPrimitive?.content ?: "",
                amount = json["amount"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                merchantName = json["merchantName"]?.jsonPrimitive?.content ?: "",
                category = json["category"]?.jsonPrimitive?.content ?: "",
                bankName = json["bankName"]?.jsonPrimitive?.content ?: "",
                type = json["type"]?.jsonPrimitive?.content ?: "EXPENSE"
            )
        } catch (e: Exception) {
            null
        }
    }
}

data class ParsedExpense(
    val date: String,
    val amount: Double,
    val merchantName: String,
    val category: String,
    val bankName: String,
    val type: String
)
```

`ParsedExpense` deliberately excludes id/timestamp/filePath so it maps to the
Room entity at the call site.

### Step 2: Rewire ReceiptScanner

In `processReceipt`, replace the inline block with:

```kotlin
val parsed = ExpenseParser.parse(jsonResponse)
if (parsed == null) {
    Log.e("ReceiptScanner", "Failed to parse receipt JSON for $path")
    markFailed(path)
    return
}
expenseDao.insertExpense(
    Expense(
        date = parsed.date,
        amount = parsed.amount,
        merchantName = parsed.merchantName,
        category = parsed.category,
        bankName = parsed.bankName,
        type = parsed.type,
        filePath = path
    )
)
```

Keep plan 002's logging/markFailed semantics exactly (field name per plans/004 if applied).

**Verify**: `./gradlew :app:compileDebugKotlin -q` → exit 0

### Step 3: Write characterization tests

Create `app/src/test/java/com/example/expense_ai/ExpenseParserTest.kt`,
JUnit 4, modeled structurally on the existing
`app/src/test/java/com/example/expense_ai/ExampleUnitTest.kt`. Cases:

1. `parse_plainJson_populatesAllFields` — full valid object; assert each field.
2. `parse_fencedJson_stripsMarkdownFence` — input wrapped as
   ```` ```json\n{...}\n``` ```` ; assert same result as case 1.
3. `parse_missingType_defaultsToExpense` — omit `type`.
4. `parse_nonNumericAmount_defaultsToZero` — `"amount": "abc"` → 0.0.
5. `parse_invalidJson_returnsNull` — `"not json"` → null.
6. `parse_emptyString_returnsNull` — `""` → null.

Note: `removeSurrounding("```json", "```")` requires the EXACT prefix
```` ```json ```` — a fence written as just ```` ``` ```` followed by the
object does NOT match the prefix pair and will fall through to
`Json.parseToJsonElement`, which tolerates leading whitespace but not a bare
fence. Add case 7 documenting current behavior:
`parse_bareFence_returnsNullOrDocumentCurrentBehavior` — run it, and encode
WHATEVER the actual result is with a comment stating it is characterization,
not desired behavior.

**Verify**: `./gradlew :app:testDebugUnitTest -q` → all pass, including 7 new tests

## Test plan

Covered in Step 3 — these ARE the tests. Verification command above.

## Done criteria

- [ ] `./gradlew :app:testDebugUnitTest -q` exits 0 with 7+ new passing tests in ExpenseParserTest
- [ ] `grep -n "removeSurrounding" app/src/main/java/com/example/expense_ai/scanner/ReceiptScanner.kt` returns no matches (logic moved to ExpenseParser)
- [ ] `./gradlew :app:compileDebugKotlin -q` exits 0
- [ ] No files outside the in-scope list modified (`git status`)
- [ ] No new dependencies added to `app/build.gradle.kts`
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back if:

- ReceiptScanner excerpts don't match live code (earlier plans changed them) — reconcile against those plans' final state or STOP.
- Any characterization test contradicts the documented expected values in ways you cannot explain from the code — report the actual behavior instead of "fixing" the parser.
- Compile fails twice after reasonable attempts.

## Maintenance notes

- plans/008 replaces this manual parsing with kotlinx.serialization +
  responseMimeType="application/json"; these tests become its safety net —
  update expectations there, do not delete them silently.
