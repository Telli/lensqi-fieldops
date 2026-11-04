# Android spoken output through the server — 2026-11-04

Labels: `EMULATOR`, `LIVE_META` for the answers. The speech provider was a local stand-in, so **no ElevenLabs call was made,
and nothing was checked by ear.** The answers describe the emulator's virtual scene.

The app ran on the Android emulator (Medium Phone, API 36.1) against the API on this Mac. The API's `Speech__BaseUrl` pointed
at a small local server that answered with an MP3, and `Speech__ApiKey` held a placeholder.

| Check | Result |
|---|---|
| Typed question in Just ask | Genuine Muse Spark answer shown ([screenshot](01-answer-read-by-server-voice.png)) |
| Request the provider received | `POST /v1/text-to-speech/hGVeHdJEcfHv6D6RUSiY?output_format=mp3_44100_64`, `xi-api-key` from the server, model `eleven_flash_v2_5`, the answer's text |
| Playback | Android `MediaPlayer` opened with `USAGE_MEDIA`, `CONTENT_TYPE_SPEECH` and released afterwards. The temporary MP3 was gone |
| Provider stopped, second question | The server logged `Connection refused`, the phone's TextToSpeech engine started, and the answer was shown. No crash |
| Third question within a minute | No call to the server's speech route, as designed |

Automated tests cover the route, the provider request and the queue: timeouts, fallback, ordering, stale lines and stuck voices.

**Not yet validated:** a real ElevenLabs call, hearing either voice, a physical phone, hands-free timing against real audio, and
Bluetooth routing. Glasses guidance still uses the phone's TextToSpeech directly.
