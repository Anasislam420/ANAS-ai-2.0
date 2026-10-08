# ANAS AI

Android voice assistant (`com.anas.ai`) built on Gemini Live, being evolved step by step into a lightweight Android AI agent.

## Current purpose
Real-time voice conversation: mic audio streams to Gemini Live over WebSocket and the spoken reply plays back, with an animated orb UI, chat history and settings (API key, voice, personality). Reasoning stays in the cloud; no local model is bundled.

## Current architecture
Single `:app` module, Kotlin, ViewBinding, coroutines/StateFlow, OkHttp + Gson. Key classes: `MainViewModel`, `GeminiLiveWebSocket`, `AudioRecorder`, `AudioPlayer`, `ChatRepository`, `AppPreferences`, `PromptGenerator`. Details: [PROJECT_STATE.md](PROJECT_STATE.md), [ARCHITECTURE.md](ARCHITECTURE.md).

## Current phase
PHASE 1 — FOUNDATION / REPOSITORY BASELINE. Root, Accessibility and Agent Core are not implemented. Gradle wrapper is absent from the source and must be repaired in a later step.

## Roadmap
1. Phase 2 — Root Execution Engine (with safety controls)
2. Accessibility automation (user-enabled only)
3. Gemini tool calling + Agent Core + Tool Registry
4. Android/intent tools, observation layer, memory/task state

## Multi-account AI workflow
```
READ AGENT_RULES.md
↓ READ PROJECT_STATE.md
↓ READ ARCHITECTURE.md
↓ INSPECT GIT
↓ INSPECT EXISTING CODE
↓ IMPLEMENT ONLY CURRENT PHASE
↓ BUILD
↓ TEST
↓ UPDATE PROJECT_STATE.md
↓ UPDATE CHANGELOG.md
↓ COMMIT
↓ PUSH
```
Never rebuild the project; the API key is entered at runtime and must never be committed.
