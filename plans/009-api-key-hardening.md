# Plan 009: Reduce Gemini API key exposure and document the threat model

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md`.
>
> **Drift check (run first)**: This repo has NO commits yet. Compare live
> code against the "Current state" excerpts below; on mismatch, STOP.

## Status

- **Priority**: P2 (P1 IF this app will ever be distributed to other people)
- **Effort**: M
- **Risk**: LOW
- **Depends on**: none
- **Category**: security / docs
- **Planned at**: no commits yet (initial staged state), 2026-08-26

## Why this matters

The API key is compiled into `BuildConfig.GEMINI_API_KEY`, which lands as a
plain string constant in the DEX of every built APK. Anyone with the APK can
extract it (`strings`/`apktool`) and spend the owner's Gemini quota or incur
charges. This is acceptable ONLY while the app is personal-use and never
shared. This plan (a) documents that boundary, (b) stops release builds from
silently embedding the key, and (c) makes the app fail loudly with an
actionable message when no key is present instead of sending doomed requests.
A full fix (backend proxy or user-supplied key in EncryptedSharedPreferences)
is a product decision recorded here but NOT implemented — see Maintenance notes.

## Current state

- `app/build.gradle.kts:25-31`:

```kotlin
val properties = Properties()
val localPropertiesFile = project.rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    properties.load(localPropertiesFile.inputStream())
}
buildConfigField("String", "GEMINI_API_KEY", "\"${properties.getProperty("GEMINI_API_KEY") ?: ""}\"")
```

This runs for ALL variants including release.

- `local.properties` contains the real key value (location:
  `local.properties`, key `GEMINI_API_KEY`). It is correctly gitignored
  (`.gitignore` lists `local.properties`). NEVER copy its value anywhere.

- `GeminiManager.kt:12`: `apiKey = BuildConfig.GEMINI_API_KEY` — no blank-key check.

Repo conventions: single-module Gradle KTS build; README does not exist yet.

## Commands you will need

| Purpose | Command                                | Expected on success |
|---------|----------------------------------------|---------------------|
| Compile debug  | `./gradlew :app:compileDebugKotlin -q` | exit 0       |
| Assemble release | `./gradlew :app:assembleRelease -q`  | exit 0       |

## Scope

**In scope**:
- `app/build.gradle.kts`
- `app/src/main/java/com/example/expense_ai/GeminiManager.kt`
- `README.md` (create)

**Out of scope**:
- Any backend/proxy implementation.
- Changing where `local.properties` lives or its format.
- Touching `gradle.properties`.
- Writing the actual key value into ANY file.

## Git workflow

- Branch: `advisor/009-api-key-hardening`
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Inject the key into debug builds only

In `app/build.gradle.kts`, move the property-loading block and
`buildConfigField` call from `defaultConfig` into
`buildTypes { debug { ... } }`, so:

```kotlin
buildTypes {
    debug {
        val properties = Properties()
        val localPropertiesFile = project.rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            properties.load(localPropertiesFile.inputStream())
        }
        buildConfigField("String", "GEMINI_API_KEY", "\"${properties.getProperty("GEMINI_API_KEY") ?: ""}\"")
    }
    release {
        optimization { enable = false }
        buildConfigField("String", "GEMINI_API_KEY", "\"\"")
    }
}
```

Remove the block from `defaultConfig`. Release now embeds an empty string —
the APK carries nothing to steal.

**Verify**: `./gradlew :app:compileDebugKotlin -q` → exit 0

### Step 2: Fail loudly on missing key

At the top of BOTH public methods in `GeminiManager.kt`
(`extractExpenseData`, `generateResponse`), add:

```kotlin
if (BuildConfig.GEMINI_API_KEY.isBlank()) return "Gemini API key is not configured. Add GEMINI_API_KEY=<key> to local.properties (debug builds only)."
```

(For the suspend `extractExpenseData`, place the check before
`withContext`; returning a String matches both methods' `String?` returns.)

**Verify**: `./gradlew :app:testDebugUnitTest -q` → exit 0

### Step 3: Document setup and security boundary

Create `README.md` at repo root covering:
1. What the app does (one paragraph).
2. Build prerequisites (Android Studio, JDK per AGP 9 requirement).
3. Setup: obtain a Google AI Studio API key; put `GEMINI_API_KEY=<key>` in
   `local.properties` (never commit it); note it works in debug builds only.
4. A **Security** section stating verbatim: "The Gemini key is embedded in
   debug APKs and extractable from them. Never distribute debug builds. If
   distribution is ever planned, move Gemini calls behind a backend proxy."
5. Verification commands table (`./gradlew :app:compileDebugKotlin`,
   `./gradlew :app:testDebugUnitTest`).

Do not include any real key material — describe it as `<key>`.

**Verify**: `grep -rn "AIza" README.md plans/ app/src/ gradle/ 2>/dev/null | grep -v Binary` → no matches

## Test plan

No unit-testable logic (BuildConfig constants are compile-time). Manual
verification (operator): temporarily rename the key line in local.properties,
run the debug app, confirm the UI/log shows the new actionable message; restore.

## Done criteria

- [ ] `./gradlew :app:compileDebugKotlin -q` exits 0
- [ ] `./gradlew :app:testDebugUnitTest -q` exits 0
- [ ] `./gradlew :app:assembleRelease -q` exits 0
- [ ] `grep -n "GEMINI_API_KEY" app/build.gradle.kts` shows the buildConfigField inside buildTypes blocks only, not defaultConfig
- [ ] `README.md` exists and contains the word "Security"
- [ ] No secret values appear in any tracked file (`git status` clean of local.properties; grep gate above passes)
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back if:

- The build.gradle.kts excerpt doesn't match live code.
- Moving buildConfigField into buildTypes breaks KSP/Room compilation in ways not fixed by a sync — report the error.
- You encounter any real key value outside local.properties — report the location, do NOT copy it anywhere.

## Maintenance notes

- Decision deferred (needs product input): proxying calls through a small
  backend vs. user-supplied keys stored in EncryptedSharedPreferences. Either
  supersedes Step 2's guard partially — keep it regardless as defense-in-depth.
- Reviewer should scrutinize: release variant truly gets an empty literal;
  README contains no key material.
