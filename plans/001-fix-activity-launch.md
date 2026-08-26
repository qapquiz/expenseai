# Plan 001: Make MainActivity actually launch the UI

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md`.
>
> **Drift check (run first)**: This repo has NO commits yet (`git log` fails:
> "current branch 'master' does not have any commits yet"). Instead of a SHA
> diff, open each in-scope file and compare it against the "Current state"
> excerpts below. On any mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: bug
- **Planned at**: no commits yet (initial staged state), 2026-08-26

## Why this matters

`MainActivity` is declared in the manifest as the launcher activity
(`app/src/main/AndroidManifest.xml:18-29`) but its body is literally empty
apart from a leftover `// ... existing code ...` comment. The app compiles
and installs but shows a permanent blank screen: `onCreate` is never
overridden, `setContent` is never called, and the fully-implemented
`ExpenseTrackerApp` composable in the same file is dead code. This single
change makes the entire product usable.

## Current state

- `app/src/main/java/com/example/expense_ai/MainActivity.kt` — contains an
  empty activity plus working composables:

```kotlin
// MainActivity.kt:59-61
class MainActivity : ComponentActivity() {
// ... existing code ...
}
```

```kotlin
// MainActivity.kt:63-64 — defined but never rendered
@Composable
fun ExpenseTrackerApp(geminiManager: GeminiManager, modifier: Modifier = Modifier) {
```

The file already imports everything needed: `Bundle`, `ComponentActivity`,
`setContent`, `enableEdgeToEdge`, `ExpenseaiTheme` (lines 3-6, 30).

- `app/src/main/java/com/example/expense_ai/ui/theme/Theme.kt` defines
  `ExpenseaiTheme` (already imported in MainActivity.kt:30).
- `GeminiManager` has a no-arg constructor (see
  `app/src/main/java/com/example/expense_ai/GeminiManager.kt:9`), matching
  how the preview at `MainActivity.kt:168` constructs it.

Repo conventions: Kotlin, Compose Material3, package root
`com.example.expense_ai`. Match the existing code style in
MainActivity.kt (no comments except where meaningfully explanatory).

## Commands you will need

| Purpose   | Command                              | Expected on success |
|-----------|--------------------------------------|---------------------|
| Compile   | `./gradlew :app:compileDebugKotlin -q` | exit 0, no output  |
| Assemble  | `./gradlew :app:assembleDebug -q`      | exit 0             |

First Gradle run may download dependencies; allow several minutes.

## Scope

**In scope** (the only file you should modify):
- `app/src/main/java/com/example/expense_ai/MainActivity.kt`

**Out of scope** (do NOT touch):
- Any other file, including the composables below line 62 — they already work.
- `AndroidManifest.xml` — launcher declaration is correct.

## Git workflow

- Branch: `advisor/001-fix-activity-launch`
- Commit message example: `Fix MainActivity to install ExpenseTrackerApp content`
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: Implement onCreate

Replace the empty class body (lines 59-61) with:

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ExpenseaiTheme {
                ExpenseTrackerApp(geminiManager = GeminiManager())
            }
        }
    }
}
```

This matches the imports already present at the top of the file
(`android.os.Bundle`, `androidx.activity.ComponentActivity`,
`androidx.activity.enableEdgeToEdge`, `setContent` is available via
`androidx.activity.compose.setContent` import at line 5).

**Verify**: `./gradlew :app:compileDebugKotlin -q` → exit 0, no output

### Step 2: Confirm nothing else changed

**Verify**: `git status --short -- app/src/main/java/com/example/expense_ai/MainActivity.kt` → shows the modified file; `git diff -- app/src/main/java/com/example/expense_ai/MainActivity.kt` shows ONLY the class-body replacement.

## Test plan

No new automated tests (instrumented launch testing requires a device/emulator
and is out of scope for this plan). Manual verification by the operator:
install the debug APK (`./gradlew :app:assembleDebug`) and confirm the
"Scan Bank Receipts Now" button renders instead of a blank screen.

## Done criteria

- [ ] `./gradlew :app:compileDebugKotlin -q` exits 0
- [ ] `./gradlew :app:testDebugUnitTest -q` exits 0 (existing template tests still pass)
- [ ] `grep -n "existing code" app/src/main/java/com/example/expense_ai/MainActivity.kt` returns no matches
- [ ] No files outside the in-scope list are modified (`git status`)
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back if:

- The class body at MainActivity.kt:59-61 does not match the excerpt (drift).
- The compile fails twice after a reasonable fix attempt.
- You find `setContent` is not resolvable despite the
  `androidx.activity.compose.setContent` import being present.

## Maintenance notes

- The preview composable `ExpenseTrackerPreview` (MainActivity.kt:164-169)
  constructs `GeminiManager()` which reads `BuildConfig.GEMINI_API_KEY`; that
  is safe in previews but see plans/009 for the API-key story.
- Reviewer should scrutinize: theme wrapper present, edge-to-edge enabled,
  no logic moved out of the composables.
