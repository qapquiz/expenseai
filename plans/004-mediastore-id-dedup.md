# Plan 004: Use MediaStore _ID as the dedup key instead of the deprecated DATA path

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
- **Risk**: MED
- **Depends on**: plans/002 (both edit `scanAndProcess`/`processReceipt`; land 002 first)
- **Category**: bug
- **Planned at**: no commits yet (initial staged state), 2026-08-26

## Why this matters

The scanner deduplicates processed receipts by `MediaStore.Images.Media.DATA`
— the raw filesystem path. That column is deprecated, is not guaranteed to be
populated on Android 10+ (scoped storage), and can return NULL. The null flows
into a Kotlin non-null parameter (`cursor.getString` returns a platform type),
producing a latent NPE/`TypeCastException`, and even when populated the path is
not a stable identity (files move between buckets). The stable, documented
identity for a MediaStore row is its `_ID`. This plan switches dedup and the
stored key to `_ID`.

## Current state

- `app/src/main/java/com/example/expense_ai/scanner/ReceiptScanner.kt`:

```kotlin
// lines 25-29
val projection = arrayOf(
    MediaStore.Images.Media._ID,
    MediaStore.Images.Media.DATA,
    MediaStore.Images.Media.BUCKET_DISPLAY_NAME
)
// line 41, 44-49
val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
...
while (cursor.moveToNext()) {
    val id = cursor.getLong(idColumn)
    val path = cursor.getString(dataColumn)
    
    if (!expenseDao.isFileProcessed(path)) {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        processReceipt(uri, path)
```

and `processReceipt(uri, path)` stores `filePath = path` in the Expense
(`ReceiptScanner.kt:74`).

- `app/src/main/java/com/example/expense_ai/data/ExpenseDao.kt:17-18`:

```kotlin
@Query("SELECT EXISTS(SELECT 1 FROM expenses WHERE filePath = :filePath)")
suspend fun isFileProcessed(filePath: String): Boolean
```

- `app/src/main/java/com/example/expense_ai/data/Expense.kt:15`:
  `val filePath: String` — part of the Room entity (`expenses`, currently
  version 3 after plans/002).

Repo conventions: suspend DAO methods; entity + DAO + database live in `data/`.

## Commands you will need

| Purpose   | Command                                | Expected on success |
|-----------|----------------------------------------|---------------------|
| Compile   | `./gradlew :app:compileDebugKotlin -q` | exit 0              |
| Unit tests| `./gradlew :app:testDebugUnitTest -q`  | exit 0              |

## Scope

**In scope**:
- `app/src/main/java/com/example/expense_ai/scanner/ReceiptScanner.kt`
- `app/src/main/java/com/example/expense_ai/data/Expense.kt`
- `app/src/main/java/com/example/expense_ai/data/ExpenseDao.kt`
- `app/src/main/java/com/example/expense_ai/data/ExpenseDatabase.kt`

**Out of scope**:
- `MainActivity.kt` — it renders `expense.filePath` nowhere; verify with grep but change nothing.
- Bucket-name selection (that is plans/006).
- Any migration-writing strategy beyond bumping the version (destructive
  migration is the existing decision — see plans/010).

## Git workflow

- Branch: `advisor/004-mediastore-id-dedup`
- Commit per step.
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Bump the database version

Because the stored value of `filePath` changes semantics, force a rebuild of
existing dev data: in `ExpenseDatabase.kt`, increment the version by 1
(version 4 if plans/002 landed; otherwise 3). Keep
`fallbackToDestructiveMigration(dropAllTables = true)`.

**Verify**: `./gradlew :app:compileDebugKotlin -q` → exit 0

### Step 2: Rename the field

In `Expense.kt`, rename `val filePath: String` to `val imageId: String`
(update the constructor call site comment if any). In `ExpenseDao.kt`,
rename accordingly:

```kotlin
@Query("SELECT EXISTS(SELECT 1 FROM expenses WHERE imageId = :imageId)")
suspend fun isFileProcessed(imageId: String): Boolean
```

Apply the same rename to `FailedReceipt` (added by plans/002) — rename its
`filePath` to `imageId` too, including `getFailedFilePaths()` →
`getFailedImageIds()`. Grep for every reference:

**Verify**: `grep -rn "filePath" app/src/main/java/` → returns no matches after this plan completes

### Step 3: Feed _ID through the scanner

In `ReceiptScanner.scanAndProcess`:
- Remove `MediaStore.Images.Media.DATA` from the projection and the
  `dataColumn` lookup.
- Replace `val path = cursor.getString(dataColumn)` usage with
  `val imageId = id.toString()`.
- Pass `imageId` where `path` was passed (`isFileProcessed`, failed-set check,
  `processReceipt(uri, imageId)`, logging messages).

**Verify**: `./gradlew :app:testDebugUnitTest -q` → exit 0

## Test plan

No new unit tests (MediaStore requires instrumentation). Manual verification:
on a device, scan once, note inserted rows via the app UI count; scan again —
no duplicates appear (proves `_ID`-based dedup works).

## Done criteria

- [ ] `./gradlew :app:compileDebugKotlin -q` exits 0
- [ ] `./gradlew :app:testDebugUnitTest -q` exits 0
- [ ] `grep -rn "Media.DATA\|filePath" app/src/main/java/` returns no matches
- [ ] Database version incremented exactly once from its pre-plan value
- [ ] No files outside the in-scope list are modified (`git status`)
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back if:

- Excerpts above don't match live code (especially if plans/002 hasn't landed — then execute 002 first or STOP).
- `grep -rn "filePath" app/src/main/java/` reveals references in files outside your scope (e.g. UI code reading `expense.filePath`) — report the locations instead of editing out-of-scope files.
- Compile fails twice after reasonable attempts.

## Maintenance notes

- Existing installs lose their "processed" history on upgrade (destructive
  migration re-scans everything once). Acceptable pre-release; revisit with plans/010.
- Reviewer should scrutinize that no log message still says "path" while holding an ID.
