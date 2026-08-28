# Plan 012: Remove the destructive migration fallback (data-loss guard)

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving on.
> Touch only files listed as in scope. If any STOP condition occurs, stop and
> report — do not improvise. Commit per the Git workflow section. One
> override: SKIP updating `plans/README.md` — your reviewer maintains it.
>
> **Drift check (run first)**:
> `git diff --stat 3a35042..HEAD -- app/src/main/java/com/example/expense_ai/data/ExpenseDatabase.kt`
> Empty output expected. Compare "Current state" excerpts against live code;
> on mismatch, STOP.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: bug (data loss)
- **Planned at**: commit `3a35042`, 2026-08-26

## Why this matters

`ExpenseDatabase`'s builder ends with
`.fallbackToDestructiveMigration(dropAllTables = true)`: any future schema
version bump without a matching `Migration` silently **wipes every stored
expense**. Plan 010 was closed DONE, but `git log -S` shows this line was never
removed in any commit — the done criterion (`grep` → no matches) was never
satisfied. Deleting the line turns an un-migrated bump into a loud crash at DB
open, which is the intended dev-time signal to write a migration. Three real
migrations (1→2, 2→3, 3→4) already exist and remain untouched.

## Current state

- `app/src/main/java/com/example/expense_ai/data/ExpenseDatabase.kt` —
  Room database, `version = 4`, `exportSchema = false`. Excerpt (lines 44–53):

```kotlin
val instance = Room.databaseBuilder(
    context.applicationContext,
    ExpenseDatabase::class.java,
    "expense_database"
)
.addMigrations(*ALL_MIGRATIONS)
.fallbackToDestructiveMigration(dropAllTables = true)
.build()
```

- `ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)`
  is defined above (lines 18–36) and stays exactly as is.
- Known test baseline at HEAD: exactly 2 failures, both in
  `ExpenseParserTest` (`parse_fencedJson_returnsNull`,
  `parse_bareFence_returnsNull`), tracked in `plans/011`. They are unrelated
  to this plan.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Compile | `./gradlew :app:compileDebugKotlin -q` | exit 0 |
| Unit tests | `./gradlew :app:testDebugUnitTest` | 9 completed, 2 failed — BOTH in ExpenseParserTest, no others |

If gradle reports "SDK location not found", copy the gitignored
`local.properties` from the main checkout:
`cp /home/nullphase/AndroidStudioProjects/expenseai/local.properties ./local.properties`

## Scope

**In scope**:
- `app/src/main/java/com/example/expense_ai/data/ExpenseDatabase.kt`

**Out of scope** (do NOT touch):
- Entity classes (`Expense.kt`, `FailedReceipt.kt`), DAO, DB version number
- The `MIGRATION_*` objects or `ALL_MIGRATIONS`
- Enabling `exportSchema` / `room.schemaLocation` (separate future decision)

## Git workflow

- Branch: `advisor/012-remove-destructive-fallback`
- Commit message: `fix(db): remove destructive migration fallback`
- Do NOT push or open a PR.

## Steps

### Step 1: Delete the fallback line

In `ExpenseDatabase.kt`, remove the single line
`.fallbackToDestructiveMigration(dropAllTables = true)` from the builder
chain. `.addMigrations(*ALL_MIGRATIONS)` and `.build()` remain.

**Verify**: `grep -n fallbackToDestructiveMigration app/src/main/java/com/example/expense_ai/data/ExpenseDatabase.kt` → no matches

### Step 2: Compile and run the suite

**Verify**: `./gradlew :app:compileDebugKotlin -q` → exit 0
**Verify**: `./gradlew :app:testDebugUnitTest` → 9 completed, 2 failed, and
both failures are the known ExpenseParserTest fence tests (check
`app/build/test-results/testDebugUnitTest/TEST-*.xml`). Any OTHER failing
test is a STOP condition.

## Test plan

No new tests — migration testing requires schema export + instrumentation,
explicitly out of scope. The suite run is a regression check only.

## Done criteria

- [ ] `grep -n fallbackToDestructiveMigration app/src/main/java/com/example/expense_ai/data/ExpenseDatabase.kt` → no matches
- [ ] `./gradlew :app:compileDebugKotlin -q` exits 0
- [ ] Test run: exactly the 2 known unrelated ExpenseParserTest failures, no others
- [ ] `git status` shows only `ExpenseDatabase.kt` modified (plus the commit on the advisor branch)
- [ ] `plans/README.md` NOT modified

## STOP conditions

Stop and report if:

- `version =` is not `4`, or the `ALL_MIGRATIONS` array differs from the
  excerpt (schema has moved since planning).
- A test other than the two known ExpenseParserTest fence tests fails.
- Any verification fails twice after a reasonable attempt.

## Maintenance notes

- Before the NEXT schema change: add `MIGRATION_4_5`, bump `version` to 5.
  With the fallback gone, forgetting this now crashes loudly instead of
  wiping data — that crash is the intended signal.
- Fresh installs are unaffected either way (Room creates the DB directly at
  the current version — no migration runs).
- Reviewer: confirm the diff is exactly one deleted line.
