# Plan 014: Manual category picker, EXPENSE↔TRANSFER correction, Gemini taxonomy alignment

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving on.
> Touch only files listed as in scope. If any STOP condition occurs, stop and
> report — do not improvise. Commit per the Git workflow section.
>
> **Drift check (run first)**: compare the "Current state" excerpts against
> the LIVE files. On any mismatch, STOP. (Plans use live-file excerpt
> comparison; repo head at planning time is `2cb308a`.)

## Status

- **Priority**: P2
- **Effort**: M
- **Risk**: LOW-MED (UI + prompt changes; no schema change)
- **Depends on**: none (plans 001–013 all DONE)
- **Category**: feature
- **Planned at**: commit `2cb308a`, 2026-08-28, via grill-me design session

## Why this matters

Categories today come straight from the model with a soft hint
(`Category (e.g., Food, Transport, ...)`) and no constraint, so the column
accumulates arbitrary strings that can never be summarized reliably. There is
also no way to correct a misread: whatever the model said is permanent, and
self-transfers (bank A → own account at bank B) can be misfiled as EXPENSE,
silently inflating spending.

This plan adds: (1) a tap-to-categorize bottom sheet over a fixed 9-item
taxonomy, (2) a two-way EXPENSE↔TRANSFER correction affordance for rows the
model got wrong, and (3) a rewritten prompt that hard-lists the taxonomy,
defines TRANSFER/INCOME crisply, handles Thai↔romanized name matching, and
injects an operator-configurable owner-name hint from `local.properties`.

## Design decisions (agreed with operator — do not relitigate)

1. **Fixed taxonomy, 9 English labels, stored as display strings** (no ids,
   single locale): Food & Drink, Groceries, Transport, Bills & Utilities,
   Shopping, Health, Entertainment, Travel, Other. Single source of truth:
   `Categories.kt`; the prompt line is generated from it.
2. **Interaction**: whole card is the tap target; ModalBottomSheet with
   FilterChips; **instant commit on chip tap, sheet auto-dismisses** (no Save
   button). Enabled iff `type == "EXPENSE" || type == "TRANSFER"`; INCOME
   rows are static.
3. **EXPENSE sheet**: 9 chips + a divider + "This was a transfer between my
   own accounts" action → sets `type="TRANSFER"`, clears category, dismisses.
4. **TRANSFER sheet**: NO chips, only "This is actually an expense" → sets
   `type="EXPENSE"` with empty category, dismisses (row becomes categorizable
   on next tap). Symmetric, no dead ends.
5. **Off-list category strings**: card shows the raw string (honest), picker
   highlights no chip. Future summaries roll anything off-list into "Other"
   at query time. Empty category → subtitle simply omits the segment.
6. **Prompt**: category = `choose EXACTLY ONE of <the 9>`. Type rules:
   TRANSFER = movement between the user's OWN accounts; EXPENSE = money to
   anyone else (incl. third-party โอนเงิน/เติมเงิน/ชำระเงิน); INCOME only for
   explicit "รับเงิน" from someone else. Plus: sender/receiver may appear in
   different scripts (Thai vs romanized English) — treat plausible
   transliterations as the same person. Plus optional owner hint sentence
   interpolated from `BuildConfig.OWNER_NAME_HINT` when non-blank.
   The `"Ariya"` line is DELETED (it conflated type and category).
7. **No schema change**: `category` column already exists. DB stays version 4,
   zero migrations.
8. **No ViewModel**: matches existing style — composable state +
   `rememberCoroutineScope` + direct DAO calls. Room's Flow auto-refreshes
   the list after each UPDATE.

## Current state

- `app/build.gradle.kts` — debug build type already loads `local.properties`
  and exposes one field (this is the pattern to clone):

```kotlin
buildConfigField("String", "GEMINI_API_KEY", "\"${properties.getProperty("GEMINI_API_KEY") ?: ""}\"")
```

  and release hardcodes empty:

```kotlin
buildConfigField("String", "GEMINI_API_KEY", "\"\"")
```

- `GeminiManager.kt` — the prompt inside `extractExpenseData` contains the
  lines being replaced (exact current text):

```kotlin
- Category (e.g., Food, Transport, Shopping, Bills, Travel)
- Bank Name (e.g., K-Plus, SCB, PromptPay)
- Type: Identify if this is "EXPENSE" or "INCOME" or "TRANSFER".
    * CRITICAL: Most Thai bank slips are EXPENSES.
    * If the action is "โอนเงินสำเร็จ" (Transfer), "เติมเงิน" (Top-up), or "ชำระเงิน" (Payment), it is an EXPENSE.
    * If the recipient name (receiver) is "อริยะ" or "Ariya", it is an "EXPENSE" (categorize as "TRANSFER").
    * Only use "INCOME" if the slip explicitly says "รับเงิน" (Received money).
```

- `data/ExpenseDao.kt` — has NO update methods today; insert path only.
- `MainActivity.kt` — `ExpenseItem(expense: Expense)` is a read-only Card
  (no click handling); list wired via `items(expenses) { expense -> ExpenseItem(expense) }`.
  Subtitle line:

```kotlin
Text(text = "${expense.bankName} • ${expense.category} • ${expense.type}", ...)
```

- `scanner/ExpenseParser.kt` — untouched; `type` stays a free string
  (default `"EXPENSE"`), tolerant of TRANSFER/INCOME.
- **Test baseline (verified 2026-08-28)**: `./gradlew :app:testDebugUnitTest`
  exits 0. The suite must still exit 0 after this plan.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Compile | `./gradlew :app:compileDebugKotlin -q` | exit 0 |
| Unit tests | `./gradlew :app:testDebugUnitTest -q` | exit 0 |

If gradle reports "SDK location not found", copy the gitignored
`local.properties` from the main checkout.

## Scope

**In scope**:
- `app/src/main/java/dev/nullphase/expense_ai/Categories.kt` (NEW)
- `app/src/main/java/dev/nullphase/expense_ai/data/ExpenseDao.kt`
- `app/src/main/java/dev/nullphase/expense_ai/GeminiManager.kt`
- `app/src/main/java/dev/nullphase/expense_ai/MainActivity.kt`
- `app/build.gradle.kts`

**Out of scope** (do NOT touch):
- `Expense.kt` / `ExpenseDatabase.kt` (no schema change — version stays 4)
- `ExpenseParser.kt` + `ExpenseParserTest.kt` (guarded by plans 007/011)
- `ReceiptScanner.kt` / `ScanWorker.kt`
- Any response-schema/enum enforcement (opportunistic follow-up A2, separate)
- Editable INCOME rows (known limitation, see Maintenance notes)
- `local.properties` (operator adds `OWNER_NAME_HINT` manually; never commit)

## Git workflow

- Branch: `feature/014-category-picker` (fast-forward merge to `master` when green)
- Commit message: `feat(ui): manual category picker with EXPENSE/TRANSFER correction`
- Do NOT push or open a PR.

## Steps

### Step 1: Categories.kt (new file, single source of truth)

```kotlin
package dev.nullphase.expense_ai

object Categories {
    val ALL = listOf(
        "Food & Drink",
        "Groceries",
        "Transport",
        "Bills & Utilities",
        "Shopping",
        "Health",
        "Entertainment",
        "Travel",
        "Other"
    )
}
```

**Verify**: file exists; `grep -c '"' Categories.kt` → 18 (9 quoted labels).

### Step 2: build.gradle.kts — OWNER_NAME_HINT BuildConfig field

In the `debug` build type, directly under the existing GEMINI_API_KEY line:

```kotlin
buildConfigField("String", "OWNER_NAME_HINT", "\"${properties.getProperty("OWNER_NAME_HINT") ?: ""}\"")
```

In the `release` build type:

```kotlin
buildConfigField("String", "OWNER_NAME_HINT", "\"\"")
```

**Verify**: `grep -c OWNER_NAME_HINT app/build.gradle.kts` → 2.

### Step 3: GeminiManager — rewrite the prompt (Step-2 field + Categories feed it)

Inside `extractExpenseData`, build the prompt as:

```kotlin
val categoryList = Categories.ALL.joinToString(", ") { "\"$it\"" }
val ownerHint = BuildConfig.OWNER_NAME_HINT.trim().takeIf { it.isNotBlank() }
    ?.let { "\n    * The user is: $it. Transfers where the sender or recipient matches this are TRANSFER." }
    ?: ""

val prompt = """
    Extract the following information from this bank receipt image:
    - Date (ISO format YYYY-MM-DD). If the year is in Thai Buddhist Era (e.g., 67, 68, 69), convert to AD (2024, 2025, 2026).
    - Amount (Number only)
    - Merchant Name (The recipient name)
    - Category: choose EXACTLY ONE of $categoryList.
    - Bank Name (e.g., K-Plus, SCB, PromptPay)
    - Type: Identify if this is "EXPENSE" or "INCOME" or "TRANSFER".
        * TRANSFER: money moved between the user's OWN accounts (sender and recipient are the same person).
        * EXPENSE: money went to anyone else, including "โอนเงินสำเร็จ" (Transfer), "เติมเงิน" (Top-up), or "ชำระเงิน" (Payment) to third parties. Most Thai bank slips are EXPENSES.
        * INCOME: only if the slip explicitly says "รับเงิน" (Received money) from someone else.
        * Sender and receiver may be written in different scripts (Thai vs romanized English). Treat plausible transliterations of the same name as the same person.$ownerHint

    Return ONLY a JSON object with these keys: date, amount, merchantName, category, bankName, type.
""".trimIndent()
```

The `"Ariya"` line is gone. Nothing else in the file changes.

**Verify**: `grep -c 'Ariya' GeminiManager.kt` → 0; `grep -c 'EXACTLY ONE'` → 1;
`grep -c 'OWNER_NAME_HINT'` → 1; `grep -c 'Categories.ALL'` → 1.

### Step 4: ExpenseDao — two targeted update methods

```kotlin
@Query("UPDATE expenses SET category = :category WHERE id = :id")
suspend fun updateCategory(id: Long, category: String)

@Query("UPDATE expenses SET type = :type, category = :category WHERE id = :id")
suspend fun updateTypeAndCategory(id: Long, type: String, category: String)
```

Deliberately NOT `@Update`-whole-entity (avoids racing concurrent edits).

**Verify**: both greps hit once each.

### Step 5: MainActivity — tap handling + bottom sheet

1. In `ExpenseTrackerApp`, add state and the sheet hookup:

```kotlin
val scope = rememberCoroutineScope()
var selectedExpense by remember { mutableStateOf<Expense?>(null) }
```

After the LazyColumn (still inside the Column), render when non-null:

```kotlin
selectedExpense?.let { selected ->
    CategoryPickerSheet(
        expense = selected,
        onDismiss = { selectedExpense = null },
        onSelectCategory = { category ->
            scope.launch { expenseDao.updateCategory(selected.id, category) }
            selectedExpense = null
        },
        onConvertType = { newType ->
            scope.launch { expenseDao.updateTypeAndCategory(selected.id, newType, "") }
            selectedExpense = null
        }
    )
}
```

2. `items(expenses)` becomes:

```kotlin
items(expenses) { expense ->
    ExpenseItem(expense, onClick = { selectedExpense = expense })
}
```

3. `ExpenseItem` gains the click contract and fixes the subtitle:

```kotlin
@Composable
fun ExpenseItem(expense: Expense, onClick: () -> Unit) {
    ...
    val subtitle = listOf(expense.bankName, expense.category, expense.type)
        .filter { it.isNotBlank() }
        .joinToString(" • ")
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(
                enabled = expense.type == "EXPENSE" || expense.type == "TRANSFER",
                onClick = onClick
            )
    ) {
        ...
        Text(text = subtitle, ...)
    }
}
```

4. New composable (same file):

```kotlin
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CategoryPickerSheet(
    expense: Expense,
    onDismiss: () -> Unit,
    onSelectCategory: (String) -> Unit,
    onConvertType: (String) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(text = expense.merchantName, style = MaterialTheme.typography.titleMedium)
            if (expense.type == "EXPENSE") {
                Spacer(modifier = Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Categories.ALL.forEach { category ->
                        FilterChip(
                            selected = expense.category == category,
                            onClick = { onSelectCategory(category) },
                            label = { Text(category) }
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onConvertType(if (expense.type == "EXPENSE") "TRANSFER" else "EXPENSE") }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "⇄", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (expense.type == "EXPENSE") "This was a transfer between my own accounts"
                           else "This is actually an expense",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
```

Notes: the `⇄` glyph avoids adding the heavyweight
`material-icons-extended` dependency. Chip selected-state derives from
`expense.category == category` — off-list strings select nothing (decision 5,
for free). No confirmation button: chip tap commits instantly (decision 2).

**Verify**: `grep -c 'ModalBottomSheet' MainActivity.kt` ≥ 2; compile passes.

### Step 6: Compile + tests

`./gradlew :app:compileDebugKotlin -q` → exit 0
`./gradlew :app:testDebugUnitTest -q` → exit 0

### Step 7: On-device verification (emulator-5554 is running)

1. `./gradlew :app:installDebug` then launch the app.
2. Manual matrix:
   - Tap an EXPENSE row → sheet with 9 chips + convert row. Tap a chip → row
     subtitle updates, sheet closes.
   - Tap a TRANSFER row → sheet shows ONLY the convert row. Tap it → type
     flips to EXPENSE (row now categorizable).
   - Tap an INCOME row → nothing happens.
   - A row with an off-list category → raw string visible, no chip selected
     when opened.
   - A row with blank category → subtitle has no dangling "•".

## Test plan

No new unit tests: the changed logic is UI-coupled (Compose + Room); the
parser path is untouched and stays guarded by `ExpenseParserTest`. Operator
manual matrix in Step 7 is the gate. Optional: after merging, operator adds
`OWNER_NAME_HINT=...` to `local.properties` and rebuilds to activate the
owner-hint sentence.

## Done criteria

- [ ] Both gradle commands exit 0
- [ ] `grep 'Ariya' GeminiManager.kt` → 0 matches
- [ ] `grep -c 'OWNER_NAME_HINT' app/build.gradle.kts` → 2
- [ ] INCOME rows not clickable; EXPENSE/TRANSFER sheets behave per matrix
- [ ] `git status` shows only the five in-scope files (plus the new file)
- [ ] plans/README.md row 014 added by reviewer/operator after merge

## STOP conditions

- Live code diverges from "Current state" excerpts (e.g. prompt text already
  rewritten, or ExpenseItem already takes onClick).
- Any unit-test failure (baseline is a fully green suite).
- A change appears to require touching an out-of-scope file (especially the
  DB version or parser).

## Maintenance notes (inherit these — do not re-derive later)

- **Summary rules (future feature)**: spending summaries aggregate
  `type = 'EXPENSE'` ONLY; income summaries `type = 'INCOME'` ONLY; TRANSFER
  rows appear in neither (net-zero self-movement). Any category not in
  `Categories.ALL` rolls up to "Other" at query time
  (`CASE WHEN category IN (...) THEN category ELSE 'Other' END`).
- **One-time data reset (operator, after merge)**: uninstall/reinstall the
  app to wipe BOTH `expenses` AND `failed_receipts` (the failure blacklist
  survives an expenses-only wipe and would skip those receipts forever), then
  "Scan Now". This regenerates all rows under the new prompt. Must happen
  BEFORE users hand-correct categories — wiping after that destroys manual work.
- **Known limitation 1**: false-INCOME rows have no UI correction path
  (income rows are static). Escape hatch is SQL or the wipe. Future: editable
  type for income.
- **Known limitation 2**: if both sides of a self-transfer are scanned, the
  incoming slip may be typed INCOME (it says รับเงิน) and would inflate income
  summaries. If it bites: extend OWNER_NAME_HINT / scanner-side suppression.
  Out of scope here.
- **A2 follow-up**: enforce the taxonomy with a response-schema enum in
  `generationConfig` if the configured model supports it — one-line change,
  verify with a single scan cycle before relying on it.
