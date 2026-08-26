# Plan 003: Downsample receipt bitmaps before decoding

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
- **Depends on**: none (touches the same function as plans/002 — rebase or execute after it)
- **Category**: perf
- **Planned at**: no commits yet (initial staged state), 2026-08-26

## Why this matters

`ReceiptScanner.processReceipt` decodes full-resolution camera images with a
plain `BitmapFactory.decodeStream`. A modern phone photo is ~12-50 MP; at
ARGB_8888 that is 48-200 MB of heap per image, decoded inside a
WorkManager CoroutineWorker. On low-memory devices this crashes with OOM and,
because WorkManager restarts the worker, can crash-loop. Receipts are text
documents — they do not need more than ~1500px on the long edge for Gemini to
read them.

## Current state

- `app/src/main/java/com/example/expense_ai/scanner/ReceiptScanner.kt:57-60`:

```kotlin
private suspend fun processReceipt(uri: android.net.Uri, path: String) {
    val bitmap = context.contentResolver.openInputStream(uri)?.use { 
        BitmapFactory.decodeStream(it)
    } ?: return
```

No `BitmapFactory.Options`, no bounds check, no downsampling.

Repo conventions: plain Kotlin, no utility class exists for image work;
keep helpers private inside ReceiptScanner.

## Commands you will need

| Purpose | Command                                | Expected on success |
|---------|----------------------------------------|---------------------|
| Compile | `./gradlew :app:compileDebugKotlin -q` | exit 0              |

## Scope

**In scope**:
- `app/src/main/java/com/example/expense_ai/scanner/ReceiptScanner.kt`

**Out of scope**:
- `GeminiManager.kt` (it accepts any Bitmap unchanged).
- Any UI/preview code.

## Git workflow

- Branch: `advisor/003-bitmap-downsampling`
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Add a bounded decode helper

Add this private function to `ReceiptScanner`:

```kotlin
private fun decodeSampledBitmap(uri: android.net.Uri, maxDimension: Int = 1536): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (bounds.outWidth / (sampleSize * 2) >= maxDimension / 2 ||
        bounds.outHeight / (sampleSize * 2) >= maxDimension / 2
    ) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    }
}
```

RGB_565 halves memory again and is fine for receipts (no alpha needed).

### Step 2: Switch processReceipt to the helper

Replace lines 58-60 with:

```kotlin
val bitmap = decodeSampledBitmap(uri) ?: return
```

Keep the surrounding behavior identical (`?: return` still exits without
marking processed — that stays plan 002's concern).

### Step 3: Remove the now-unused BitmapFactory import if flagged

`BitmapFactory` is still used by the helper; only remove imports the compiler
reports as unused.

**Verify each step**: `./gradlew :app:compileDebugKotlin -q` → exit 0

## Test plan

No unit-testable pure logic introduced (decode requires Android framework).
Manual verification: install debug build, scan a folder containing a large
photo, confirm scan completes without OOM in logcat.

## Done criteria

- [ ] `./gradlew :app:compileDebugKotlin -q` exits 0
- [ ] `./gradlew :app:testDebugUnitTest -q` exits 0
- [ ] `grep -n "decodeStream(it)" app/src/main/java/com/example/expense_ai/scanner/ReceiptScanner.kt` returns no matches (the un-options call is gone)
- [ ] No files outside the in-scope list are modified (`git status`)
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back if:

- The excerpt at ReceiptScanner.kt:57-60 does not match (e.g. plan 002 or another change already altered it differently).
- Compile fails twice after reasonable attempts.
- You find a second bitmap decode site elsewhere — report instead of extending scope.

## Maintenance notes

- If a future feature shows receipt images in the UI, reuse this helper but
  reconsider RGB_565 (banding on photos shown full-screen).
- Reviewer should scrutinize: two-pass decode uses TWO separate stream opens
  (required — streams cannot reset); confirm both use `?.use`.
