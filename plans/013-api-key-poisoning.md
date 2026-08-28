# Plan 013: Blank Gemini API key aborts the scan instead of poisoning receipts

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving on.
> Touch only files listed as in scope. If any STOP condition occurs, stop and
> report — do not improvise. Commit per the Git workflow section. One
> override: SKIP updating `plans/README.md` — your reviewer maintains it.
>
> **Drift check (run first)**:
> `git diff --stat 3a35042..HEAD -- app/src/main/java/com/example/expense_ai/scanner/ReceiptScanner.kt app/src/main/java/com/example/expense_ai/GeminiManager.kt`
> ReceiptScanner.kt is EXPECTED to have changed (uncommitted scanner work was
> committed before dispatch). Compare the "Current state" excerpts against the
> LIVE code; on any mismatch, STOP.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none hard (plans/002 is DONE)
- **Category**: bug
- **Planned at**: commit `3a35042` (against the post-commit working tree), 2026-08-26

## Why this matters

When `GEMINI_API_KEY` is blank, `GeminiManager.extractExpenseData` returns an
error MESSAGE as if it were model output (a non-null String). The parser then
fails on it, and `ReceiptScanner.processReceipt` calls `markFailed(imageId)`
for **every** scanned receipt. Failed receipts are skipped forever by
`if (imageId in failed) continue` — so a user who launches the app once before
configuring the key permanently poisons all their receipts, with no way to
retry even after fixing the key. After this plan, a blank key aborts the scan
up front: nothing is queried, nothing is marked failed, and the status line
tells the user why.

## Current state

- `app/src/main/java/com/example/expense_ai/GeminiManager.kt` — wraps the
  Gemini SDK. Excerpt (line 26–27, top of `extractExpenseData`):

```kotlin
suspend fun extractExpenseData(bitmap: Bitmap): String? {
    if (BuildConfig.GEMINI_API_KEY.isBlank()) return "Gemini API key is not configured. Add GEMINI_API_KEY=<key> to local.properties (debug builds only)."
```

- `app/src/main/java/com/example/expense_ai/scanner/ReceiptScanner.kt` —
  `class ReceiptScanner(context, geminiManager, expenseDao)`. Relevant
  excerpts from `scanAndProcess`:

```kotlin
suspend fun scanAndProcess(onProgress: (suspend (String) -> Unit)? = null) {
    onProgress?.invoke("Searching for bank receipts...")
    ...
    if (imageId in failed || isPending == 1) continue
```

  and from `processReceipt` (null extraction → permanent failure):

```kotlin
val jsonResponse = try {
    geminiManager.extractExpenseData(bitmap)
} catch (e: QuotaExceededException) {
    throw e
}
...
if (jsonResponse == null) {
    Log.e("ReceiptScanner", "Gemini returned no text for $imageId")
    markFailed(imageId)
    return
}
```

- `generateResponse` in GeminiManager has the same return-an-error-string
  pattern but ZERO callers (dead code, tracked separately) — do NOT change it.
- Known test baseline: exactly 2 failures, both in `ExpenseParserTest`
  (fence tests, tracked in `plans/011`). Unrelated to this plan.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Compile | `./gradlew :app:compileDebugKotlin -q` | exit 0 |
| Unit tests | `./gradlew :app:testDebugUnitTest` | 9 completed, 2 failed (the known ExpenseParserTest fence tests only) |

If gradle reports "SDK location not found", copy the gitignored
`local.properties` from the main checkout:
`cp /home/nullphase/AndroidStudioProjects/expenseai/local.properties ./local.properties`

## Scope

**In scope**:
- `app/src/main/java/com/example/expense_ai/GeminiManager.kt`
- `app/src/main/java/com/example/expense_ai/scanner/ReceiptScanner.kt`

**Out of scope** (do NOT touch):
- `GeminiManager.generateResponse` (dead code; cleanup handled elsewhere)
- `ExpenseParser` / parser tests (owned by plans/011)
- `ExpenseDatabase`, DAO, entities
- Any change to `markFailed` semantics for genuine extraction failures

## Git workflow

- Branch: `advisor/013-api-key-poisoning`
- Commit message: `fix(scanner): blank API key aborts scan instead of failing all receipts`
- Do NOT push or open a PR.

## Steps

### Step 1: GeminiManager — expose configuration state, return null on blank key

In `GeminiManager`, add a property and change the blank-key branch:

```kotlin
val isConfigured: Boolean
    get() = BuildConfig.GEMINI_API_KEY.isNotBlank()
```

and in `extractExpenseData`, replace

```kotlin
if (BuildConfig.GEMINI_API_KEY.isBlank()) return "Gemini API key is not configured. Add GEMINI_API_KEY=<key> to local.properties (debug builds only)."
```

with

```kotlin
if (!isConfigured) return null
```

**Verify**: `grep -n 'return "Gemini API key' app/src/main/java/com/example/expense_ai/GeminiManager.kt` → no matches
**Verify**: `grep -n 'isConfigured' app/src/main/java/com/example/expense_ai/GeminiManager.kt` → 2 matches (property + branch)

### Step 2: ReceiptScanner — abort before touching MediaStore when unconfigured

At the very top of `scanAndProcess`, before the
`onProgress?.invoke("Searching for bank receipts...")` line, add:

```kotlin
if (!geminiManager.isConfigured) {
    Log.e("ReceiptScanner", "Gemini API key is not configured — scan aborted")
    onProgress?.invoke("Gemini API key is not configured — scan aborted")
    return
}
```

Note: `scanAndProcess` already has `onProgress?.invoke(...)` wired to
WorkManager progress (`setProgress` in ScanWorker), so the message reaches the
UI status line. Nothing else in the function changes: genuine extraction
failures continue to `markFailed` as before.

**Verify**: `grep -n 'isConfigured' app/src/main/java/com/example/expense_ai/scanner/ReceiptScanner.kt` → at least 1 match

### Step 3: Compile and run the suite

**Verify**: `./gradlew :app:compileDebugKotlin -q` → exit 0
**Verify**: `./gradlew :app:testDebugUnitTest` → 9 completed, 2 failed, both
the known ExpenseParserTest fence tests. Any other failure is a STOP condition.

## Test plan

No new unit tests — `ReceiptScanner` is Android-coupled (ContentResolver,
Room) and has no test seam yet (extraction of pure decision logic is a
separate deferred plan). Manual operator verification after merge: with the
key removed from `local.properties`, press "Scan Bank Receipts Now" → status
shows the abort message; `failed_receipts` table unchanged; restore key →
normal scan proceeds.

## Done criteria

- [ ] `./gradlew :app:compileDebugKotlin -q` exits 0
- [ ] Test run: only the 2 known ExpenseParserTest failures (or 0 if plans/011 landed in this branch's base)
- [ ] `grep -n 'return "Gemini API key' app/src/main/java/com/example/expense_ai/GeminiManager.kt` → no matches
- [ ] `grep -n 'isConfigured'` → matches in both GeminiManager.kt and ReceiptScanner.kt
- [ ] `git status` shows only the two in-scope files modified (plus the commit on the advisor branch)
- [ ] `plans/README.md` NOT modified

## STOP conditions

Stop and report if:

- Live code doesn't match the "Current state" excerpts (e.g. `extractExpenseData`
  signature changed, or `scanAndProcess` has no `onProgress` parameter).
- A test other than the two known ExpenseParserTest fence tests fails.
- The fix appears to require touching an out-of-scope file.

## Maintenance notes

- Receipts already poisoned by the old behavior sit in the `failed_receipts`
  table and will still be skipped. Clearing them needs SQL
  (`DELETE FROM failed_receipts`) or the planned failed-receipt management UI
  (direction item A) — operator decision after this lands.
- `generateResponse` still returns error strings; it is dead code pending the
  cleanup plan and must not grow callers.
- Reviewer: confirm `markFailed` is still reached on genuine null extraction
  (model returned nothing) — the fix must not suppress real failure marking.
