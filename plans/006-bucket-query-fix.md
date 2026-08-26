# Plan 006: Fix the MediaStore query so it actually finds receipt images

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
- **Effort**: S
- **Risk**: LOW
- **Depends on**: plans/002 and plans/004 (same function; land those first)
- **Category**: bug
- **Planned at**: no commits yet (initial staged state), 2026-08-26

## Why this matters

`ReceiptScanner.scanAndProcess` queries only five hard-coded photo buckets:
"K PLUS", "SCB EASY", "Krungthai NEXT", "Bangkok Bank", "ttb touch". Real
bank-transaction screenshots almost always land in the generic `Screenshots`
or `Download` buckets — not per-app album folders, whose display names also
vary by device locale and app version. As written, the scan will find zero
images for most users. This plan widens the query to include the common
buckets while keeping an allow-list approach.

## Current state

- `app/src/main/java/com/example/expense_ai/scanner/ReceiptScanner.kt:20-22, 31`:

```kotlin
private val bankFolders = listOf(
    "K PLUS", "SCB EASY", "Krungthai NEXT", "Bangkok Bank", "ttb touch"
)
...
val selection = "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} IN (${bankFolders.joinToString { "'$it'" }})"
```

Repo conventions: plain Kotlin; the class takes Context via constructor.

## Commands you will need

| Purpose | Command                                | Expected on success |
|---------|----------------------------------------|---------------------|
| Compile | `./gradlew :app:compileDebugKotlin -q` | exit 0              |
| Unit tests | `./gradlew :app:testDebugUnitTest -q` | exit 0            |

## Scope

**In scope**:
- `app/src/main/java/com/example/expense_ai/scanner/ReceiptScanner.kt`

**Out of scope**:
- Removing the allow-list entirely (scanning ALL images would send every
  personal photo to Gemini — privacy regression; keep the filter).
- Any change to rate-limiting (the 4s delay).

## Git workflow

- Branch: `advisor/006-bucket-query-fix`
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Widen the bucket list

Replace the `bankFolders` initializer with:

```kotlin
private val bankFolders = listOf(
    "Screenshots", "Download", "Downloads",
    "K PLUS", "SCB EASY", "Krungthai NEXT", "Bangkok Bank", "ttb touch"
)
```

Note: bucket display names are case-sensitive in some MediaStore
implementations. Keep exact strings as listed.

**Verify**: `./gradlew :app:compileDebugKotlin -q` → exit 0

## Test plan

No unit-testable pure logic (ContentResolver query). Manual verification on a
device with at least one screenshot of a bank transfer: press "Scan Bank
Receipts Now", confirm logcat shows the scanner processing the image
(requires plans/002's logging) instead of finding zero rows.

## Done criteria

- [ ] `./gradlew :app:compileDebugKotlin -q` exits 0
- [ ] `./gradlew :app:testDebugUnitTest -q` exits 0
- [ ] The bucket list contains "Screenshots" and "Download"
- [ ] No files outside the in-scope list are modified (`git status`)
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back if:

- plans/004 renamed things in this file differently than its plan describes.
- Compile fails twice after reasonable attempts.
- You discover the selection string is built differently than quoted (e.g. parameterized already).

## Maintenance notes

- If users report missed receipts, the likely culprit remains bucket naming;
  consider logging distinct bucket names seen during scans as a follow-up diagnostic.
- Reviewer should scrutinize that the allow-list was NOT removed entirely.
