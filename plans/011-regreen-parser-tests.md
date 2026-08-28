# Plan 011: Re-green the parser test suite (remove markdown-fence tolerance)

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving on.
> Touch only files listed as in scope. If any STOP condition occurs, stop and
> report — do not improvise. Commit per the Git workflow section. One
> override: SKIP updating `plans/README.md` — your reviewer maintains it.
>
> **Drift check (run first)**:
> `git diff --stat 3a35042..HEAD -- app/src/main/java/com/example/expense_ai/scanner/ExpenseParser.kt app/src/test/java/com/example/expense_ai/scanner/ExpenseParserTest.kt`
> Empty output expected. Compare "Current state" excerpts against live code;
> on mismatch, STOP.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: tests
- **Planned at**: commit `3a35042`, 2026-08-26

## Why this matters

`./gradlew :app:testDebugUnitTest` currently fails 2 of 9 tests. Commit
`bb2ed02` re-added markdown-fence stripping to `ExpenseParser.parse`, but the
characterization tests from plan 007 (`parse_fencedJson_returnsNull`,
`parse_bareFence_returnsNull`) assert that fenced input is treated as a parse
failure. The red suite disables the verification gate every other plan relies
on. Gemini JSON mode is enforced (`responseMimeType = "application/json"` in
`GeminiManager.kt:21`), so a fenced response is a genuine anomaly and should
fail loudly, not be silently salvaged. The tests are the spec; the parser
conforms to them.

## Current state

- `app/src/main/java/com/example/expense_ai/scanner/ExpenseParser.kt` — pure
  JSON parser, `object ExpenseParser`. Excerpt (lines 17–31):

```kotlin
fun parse(responseText: String): ParsedExpense? {
    return try {
        var cleaned = responseText.trim()

        // Handle markdown code blocks if the model insists on using them
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.substringAfter("\n").substringBeforeLast("```").trim()
            // Some models put "json" after the first triple-backtick
            if (cleaned.startsWith("json", ignoreCase = true)) {
                cleaned = cleaned.removePrefix("json").trim()
            }
        }

        val draft = Json { ignoreUnknownKeys = true }
            .decodeFromString(ExpenseDraft.serializer(), cleaned)
```

- `app/src/test/java/com/example/expense_ai/scanner/ExpenseParserTest.kt` —
  8 pure-JVM JUnit4 tests; the two failing ones
  (`parse_fencedJson_returnsNull` at lines 35–46,
  `parse_bareFence_returnsNull` at lines 96–103) assert
  `ExpenseParser.parse(fenced input) == null`.
- Known baseline at HEAD: exactly these 2 tests fail; the other 7 pass.
- Conventions: plain Kotlin, JUnit4, no Robolectric. Match surrounding style.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Compile | `./gradlew :app:compileDebugKotlin -q` | exit 0 |
| Unit tests | `./gradlew :app:testDebugUnitTest` | BUILD SUCCESSFUL, 9 tests, 0 failures |

If gradle reports "SDK location not found", copy the gitignored
`local.properties` from the main checkout:
`cp /home/nullphase/AndroidStudioProjects/expenseai/local.properties ./local.properties`

## Scope

**In scope**:
- `app/src/main/java/com/example/expense_ai/scanner/ExpenseParser.kt`

**Out of scope** (do NOT touch):
- `ExpenseParserTest.kt` — the tests are the spec; weakening them is review failure
- `GeminiManager.kt` — its brace-extraction fallback for non-JSON-prefixed
  responses is a separate mechanism, unrelated to fences

## Git workflow

- Branch: `advisor/011-regreen-parser-tests`
- Commit message: `fix(parser): treat fenced JSON as parse failure`
- Do NOT push or open a PR.

## Steps

### Step 1: Delete the fence-stripping block

In `ExpenseParser.parse`, remove the `var cleaned` declaration, the entire
`if (cleaned.startsWith("```")) { ... }` block (including comments), and
decode from the trimmed input directly. Target shape:

```kotlin
fun parse(responseText: String): ParsedExpense? {
    return try {
        val draft = Json { ignoreUnknownKeys = true }
            .decodeFromString(ExpenseDraft.serializer(), responseText.trim())
        // ... rest of the function unchanged (ParsedExpense construction
        // and the catch block stay exactly as they are)
```

**Verify**: `grep -n 'startsWith' app/src/main/java/com/example/expense_ai/scanner/ExpenseParser.kt` → no matches

### Step 2: Run the suite

**Verify**: `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL; the test XML
in `app/build/test-results/testDebugUnitTest/` shows 9 completed, 0 failures.

## Test plan

No new tests. The existing 8 `ExpenseParserTest` tests are the specification
this plan conforms the code to. Previously-failing
`parse_fencedJson_returnsNull` and `parse_bareFence_returnsNull` must now pass.

## Done criteria

- [ ] `./gradlew :app:compileDebugKotlin -q` exits 0
- [ ] `./gradlew :app:testDebugUnitTest` exits 0; 9 completed, 0 failed
- [ ] `grep -n 'startsWith' app/src/main/java/com/example/expense_ai/scanner/ExpenseParser.kt` → no matches
- [ ] `git status` shows only `ExpenseParser.kt` modified (plus the commit on the advisor branch)
- [ ] `plans/README.md` NOT modified

## STOP conditions

Stop and report if:

- The two characterization tests are absent or their assertions differ from
  "Current state" (the spec has changed — escalate, don't adapt).
- Any verification fails twice after a reasonable attempt.
- The fix appears to require touching `GeminiManager.kt` or the test file.

## Maintenance notes

- If real Gemini responses ever arrive markdown-fenced despite JSON mode, the
  scanner will mark those receipts failed. The correct escalation is to
  revisit this decision with evidence (a captured raw response), not to
  silently re-add stripping.
- Reviewer: confirm the test file was NOT modified and that the diff deletes
  exactly the stripping block.
