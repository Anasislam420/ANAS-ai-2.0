# PROJECT_STATE.md

> Last updated by: repository restoration session (Account 1 audit + new repo baseline). Source imported from `JarvisAssistant-Android.zip` (top-level `jarvis/` folder flattened to the repo root). The ZIP contained no `.git` directory and no Gradle wrapper.

## Current Phase
PHASE 1 — FOUNDATION / REPOSITORY BASELINE

## Project identity
- Application name: ANAS AI (Gradle `rootProject.name = "ANAS AI"`)
- Package / applicationId / namespace: `com.anas.ai`
- Version: versionCode 115, versionName 1.1.5
- Repository: https://github.com/Anasislam420/ANAS-ai-2.0.git (new permanent source of truth; the previous repo was deleted)
- Branch: `main`
- Commit: initial baseline commit `chore: restore ANAS AI project baseline` (see `git log`)
- Original ZIP root was `jarvis/`; its contents are now at the repository root (app module is `:app`)

## Build configuration (from source)
- Android Gradle Plugin 8.7.2, Kotlin 2.0.20
- compileSdk 34, targetSdk 34, minSdk 26
- Java/Kotlin JVM target 17, viewBinding enabled
- Release: `isMinifyEnabled = false`
- `gradle.properties`: AndroidX on, nonTransitiveRClass, 2 GB Gradle heap
- Repositories: google(), mavenCentral(), `FAIL_ON_PROJECT_REPOS`
- **Gradle wrapper is absent from the provided source** (no `gradlew`, `gradlew.bat`, `gradle/wrapper/`). It was deliberately not invented; repair in a later controlled step (AGP 8.7.2 needs Gradle 8.9+).
- Dependencies: core-ktx 1.13.1, appcompat 1.7.0, material 1.12.0, constraintlayout 2.1.4, lifecycle (viewmodel/livedata/runtime-ktx) 2.8.6, activity-ktx 1.9.3, kotlinx-coroutines-android 1.8.1, OkHttp 4.12.0, Gson 2.10.1, androidx.webkit 1.12.0
- Permissions: RECORD_AUDIO, INTERNET, ACCESS_NETWORK_STATE, MODIFY_AUDIO_SETTINGS
- Manifest: `allowBackup=true`, portrait-only activities, network security config forbids cleartext

## Current functionality (verified by reading source; NOT run on a device)
- Live voice conversation with Gemini Live over WebSocket (audio in, audio out)
- 16 kHz mono mic capture, 24 kHz mono playback, barge-in (interruption flushes playback)
- Power button starts/stops a session; mic mute toggle (persisted)
- Conversation states IDLE / LISTENING / THINKING / SPEAKING driving an animated WebView orb
- Settings: API key, user name, model, voice, personality (Assistant / Girlfriend / Professional)
- Chat history (last 100 turns) in SharedPreferences, shown in a bottom sheet
- Automatic reconnect (max 5 attempts) and session renewal every 9 minutes

## Existing architecture (actual)
Single module, single-package-tree MVVM-lite, no DI framework, no database.
```
com.anas.ai
├── JarvisApp               Application; holds AppPreferences + ChatRepository singletons
├── ui/home                 MainActivity (viewBinding) + MainViewModel (owns session)
├── ui/orb/OrbHelper        WebView wrapper for assets/orb/index.html
├── ui/chat                 ChatHistoryBottomSheet, ChatAdapter
├── ui/settings             SettingsActivity
├── audio                   AudioRecorder, AudioPlayer, AudioUtils
├── network                 GeminiLiveWebSocket
├── data/model              GeminiLiveModels.kt (+ GeminiConstants), ConversationState, ChatTurn
├── data/repository         ChatRepository
├── data/preferences        AppPreferences
└── util                    PromptGenerator
```

## Important classes
- `MainViewModel` — owns session lifecycle; wires recorder → websocket → player; exposes StateFlows to UI; `finalizeTurn()` saves chat turns.
- `GeminiLiveWebSocket` — OkHttp WebSocket to `wss://generativelanguage.googleapis.com/ws/...BidiGenerateContent?key=<apiKey>`; sends a setup message (model, AUDIO modality, voice, system instruction); sends `realtimeInput.audio` chunks; parses `serverContent` (`modelTurn` text/inlineData audio, `interrupted`, `turnComplete`) and `setupComplete`. Uses a generation counter to ignore stale sockets, 8 s ping, exponential-ish backoff, 9-minute renewal.
- `AudioRecorder` — `AudioRecord` (VOICE_COMMUNICATION), 1280-byte (40 ms) chunks on Dispatchers.IO, amplitude callback, mute flag.
- `AudioPlayer` — queue + playback thread on an `AudioTrack` (USAGE_ASSISTANT, low-latency); reports playing state and amplitude.
- `PromptGenerator` — builds system prompt (date, user name, personality, core rules).
- `AppPreferences` — SharedPreferences `jarvis_prefs` (API key, model, voice, personality, user name, mic mute, chat JSON).
- `ChatRepository` — StateFlow of `ChatTurn`, capped at 100, persisted via AppPreferences.

## Audit findings / doc-vs-code differences
1. **No tool calling.** The setup message has no `tools`/`functionDeclarations`; `GeminiLiveWebSocket` does not handle `toolCall`/`toolCallCancellation` and cannot send `toolResponse`. Models in `GeminiLiveModels.kt` have no representation for them.
2. **System prompt forbids automation** (CORE RULE 2–3: "You DO NOT have phone automation…"). Must be changed deliberately when tools are added, or the model will refuse to use them.
3. **Automation-looking UI is static mockup.** `activity_main.xml` contains tiles (Open Apps, Wi-Fi, Bluetooth, Camera, Flashlight, Music, Location), mode buttons (Vision/Camera/Screen), an "activity" list ("Opened YouTube", "Captured Screenshot", "Enable Battery Saver", …), and "SMART AUTOMATIONS"/"AI TOOLS" nav. `MainActivity` binds **no click handlers** to any of them. They do nothing. Do not mistake them for working features.
4. **User transcript is not captured.** `finalizeTurn()` stores the literal string "Spoken user query"; there is no `inputAudioTranscription`/`outputAudioTranscription` in setup. Model responses are audio-only (`responseModalities=["AUDIO"]`); the `text` branch rarely fires.
5. **Personality prompts use Hinglish**, while the product goal is Bengali/Banglish/English.
6. **API key** is sent as a URL query parameter and stored in plain SharedPreferences; `allowBackup=true` may include prefs in backups. Candidates for hardening (not done).
7. **Session renewal** opens a new socket without carrying conversation context (no session resumption handle). Context is lost every 9 minutes.
8. **Orb WebView** enables JS and `allowFileAccessFromFileURLs=true` for a local asset page; low risk now, revisit before exposing any JS bridge.
9. Model list contains a single hardcoded preview model id (`gemini-2.5-flash-native-audio-preview-12-2025`); it may be retired by Google.
10. No tests exist; `androidx.test` runner is configured but no test dependencies are declared.
11. Mic capture keeps running during session even while SPEAKING (relies on VOICE_COMMUNICATION echo cancellation).
12. Session runs in `MainViewModel`/Activity scope: no foreground service, so it ends/stalls when the app is backgrounded. Agent features that act on *other* apps will need a foreground service.

## Current limitations
Cannot open apps, control settings, read/operate other apps' UI, run shell/root commands, call tools, remember across sessions beyond a 100-turn log, or run in background.

## Status flags
- ROOT AUTOMATION STATUS: NOT IMPLEMENTED / PREPARATION PHASE
- ACCESSIBILITY AUTOMATION STATUS: NOT IMPLEMENTED / PREPARATION PHASE
- AGENT CORE STATUS: NOT IMPLEMENTED / PREPARATION PHASE

## Build verification
**BUILD NOT VERIFIED — ENVIRONMENT LIMITATION.** The sandbox had no Android SDK or Gradle, and the source has no wrapper. No source files were modified relative to the ZIP (only `.gitignore` extended and docs added). First action for the next agent: add/confirm the wrapper, run `./gradlew assembleDebug`, record the result here.

## Security scan
No API keys, keystores, `local.properties`, APKs or `google-services.json` were found in the source. The Gemini API key is entered by the user in Settings at runtime.

## NEXT AGENT TASK
**Implement Phase 2: Root Execution Engine.**

Before coding:
1. Read AGENT_RULES.md
2. Read PROJECT_STATE.md
3. Read ARCHITECTURE.md
4. Read CHANGELOG.md
5. Inspect recent Git commits
6. Inspect existing source
7. Do not rebuild the application
8. Implement only the Root Execution Engine
9. Preserve all existing functionality
10. Build and test
11. Update PROJECT_STATE.md
12. Update CHANGELOG.md
13. Commit and push

Step 0 (housekeeping): repair the Gradle wrapper if still absent and run a baseline `assembleDebug`.

Phase 2 scope: package `com.anas.ai.root` with `ToolResult`, `RootManager` (su detection, availability check, async exec with stdout/stderr/exit code, timeout, cancel, no main-thread blocking), `RootShellExecutor`, `RootCommandValidator`, `RootSafetyManager`, `RootTool`. No new dependencies (use `ProcessBuilder`/`Runtime` + coroutines). No wiring to Gemini yet; expose a small internal debug entry point only. Add unit tests for the validator/risk classifier.
