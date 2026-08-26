# Plan 010: Replace destructive migrations with a declared migration path

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md`.
>
> **Drift check (run first)**: This repo has NO commits yet. Compare live
> code against the "Current state" excerpts below. NOTE: the DB version number
> may already be 3 or 4 if plans/002 and plans/004 landed — that is expected;
> treat any OTHER mismatch as a STOP condition.

## Status

- **Priority**: P3 (P1 the day the app ships to any real user)
- **Effort**: S
- **Risk**: LOW
- **Depends on**: execute AFTER plans/002 and plans/004 (they bump the schema; this plan finalizes migration strategy)
- **Category**: tech-debt
- **Planned at**: no commits yet (initial staged state), 2026-08-26

## Why this matters

`ExpenseDatabase` uses `fallbackToDestructiveMigration(dropAllTables = true)`:
any future schema change silently WIPES every stored expense and scan history.
That is fine while pre-release, but it is a data-loss landmine — one forgotten
version bump after shipping destroys user data with no warning. This plan
turns destruction into a loud crash during development (so bumps are noticed)
and introduces a real `Migration` as the pattern going forward.

## Current state

- `app/src/main/java/com/example/expense_ai/data/ExpenseDatabase.kt:8, 18-24`:

```kotlin
@Database(entities = [Expense::class], version = 2, exportSchema = false)
...
val instance = Room.databaseBuilder(
    context.applicationContext,
    ExpenseDatabase::class.java,
    "expense_database"
)
.fallbackToDestructiveMigration(dropAllTables = true)
.build()
```

(entities list and version may have grown via earlier plans:
plans/002 adds `FailedReceipt`, plans/004 renames `filePath`→`imageId`.)

Repo conventions: Room via KSP (`ksp(libs.androidx.room.compiler)`), version 2.8.4.

## Commands you will need

| Purpose    | Command                                  | Expected on success |
|------------|------------------------------------------|---------------------|
| Compile    | `./gradlew :app:compileDebugKotlin -q`   | exit 0              |
| Unit tests | `./gradlew :app:testDebugUnitTest -q`    | exit 0              |

## Scope

**In scope**:
- `app/src/main/java/com/example/expense_ai/data/ExpenseDatabase.kt`

**Out of scope**:
- Entity definitions themselves (already settled by earlier plans).
- Adding `room.schemaLocation` KSP arg / exportSchema=true (worthwhile but a
  separate tooling decision — noted in Maintenance).

## Git workflow

- Branch: `advisor/010-migration-strategy`
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Read the current version and entity list

Open ExpenseDatabase.kt. Record the current `version = N` and entities list —
you need N for Step 2. If `fallbackToDestructiveMigration(dropAllTables = true)`
is not present exactly as quoted above, STOP.

### Step 2: Swap destructive fallback for a dev-only loud failure plus one real migration

Replace the builder chain with:

```kotlin
val instance = Room.databaseBuilder(
    context.applicationContext,
    ExpenseDatabase::class.java,
    "expense_database"
)
.addMigrations(*ALL_MIGRATIONS)
.build()
```

and define at the bottom of the companion object, covering every historical
transition up to current version N. Because all prior transitions were
destructive-by-design in an unreleased app, a single catch-up migration from
the last released shape is sufficient; for THIS repo nothing has shipped, so
define the object empty and rely on the loud-failure behavior:

```kotlin
private val ALL_MIGRATIONS = emptyArray<androidx.room.migration.Migration>()
```

Effect: any un-migrated version change now throws
`IllegalStateException` at open time instead of deleting data — correct for
development, and forces writing real migrations once users exist.

**Verify**: `./gradlew :app:compileDebugKotlin -q` → exit 0

### Step 3: Confirm clean-install still works

Uninstall/reinstall cannot be automated here; verify by compiling and running
unit tests only. Manual operator check: fresh install of debug build opens
the app without crash (Room creates the DB at the current version directly —
no migration involved).

**Verify**: `./gradlew :app:testDebugUnitTest -q` → exit 0

## Test plan

No new unit tests (migration testing requires instrumentation with a schema
export, out of scope). Manual: fresh install works; upgrading an installed
dev build across a version bump now crashes loudly instead of wiping — that
crash IS the intended signal to write a migration.

## Done criteria

- [ ] `./gradlew :app:compileDebugKotlin -q` exits 0
- [ ] `./gradlew :app:testDebugUnitTest -q` exits 0
- [ ] `grep -n "fallbackToDestructiveMigration" app/src/main/java/com/example/expense_ai/data/ExpenseDatabase.kt` returns no matches
- [ ] No files outside the in-scope list modified (`git status`)
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back if:

- The builder excerpt differs beyond the entities/version drift described above.
- Compile reveals `addMigrations` signature mismatch with Room 2.8.4 — report actual signature instead of guessing.
- Any plan other than 002/004 has added its own migration code here.

## Maintenance notes

- Follow-up worth doing separately: enable schema export via KSP arg
  `room.schemaLocation` and set `exportSchema = true`; required for
  `MigrationTestHelper` once real migrations exist.
- Reviewer should scrutinize that ALL_MIGRATIONS being empty is understood:
  it means "loud failure on any gap", not "migrations handled".
