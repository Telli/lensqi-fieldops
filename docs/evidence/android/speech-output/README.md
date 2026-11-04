# Android spoken output through the server — 2026-11-04

## Emulator, with a stand-in provider

Labels: `EMULATOR`, `LIVE_META` for the answers. The speech provider was a local stand-in, so no ElevenLabs call was made in
this part, and nothing was checked by ear. The answers describe the emulator's virtual scene.

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

## Real provider on a physical phone

Labels: `LIVE_META`, `PHYSICAL_PHONE`. Galaxy S23 Ultra over USB, with the server's real ElevenLabs key and the
"Laurel - Warm, Kind & Confident" voice. A person ran it and reported what they heard. No recordings are committed.

| Check | Result |
|---|---|
| Three real calls through the server | HTTP 200, `audio/mpeg`, in 0.59, 0.31 and 0.30 seconds for a 100-character line |
| Just ask, spoken question | The person heard the answer read in the ElevenLabs voice, with the media volume up |
| Hands-free | The person reported it behaved: the microphone reopened after the answer. Reported by ear, not measured |
| Server with no ElevenLabs key | Route answered `speech_not_configured`. The person confirmed the phone's own voice read the answer, and the phone played no server audio |
| Crashes | None from LensQi in the phone's crash log |

Coaching hints first played while a question waited for its answer and sounded like the reply, so the person asked three
times. Hints now hold while a request is pending and for 20 seconds after an answer ends. In a second recording on the same
phone, one question got one answer, with no spoken line between them.

An earlier run seemed silent. The audio had gone to a Bluetooth earbud that wasn't being worn, and the speaker's media volume
was 0 once the earbud was removed. Spoken output of either kind follows the media volume.

**Not yet validated:** a provider failure or slow response on the phone (these were run on the emulator and in tests),
listening through a Bluetooth device, or use in a noisy place. Glasses guidance still uses the phone's TextToSpeech directly.
