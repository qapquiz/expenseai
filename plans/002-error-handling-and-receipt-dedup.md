# Plan 002: Surface scanner errors and stop re-scanning failed receipts forever

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md`.
>
> **Drift check (run first)**: This repo has NO commits yet. Open each
> in-scope file and compare it against the "Current state" excerpts below.
> On any mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: LOW
- **Depends on**: none (execute after plans/001 for convenience; no code dependency)
- **Category**: bug
- **Planned at**: no commits yet (initial staged state), 2026-08-26

## Why this matters

Two compounding bugs make the scanner silently burn API quota:

1. If Gemini extraction returns null or JSON parsing fails,
   `processReceipt` returns WITHOUT recording anything, so
   `expenseDao.isFileProcessed(path)` stays false forever. Every hourly scan
   retries every failed image with a 4-second delay each
   (`ReceiptScanner.kt:47-52`), paying for the same failures indefinitely.
2. Every error path is swallowed: `GeminiManager` catches all exceptions and
   returns `null`, the JSON parse catch block is an empty comment, and
   `ScanWorker` converts any exception to a blind `Result.retry()`. Nothing
   is logged, so neither bug is observable or debuggable.

This plan adds logging to every failure path and records failed files so they
are attempted at most once per retry policy instead of on every cycle.

## Current state

- `app/src/main/java/com/example/expense_ai/scanner/ReceiptScanner.kt` —
  scans MediaStore and inserts expenses:

```kotlin
// ReceiptScanner.kt:47-52
if (!expenseDao.isFileProcessed(path)) {
    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
    processReceipt(uri, path)
    // Delay to stay within free tier 15 RPM limit (approx 4 seconds per request)
    kotlinx.coroutines.delay(4000)
}
```

```kotlin
// ReceiptScanner.kt:62-63, 77-79
val jsonResponse = geminiManager.extractExpenseData(bitmap) ?: return
val cleanJson = jsonResponse.trim().removeSurrounding("```json", "```").trim()
...
} catch (e: Exception) {
    // Handle parsing error
}
```

- `app/src/main/java/com/example/expense_ai/GeminiManager.kt:35-37`:

```kotlin
} catch (e: Exception) {
    null
}
```

- `app/src/main/java/com/example/expense_ai/scanner/ScanWorker.kt:20-25`:

```kotlin
return try {
    scanner.scanAndProcess()
    Result.success()
} catch (e: Exception) {
    Result.retry()
}
```

- `app/src/main/java/com/example/expense_ai/data/ExpenseDao.kt:14-18`:
  `insertExpense` uses `OnConflictStrategy.REPLACE`; dedup query is
  `SELECT EXISTS(SELECT 1 FROM expenses WHERE filePath = :filePath)`.

- `app/src/main/java/com/example/expense_ai/data/Expense.kt` — Room entity
  `expenses`, version 2 declared in `ExpenseDatabase.kt:8`.

Repo conventions: Kotlin official style; suspend functions for DB work;
Room DAO methods are suspend or Flow-returning.

## Commands you will need

| Purpose   | Command                                | Expected on success |
|-----------|----------------------------------------|---------------------|
| Compile   | `./gradlew :app:compileDebugKotlin -q` | exit 0              |
| Unit tests| `./gradlew :app:testDebugUnitTest -q`  | exit 0              |

## Scope

**In scope** (the only files you should modify):
- `app/src/main/java/com/example/expense_ai/scanner/ReceiptScanner.kt`
- `app/src/main/java/com/example/expense_ai/GeminiManager.kt`
- `app/src/main/java/com/example/expense_ai/scanner/ScanWorker.kt`
- `app/src/main/java/com/example/expense_ai/data/ExpenseDao.kt`
- `app/src/main/java/com/example/expense_ai/data/ExpenseDatabase.kt`

**Out of scope** (do NOT touch):
- `Expense.kt` entity fields beyond what Step 3 requires (see STOP conditions if schema change is needed).
- `MainActivity.kt` — UI is plan 001's territory.
- The 4000ms rate-limit delay value.

## Git workflow

- Branch: `advisor/002-error-handling-dedup`
- Commit per step.
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Add logging to GeminiManager failure paths

In `GeminiManager.kt`, replace both silent catches with logged ones using
`android.util.Log` with tag `"GeminiManager"`:

- In `extractExpenseData` (line 35-37): log
  `Log.e("GeminiManager", "extractExpenseData failed", e)` before returning null.
- In `generateResponse`: keep the existing user-facing message mapping, add
  `Log.e("GeminiManager", "generateResponse failed", e)` inside the catch.

**Verify**: `./gradlew :app:compileDebugKotlin -q` → exit 0

### Step 2: Log parse failures in ReceiptScanner

In `processReceipt`, replace the empty catch body (lines 77-79) with
`Log.e("ReceiptScanner", "Failed to parse receipt JSON for $path", e)`.
Also log when extraction returns null: change line 62 to

```kotlin
val jsonResponse = geminiManager.extractExpenseData(bitmap)
if (jsonResponse == null) {
    Log.e("ReceiptScanner", "Gemini returned no text for $path")
    markFailed(path)
    return
}
```

(`markFailed` is added in Step 3.)

**Verify**: `./gradlew :app:compileDebugKotlin -q` → exit 0 (expected to fail
on unresolved `markFailed` until Step 3 lands; do steps 2+3 before verifying)

### Step 3: Record failures so they are not retried every scan cycle

Add a lightweight failure record without changing the Expense schema:

1. Create a new Room entity in `data/Expense.kt` (same file, below Expense):

```kotlin
@Entity(tableName = "failed_receipts", primaryKeys = ["filePath"])
data class FailedReceipt(
    val filePath: String,
    val reason: String,
    val failedAt: Long = System.currentTimeMillis()
)
```

2. In `ExpenseDao` add:

```kotlin
@Insert(onConflict = OnConflictStrategy.REPLACE)
suspend fun insertFailedReceipt(receipt: FailedReceipt)

@Query("DELETE FROM failed_receipts WHERE filePath = :filePath")
suspend fun clearFailedReceipt(filePath: String)

@Query("SELECT filePath FROM failed_receipts")
suspend fun getFailedFilePaths(): List<String>
```

3. Register the entity: change `ExpenseDatabase.kt:8` to
   `@Database(entities = [Expense::class, FailedReceipt::class], version = 3, exportSchema = false)`.
   Keep `fallbackToDestructiveMigration(dropAllTables = true)` as-is (see
   plans/010 for that decision).

4. In `ReceiptScanner`, inject nothing new — it already holds `expenseDao`.
   Add a private helper:

```kotlin
private suspend fun markFailed(path: String) {
    expenseDao.insertFailedReceipt(FailedReceipt(filePath = path, reason = "extraction_or_parse_failed"))
}
```

5. Call `markFailed(path)` on ALL failure exits of `processReceipt`:
   extraction-null (Step 2), bitmap-open-null (`?: return` at line 60), and
   the parse catch block. On successful insert (line 76), call
   `expenseDao.clearFailedReceipt(path)` first so a previously-failed file
   can be cleaned up.

6. In `scanAndProcess`, skip files present in the failed set. Before the
   while loop: `val failed = expenseDao.getFailedFilePaths().toSet()`, then
   guard the body with `if (path in failed) continue`.

7. Replace the empty catch comment with logging + `markFailed(path)`.

**Verify**: `./gradlew :app:compileDebugKotlin -q` → exit 0

### Step 4: Make ScanWorker's catch observable and bounded

In `ScanWorker.kt`, replace the blind `Result.retry()` with:

```kotlin
} catch (e: Exception) {
    Log.e("ScanWorker", "Scan failed on attempt $runAttemptCount", e)
    if (runAttemptCount < 3) Result.retry() else Result.failure()
}
```

**Verify**: `./gradlew :app:testDebugUnitTest -q` → exit 0

## Test plan

Instrumented/worker behavior needs a device, which is out of scope here;
verify by compilation plus the existing unit test suite. Manual verification
by operator after install: trigger a scan with an invalid API key, confirm
logcat shows `GeminiManager`/`ReceiptScanner` errors exactly once per file,
and a second scan run skips those files (no repeat errors).

## Done criteria

- [ ] `./gradlew :app:compileDebugKotlin -q` exits 0
- [ ] `./gradlew :app:testDebugUnitTest -q` exits 0
- [ ] `grep -rn "catch (e: Exception)" app/src/main/java/com/example/expense_ai/scanner app/src/main/java/com/example/expense_ai/GeminiManager.kt` — every match is followed by a Log statement within 2 lines
- [ ] `grep -n "Handle parsing error" app/src/main/java/com/example/expense_ai/scanner/ReceiptScanner.kt` returns no matches
- [ ] No files outside the in-scope list are modified (`git status`)
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back if:

- Any excerpt above does not match the live code.
- Room fails to compile the new entity/DAO methods after two attempts.
- You find `version = 2` has already changed (someone else migrated the DB) — do not bump versions blindly.
- The fix appears to require modifying `MainActivity.kt` or `Expense.kt`'s existing fields.

## Maintenance notes

- Future work (plans/008) replaces manual JSON parsing with
  kotlinx.serialization; keep `markFailed` call sites intact through that refactor.
- Reviewer should scrutinize: failed set is read ONCE per scan (not per
  cursor row), and `clearFailedReceipt` runs only after a confirmed insert.
