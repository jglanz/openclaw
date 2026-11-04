---
summary: "Global voice wake words (Gateway-owned) and how they sync across nodes"
read_when:
  - Changing voice wake words behavior or defaults
  - Adding new node platforms that need wake word sync
title: "Voice wake"
---

Wake words are **one global list owned by the Gateway** — there are no per-node custom lists. Any node or app UI can edit the list; the Gateway persists the change and broadcasts it to every connected client.

- **Control UI**: wake-word editor under **Settings → Talk**.
- **macOS**: local Voice Wake enable/disable toggle. Requires macOS 26+; see [Voice wake (macOS)](/platforms/mac/voicewake) for runtime/PTT details.
- **iOS**: local Voice Wake enable/disable toggle in Settings.
- **Android**: local Voice Wake enable/disable toggle and wake-word editor in Settings → Voice. Requires Android on-device speech recognition.

## Storage

Wake words and routing rules live in the Gateway state database, `~/.openclaw/state/openclaw.sqlite` by default (override with `OPENCLAW_STATE_DIR`), under the `config_machine_state` keys `voicewake.triggers` and `voicewake.routing`. The state keys and the Gateway methods below name the wake-word list `triggers`. Legacy `settings/voicewake.json` and `settings/voicewake-routing.json` are `openclaw doctor --fix` migration inputs only — runtime never reads them.

## Protocol

### Trigger list

| Method          | Params                   | Result                   |
| --------------- | ------------------------ | ------------------------ |
| `voicewake.get` | none                     | `{ triggers: string[] }` |
| `voicewake.set` | `{ triggers: string[] }` | `{ triggers: string[] }` |

`voicewake.set` normalizes input: trims whitespace, drops empty entries, keeps at most 32 triggers, and truncates each to 64 UTF-16 code units without splitting surrogate pairs. An empty result falls back to the built-in defaults (`openclaw`, `claude`, `computer`).

### Routing (trigger to target)

| Method                  | Params | Result                               |
| ----------------------- | ------ | ------------------------------------ |
| `voicewake.routing.get` | none   | `{ config: VoiceWakeRoutingConfig }` |

```json
{
  "version": 1,
  "defaultTarget": { "mode": "current" },
  "routes": [{ "trigger": "robot wake", "target": { "sessionKey": "agent:main:main" } }],
  "updatedAtMs": 1730000000000
}
```

Each route `target` supports exactly one of:

- `{ "mode": "current" }`
- `{ "agentId": "main" }`
- `{ "sessionKey": "agent:main:main" }`

Limits: at most 32 routes, trigger text at most 64 characters. Route triggers are normalized for matching and duplicate detection by lowercasing, stripping leading/trailing punctuation from each word, and collapsing whitespace (`"Hey, Bot!!"` and `"hey bot"` match and count as duplicates) — this is a stricter normalization than the plain trim used for the global trigger list above.

### Events

| Event                       | Payload                              |
| --------------------------- | ------------------------------------ |
| `voicewake.changed`         | `{ triggers: string[] }`             |
| `voicewake.routing.changed` | `{ config: VoiceWakeRoutingConfig }` |

Both broadcast to every WebSocket client with read scope (macOS app, WebChat, and similar) and to every connected node. A node also gets both as an initial snapshot push right after it connects.

## Client behavior

- **macOS**: calls `voicewake.set`/`voicewake.get` and listens for `voicewake.changed` to stay in sync with other clients.
- **iOS**: calls `voicewake.set`/`voicewake.get` and listens for `voicewake.changed` to keep local wake-word detection responsive.
- **Android**: calls `voicewake.set`/`voicewake.get`, listens for `voicewake.changed`, and advertises `voiceWake` while enabled. Recognition stays on-device and foreground-only; it pauses while Talk, manual dictation, voice-note capture, or message speech owns audio.

## Android Talk session commands

Android Voice Wake (**Settings → Voice → Listen for wake words**) must be enabled for every spoken session command, including commands spoken during an active Talk session. Whether you need the wake word depends on the Talk state:

- **Idle or paused:** say a configured wake word, then an action; the session target is optional. For example, **“Hey Jarvis start”**, **“Jarvis pause”**, or **“Jarvis turn off the sound”**. A leading `hey` is optional, and `Jarvis` is only an example, not a built-in default. A paused session can only be resumed with a wake word.
- **Active Talk:** the wake word is optional, but the phrase needs an action and at least one target, such as **“could you end the conversation”**, **“let’s stop the session”**, **“pause listening”**, or **“mute the speaker”**. A lone **“stop”**, **“pause”**, or **“stop it”** remains ordinary speech. **“Jarvis stop”** ends an active Talk session, not just the current reply.

The matcher accepts action synonyms and varied word order. Start includes `start`, `begin`, `open`, `launch`, `turn on`, `switch on`, `fire up`, and `kick off`; stop includes `stop`, `end`, `finish`, `close`, `quit`, `exit`, `terminate`, `hang up`, `turn off`, `switch off`, `shut down`, and `shut off`; pause includes `pause`, `hold`, `hold on`, `put on hold`, and `suspend`; resume includes `resume`, `continue`, `unpause`, `un pause`, and `carry on`; mute includes `mute`, `silence`, and `put on mute`; unmute includes `unmute`, `un mute`, and `un-mute`. Session targets include `session`, `conversation`, `chat`, `call`, `talk`, `talk mode`, `voice mode`, `voice chat`, and `listening`, with `voice` also acting as a qualifier before a session target. For mute/unmute, audio targets include `speaker`, `sound`, `audio`, `voice`, `volume`, and `yourself`. `turn off` / `switch off` with an audio target means mute; `turn on` / `switch on` with an audio target means unmute; otherwise they mean stop / start.

Case and punctuation are ignored. Fillers such as `please`, `could you`, `can you`, `go ahead and`, `let’s`, `just`, `now`, `okay`, `thank you`, and articles or possessives are allowed. Common sound-alikes include `paws` → pause, `moot` → mute, and `sesh` / `sesion` / `cession` / `sessions` → session; action and target words of at least five letters also allow a one-letter difference. The phrase is limited to 12 words, must contain exactly one distinct action, and every word must be recognized; questions, negations, extra topics, or other unknown words stay ordinary speech.

A match whose state transition would do nothing is not consumed: Voice Wake forwards its trigger guess to the Gateway, and Talk treats the top guess as a normal turn. For example, **“Jarvis stop”** while idle, **“Jarvis continue”** when nothing is paused, and **“start the conversation”** during Talk are not local actions.

Voice Wake checks up to three recognizer guesses: the first guess containing a wake word triggers it, and every wake-word-containing guess is checked for a command before the trigger guess is forwarded. Native Talk checks every guess of a final result and uses the top guess as normal speech if none is actionable; realtime transcripts contain one guess. On Android 13+, Voice Wake also sends wake words and command phrases as best-effort recognizer biasing hints (up to 24 strings); Talk’s recognizer is not biased.

Actionable matches are handled on-device and do not create a separate chat message; no-op matches follow their normal speech path. In realtime Talk, the audio has already reached the provider, so the model may hear and respond to the phrase before the local action is applied. Commands without a wake word are recognized only in Talk mode: native Talk finals while the assistant is speaking are checked only when [`talk.interruptOnSpeech`](/nodes/talk) is `true`; realtime Talk user finals are checked regardless. Composer dictation and voice-note recording never treat speech as a session command. Voice Wake listens only in the foreground; leaving the foreground stops Talk capture, and voice start/resume is not queued while backgrounded.

## Related

- [Talk mode](/nodes/talk)
- [Audio and voice notes](/nodes/audio)
- [Media understanding](/nodes/media-understanding)
