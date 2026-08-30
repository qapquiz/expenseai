# Plan 015: Group the expense list by date with daily EXPENSE totals

> **Executor instructions**: Short plan — design was fully decided in a live
> grill-me session (operator picked option b, expense-only totals). Drift
> check: compare "Current state" excerpts against live files; STOP on mismatch.

## Status

- **Priority**: P2
- **Effort**: S
- **Risk**: LOW
- **Depends on**: 014 (DONE)
- **Category**: feature
- **Planned at**: 2026-08-29

## Why this matters

The list was a flat timestamp-ordered log. Expense trackers are consumed
day-by-day; operators want "how much did I spend today" at a glance.

## Decisions (agreed with operator)

1. Group by the `date` column; **fix the sort**: DAO changes from
   `ORDER BY timestamp DESC` to `ORDER BY date DESC, timestamp DESC`
   (query-text change only — no migration; ISO strings sort chronologically).
2. Header labels: `Today` / `Yesterday` / `28 Aug 2026` (split the ISO string;
   Calendar for today/yesterday — java.time needs API 26, minSdk is 24, no
   desugaring). Blank date → `Unknown date` (sorts last in DESC). Drifted/
   unparseable date strings render raw (honest-display rule from 014).
3. Header total = **EXPENSE rows only**, red, hidden when zero. TRANSFER is
   net-zero self-movement; INCOME is not spending (014 rules inherited).
4. Plain (non-sticky) headers; pure Compose; no new dependencies.

## Current state

- `ExpenseDao.kt`: `@Query("SELECT * FROM expenses ORDER BY timestamp DESC")`
- `MainActivity.kt`: `LazyColumn` renders `items(expenses) { ExpenseItem(...) }`
  — flat, no headers. (Post-014: ExpenseItem takes an onClick param.)

## Steps

1. DAO: swap the ORDER BY clause (line above).
2. MainActivity: `ExpenseListItem` sealed interface (Header/Row);
   `groupExpensesByDate()` (groupBy on date — preserves DESC encounter order);
   `headerLabel()` + `calendarYmd()` (Locale.US formatting throughout);
   `DateHeader` composable; LazyColumn renders `forEachIndexed { item }` over
   the flat list. ExpenseItem click wiring unchanged.

## Verification (done 2026-08-29, emulator-5554)

- `./gradlew :app:compileDebugKotlin -q` → exit 0; `:app:testDebugUnitTest` → exit 0
- On device: groups render `Yesterday -฿110.00` (45+65), `28 Aug 2026 -฿180.00`,
  transfer-only group shows NO total, income-only group shows NO total,
  date-DESC order, card tap still opens the category sheet with correct
  selected chip.

## Done criteria

- [x] Both gradle gates green
- [x] On-device matrix above verified
- [x] No schema/DB-version change; parser untouched
