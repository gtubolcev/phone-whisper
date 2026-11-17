# Phone Whisper BYOK

Fork of a fork. Tremendous lineage, tremendous app, but now with freedom. Real freedom.

This build adds the thing cloud dictation always needed:

- **BYOK**: bring your own API key.
- **Any OpenAI-compatible HTTPS endpoint**: OpenAI, OpenRouter, self-hosted proxy, whatever speaks the protocol.
- **Cloud transcription can replace the local model completely**: choose provider, key, base URL, model.
- **Cleanup/postprocessing is separate**: its own provider, own key, own base URL, own chat model. No mixing. No confusion. Beautiful separation.
- **Model choice comes from your endpoint**: the app asks `/models` and shows what your provider actually has.

Old way: one hardcoded OpenAI-shaped path. Very limited. Sad.

New way: transcription provider here, cleanup provider there, keys stay yours, models come from the endpoint. Strong architecture. Many people are saying this.

## Voice keyboard

No accessibility service needed: enable **Phone Whisper voice** in Android keyboard settings (or from the app's Setup section) and switch to it in any text field. Bank apps that block accessibility services stay happy.

- **Mic** in the middle: tap to start, tap to stop. Text goes in through the normal keyboard channel, with smart spacing and capitalization.
- **Smart punctuation** `, . ? !`: replaces the mark the recognizer guessed ("Ты придёшь." + `?` → "Ты придёшь?"). Press two marks in a row to stack them (`?!`, `...`).
- **Fix a word**: the last phrase appears as tappable words above the mic. Tap one to select it, then:
  - **✨ Suggestions** — the cleanup LLM proposes what you probably said (uses the Post-Processing provider and key);
  - **🎤 Again** — re-dictate just that word;
  - **Aa** — flip capitalization; **Delete**; **⌨ Type** — jump to your regular keyboard with the word selected.
- **← →** move by word, **⌫ word** deletes a word, ⌫ and arrows repeat when held.
- Keyboard key: tap to return to your previous keyboard, hold for the list.

The keyboard and the floating button share one dictation engine, so the local model is loaded once.

## Install

Use the side-by-side debug build from [Releases](https://github.com/ivkiwi/phone-whisper/releases/tag/openai-compatible-providers-side-by-side-2026-06-29).

It installs next to upstream `0.4.1`:

- package id: `com.kafkasl.phonewhisper.openai`
- app label: `Phone Whisper OpenAI`

## Status

Built by GitHub Actions. APK ready:

[phone-whisper-openai-side-by-side-debug.apk](https://github.com/ivkiwi/phone-whisper/releases/download/openai-compatible-providers-side-by-side-2026-06-29/phone-whisper-openai-side-by-side-debug.apk)
