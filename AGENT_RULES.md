# AGENT_RULES.md — Permanent rules for every AI agent on ANAS AI

1. **This is an existing, working project.** Never rebuild it from scratch, never create a second app or parallel managers.
2. **Read before coding:** AGENT_RULES.md → PROJECT_STATE.md → ARCHITECTURE.md → CHANGELOG.md → `git log -10 --oneline` → the source you will touch.
3. **Source code beats documentation.** If docs and code disagree, record the difference in PROJECT_STATE.md and treat the code as the baseline.
4. **Preserve working functionality.** Never silently delete or replace: `GeminiLiveWebSocket`, `AudioRecorder`, `AudioPlayer`, `MainViewModel` session flow, `ChatRepository`, `AppPreferences`, the orb UI. Changes to them need a documented, concrete reason.
5. **Package stays `com.anas.ai`.** No package renames. Application class is `JarvisApp` (legacy name; do not rename without an explicit task).
6. **Minimal dependencies, light APK.** No embedded LLMs. Every new dependency needs a written justification in PROJECT_STATE.md. Do not change AGP/Kotlin/Gradle/SDK versions without a technical reason.
7. **Least-privilege automation order:** (1) native Android APIs → (2) Intents / PackageManager → (3) user-enabled AccessibilityService → (4) root shell. Never use a higher level when a lower one can do the job.
8. **Root requires safety controls.** No unrestricted root shell for the AI. All root commands go through validation + risk classification (SAFE/LOW/MEDIUM/HIGH/CRITICAL). HIGH and CRITICAL require explicit user confirmation; CRITICAL (format, partition/bootloader ops, `rm -rf` on system paths, etc.) is blocked by default.
9. **Never silently enable the AccessibilityService** or bypass Android's user-facing enablement. The user turns it on manually in system settings; the app may only deep-link there.
10. **Never run destructive root commands without appropriate confirmation.**
11. **Do not trust the model blindly.** LLM output is untrusted input; it must pass the Safety Layer before reaching any tool.
12. **Git discipline:** run `git status`, `git branch`, `git log -5 --oneline` first; make focused commits; do not overwrite unrelated work.
13. **Update state after meaningful work:** PROJECT_STATE.md (status + NEXT AGENT TASK) and CHANGELOG.md in the same commit as the work.
14. **Verify honestly:** run `./gradlew assembleDebug` before claiming success; report the real result.
15. **One phase per account.** Implement only the phase named in PROJECT_STATE.md → NEXT AGENT TASK.
