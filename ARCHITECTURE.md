# ARCHITECTURE.md — Proposed agent architecture (design only, not implemented)

Baseline = the existing app described in PROJECT_STATE.md. The agent is built *around* the existing voice stack; it does not replace it.

## 1. Component map
```
ANAS AI
├── Voice Interface (EXISTS)  AudioRecorder → GeminiLiveWebSocket → AudioPlayer
├── Agent Core (new)          Intent Understanding · Planner · Tool Selection · Execution Loop · Verification
├── Tool Registry (new)       Root · Android · Accessibility · Intent · Package · File · Diagnostic · Coding
├── Observation Layer (new)   Device State · App State · UI State · Shell Output · Logs
├── Safety Layer (new)        Permission Checks · Risk Classification · Confirmation · Command Validation
└── Memory / State (partial)  Conversation (ChatRepository EXISTS) · Task State · Tool Results · Project State (md files)
```
Suggested package layout (new, under `com.anas.ai`): `agent/`, `tools/`, `root/`, `accessibility/`, `safety/`, `observation/`. Keep the existing packages untouched.

## 2. Automation hierarchy (least privilege wins)
1. Native Android APIs (e.g. CameraManager torch, AudioManager, BatteryManager)
2. Intents / PackageManager / permitted system APIs (launch app, open settings screens)
3. AccessibilityService UI automation — user-enabled only
4. Root shell — only if levels 1–3 cannot do it and root is available

The Tool Selector must pick the lowest level that can complete the task and log why a higher level was needed.

## 3. Agent loop
```
User voice → Gemini Live → understanding → task classification → planning
→ tool selection → safety check → tool execution → observation → verification
→ result → voice response
```
The agent must not map every utterance to a shell command. Steps: (1) understand, (2) decide whether action is needed, (3) choose tool, (4) decide whether root is truly needed, (5) execute, (6) observe, (7) verify, (8) report. Cap iterations and total runtime per task; allow user cancel by voice/button.

## 4. Gemini Live integration (adapted to actual code)
Current code has **no tool calling** (see PROJECT_STATE findings 1–2). Recommended strategy: use Gemini Live's native function calling rather than parsing free text.
- Setup message: add `tools: [{functionDeclarations: [...]}]` generated from the Tool Registry (extend `SetupPayload` in `GeminiLiveModels.kt`).
- Incoming: handle top-level `toolCall.functionCalls[{id,name,args}]` and `toolCallCancellation` in `GeminiLiveWebSocket.handleIncomingMessage`; add `onToolCall(...)` to `Listener`.
- Outgoing: send `toolResponse.functionResponses[{id,name,response}]` after execution.
- `MainViewModel` forwards calls to an `AgentCore`/`ToolDispatcher`; the websocket stays transport-only.
- Update the system prompt (remove the "no automation" rules, describe tools, require confirmation behavior) — only when real tools exist.
- Enable input/output audio transcription so chat history records real user text and tool use can be audited.
- Verify against current Gemini Live docs at implementation time; the model id in use is a preview.
- Because session renewal drops context, add session resumption or re-seed task state on renewal before long multi-step tasks.
- Agent work that touches other apps needs a foreground service (the session currently lives in the ViewModel).

## 5. Tool abstraction
```
interface Tool {
  name: String; description: String
  inputSchema: JSON schema          // also used for Gemini functionDeclarations
  riskLevel: RiskLevel              // may be computed per-invocation
  suspend fun execute(args): ToolResult
}
ToolResult { success, output, error, exitCode?, durationMs, metadata }
```
`ToolResult` should follow the existing Kotlin style (data classes, StateFlow/coroutines); no new libraries.

| Tool | Responsibility | Level | Depends on |
|---|---|---|---|
| AppLaunchTool | launch app by name/package | 2 | PackageManager |
| IntentTool | fire allow-listed intents (settings, dial UI, view URL) | 2 | Intents |
| PackageManagerTool | list/query apps | 2 (root for install/uninstall) | PackageManager |
| DeviceInfoTool | battery, network, storage, props | 1 | system services |
| AccessibilityTool / UIInspectorTool | read UI tree, find nodes | 3 | AccessibilityService |
| TapTool / SwipeTool / ScrollTool / BackTool / TextInputTool | gestures, text entry | 3 | AccessibilityService |
| RootShellTool | validated root command | 4 | RootManager + Safety |
| ProcessTool / LogcatTool | process list, logs | 4 (some 1–2) | RootManager |
| FileTool | read/list/write permitted paths | 1–4 | storage APIs / root |
| GitTool / CodeSearchTool / CodeEditTool / GradleTool / BuildTool | coding-agent features | future | needs a shell environment; defer |

## 6. Root subsystem (Phase 2 design)
- `RootManager`: detect `su`, verify real access (run `id`, expect uid=0), run commands async on `Dispatchers.IO`, capture stdout/stderr/exit code, enforce timeout, support cancellation, never block the main thread, catch all exceptions into `ToolResult`.
- `RootShellExecutor`: low-level process I/O for a single `su -c` invocation (start with one-shot; persistent shell is optional later). Bound output size.
- `RootCommandValidator`: parse command, reject shell chaining/redirection/substitution unless allow-listed, normalize paths, block protected targets.
- `RootSafetyManager`: map validated command → `RiskLevel`; decide ALLOW / CONFIRM / BLOCK; keep an audit log.
- `RootTool`: the Tool-Registry adapter; exposes structured operations rather than a raw shell where possible (e.g. `list_packages`, `getprop`, `read_file`).

Risk model:
| Level | Examples | Policy |
|---|---|---|
| SAFE | `id`, `whoami`, `getprop`, read-only diagnostics | allow |
| LOW | `ps`, `pm list packages`, `ls` | allow, log |
| MEDIUM | `pm disable-user`, modify non-critical app files | confirm |
| HIGH | change system settings/props, edit protected files, stop system processes | explicit confirm, show exact command |
| CRITICAL | `rm -rf` on system paths, `dd`/`mkfs`/partition, bootloader ops, anything that can brick | block by default |

Default-deny: unknown commands are treated as HIGH, never SAFE. Confirmation must come from the user (UI tap or clear spoken confirmation after the exact action is read back), never from the model alone.

## 7. Accessibility subsystem (design)
- Separate module-like package `com.anas.ai.accessibility` with an `AccessibilityService` subclass, declared in the manifest with an `accessibility_service_config.xml` and a user-facing description string.
- Never auto-enable. UI offers a button that opens `Settings.ACTION_ACCESSIBILITY_SETTINGS`; the app checks enabled-state and degrades gracefully.
- Capabilities: dump hierarchy (bounded), find by text/contentDescription/viewId, click, set text, scroll, global actions (back/home/recents), gesture dispatch.
- Integration points: `UIInspectorTool` and gesture tools call a service bridge (singleton holder with weak ref); results flow to the Observation Layer. Respect `FLAG_SECURE`/password fields; do not log sensitive text.

## 8. Observation, memory and safety integration
- Observation snapshots are compact text (not screenshots) to keep tokens/latency low; truncate shell output.
- Task State is in-memory per task; Tool Results stored with timestamps; chat history keeps using ChatRepository.
- Safety Layer sits between tool selection and execution for every tool, not only root; it also checks runtime permissions and Accessibility state.
