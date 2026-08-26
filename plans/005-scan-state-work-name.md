# Plan 005: Track scanning state for both one-shot and periodic work

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md`.
>
> **Drift check (run first)**: This repo has NO commits yet. Compare live
> code against the "Current state" excerpts below; on mismatch, STOP.

## Status

- **Priority**: P3
- **Effort**: S
- **Risk**: LOW
- **Depends on**: plans/001 (edits MainActivity; land 001 first)
- **Category**: bug
- **Planned at**: no commits yet (initial staged state), 2026-08-26

## Why this matters

The UI's `isScanning` flag observes WorkManager unique work named
`"receipt_scan"` only. The periodic scan is enqueued under a DIFFERENT name,
`"periodic_receipt_scan"`, so hourly background scans run completely
invisible to the user, and worse, the manual "Scan Now" button stays enabled
while the periodic worker may already be running the same pipeline. Both
names should be observed.

## Current state

- `app/src/main/java/com/example/expense_ai/MainActivity.kt`:

```kotlin
// line 71-72
val workInfos by workManager.getWorkInfosForUniqueWorkFlow("receipt_scan")
    .collectAsState(initial = emptyList())

// line 74
val isScanning = workInfos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }

// lines 81-86 and 98-103: periodic work enqueued under "periodic_receipt_scan"
// line 113-117: one-time work under "receipt_scan"
```

Repo conventions: Compose state via `collectAsState`; WorkManager flows from
`androidx.work` 2.11.2 (`gradle/libs.versions.toml`: `work = "2.11.2"`).

## Commands you will need

| Purpose | Command                                | Expected on success |
|---------|----------------------------------------|---------------------|
| Compile | `./gradlew :app:compileDebugKotlin -q` | exit 0              |

## Scope

**In scope**:
- `app/src/main/java/com/example/expense_ai/MainActivity.kt`

**Out of scope**:
- Renaming either work name (both are referenced by WorkManager persistence;
  renaming breaks already-enqueued work).
- Any other composable logic.

## Git workflow

- Branch: `advisor/005-scan-state-work-name`
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Combine both work-name flows

Replace lines 71-74 with:

```kotlin
val scanWorkInfos by workManager.getWorkInfosForUniqueWorkFlow("receipt_scan")
    .collectAsState(initial = emptyList())
val periodicWorkInfos by workManager.getWorkInfosForUniqueWorkFlow("periodic_receipt_scan")
    .collectAsState(initial = emptyList())

val isScanning = (scanWorkInfos + periodicWorkInfos).any {
    it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
}
```

**Verify**: `./gradlew :app:compileDebugKotlin -q` → exit 0

## Test plan

No unit-testable logic (Compose + WorkManager). Manual verification: trigger a
scan, observe button disables; note that with an hourly period this is hard to
observe for periodic work — code review suffices for that half.

## Done criteria

- [ ] `./gradlew :app:compileDebugKotlin -q` exits 0
- [ ] `grep -n 'getWorkInfosForUniqueWorkFlow("receipt_scan")' app/src/main/java/com/example/expense_ai/MainActivity.kt` shows the flow assigned to a NEW variable (not directly feeding `isScanning`)
- [ ] No files outside the in-scope list are modified (`git status`)
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back if:

- Line excerpts don't match (e.g. plans/001 changed the surrounding code differently).
- `getWorkInfosForUniqueWorkFlow` does not resolve — check WorkManager version in `gradle/libs.versions.toml` and report if below 2.9.0.

## Maintenance notes

- If more work names appear later, extract a single combined StateFlow instead of stacking collectAsState calls.
