# Expense AI

An Android app that automatically scans bank receipts from Thai banking apps and extracts transaction data into a local SQLite database using Gemini AI.

## Features
- **Automated Folder Scanning**: Monitors buckets like K PLUS, SCB EASY, and other bank-specific folders.
- **AI Data Extraction**: Uses Gemini 3 Flash to identify merchant, amount, date, and transaction type.
- **Background Processing**: Runs scans in the background using WorkManager.
- **Local Storage**: Persists data in a Room database.

## Prerequisites
- Android Studio Ladybug or newer.
- JDK 17+.
- A Google AI Studio API Key.

## Setup
1. Obtain an API key from [Google AI Studio](https://aistudio.google.com/).
2. Create a `local.properties` file in the project root if it doesn't exist.
3. Add your key: `GEMINI_API_KEY=your_actual_key_here`.
4. Note: The API key is only enabled for **debug** builds.

## Security
The Gemini key is embedded in debug APKs and extractable from them. Never distribute debug builds. If distribution is ever planned, move Gemini calls behind a backend proxy.

## Development
| Purpose | Command |
|---------|---------|
| Compile | `./gradlew :app:compileDebugKotlin` |
| Unit Tests | `./gradlew :app:testDebugUnitTest` |
| Assemble Debug | `./gradlew :app:assembleDebug` |
