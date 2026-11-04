> General web and Android speech input also uses Meta Voice Transcribe, through `POST /v1/voice/transcribe`.
> The repair claim uses the inspection-specific path below, which adds an explicit transcript review.
> Browser and Android text-to-speech output is unchanged. See [speech input integration](META_VOICE_INPUT.md).

# Android repair claims by voice

The repair step, including a retry after failed verification, offers two ways to write the claim. Each one fills a draft
that the technician reviews before it becomes a claim.

| Method | Where the words come from | Sent off the phone |
|---|---|---|
| Typing | The technician | The confirmed text |
| **Record with Muse Voice** | `muse-voice-transcribe-1.0`, through the LensQi server | The recording, then the confirmed text |

## Recording with Muse Voice

1. Tap **Record with Muse Voice** and grant microphone access.
2. Speak, then tap **Stop and transcribe**. Recording stops by itself after 60 seconds.
3. Review the words in the **Repair claim transcript** field, which is labelled **Muse Voice (inferred)**. Edit them as needed.
4. Tap **Confirm transcript and submit claim**, or **Reject transcript** to discard them.

Confirming sends the words through the inspection's normal claim path, with its idempotency keys and error handling. The
words stay in the claim field, so a refused or failed submission can be retried from there. The voice code cannot record a
claim by itself.

The app records mono 16-bit PCM WAV at 24 kHz and deletes the temporary file after the upload. Leaving the screen,
backgrounding the app or scrolling the control away cancels recording or transcription and releases the microphone. A draft
under review survives that. Microphone denial, recording failures and transcription errors leave typing available.

Transcription needs a connection to the server; it is not queued offline.

## API

`POST /v1/inspections/{id}/voice/transcribe` takes multipart `audio`: a nonempty mono 16-bit PCM WAV at 16 or 24 kHz, at most
60 seconds. It returns `{ "transcript": "...", "modelId": "..." }`. It records no event, stores no audio and does not change
the inspection. It counts against the model-call rate limits and request timeout.

| Response | Meaning |
|---|---|
| `400 audio_invalid` | The upload is not a supported WAV |
| `404` | Unknown inspection |
| `409 invalid_state` | The inspection is closed, so no model call was made |
| `409 no_speech` | The provider heard no words; record again or type the claim |
| `409 provider_unavailable` | Transcription failed; provider error bodies are never returned or logged |
| `429 rate_limited`, `504 model_timeout` | The standard model-call limits |

The server calls Meta's `POST /v1/asr/transcribe` with multipart `request` and `audio`, and JSON settings `model`,
`audioEncoding=WAV` and `mode=PUSH_TO_TALK`. The key stays on the server through `MODEL_API_KEY`. The `Voice` configuration
section sets the model and provider timeout. See Meta's
[file transcription reference](https://dev.meta.ai/docs/api-reference/voice/transcribe) and
[speech-to-text guide](https://dev.meta.ai/docs/speech-to-text), checked on November 4, 2026.

## What has been verified

Dated November 4, 2026. See the [call record](evidence/android/voice-claim/live-transcribe-20261104.json).

- **Genuine provider calls.** Synthetic speech from macOS `say` was transcribed correctly apart from punctuation, which
  varied between calls. Silence returned an empty transcript, which the API reports as `no_speech`.
- **Android emulator.** Real `AudioRecord` capture passed the server's validation and reached the provider, which heard no
  speech from the emulator's microphone. To exercise review with genuine provider output, a local test proxy replaced the
  upload with the synthetic speech. Rejecting recorded no claim. Editing and confirming recorded one `RepairClaimed` event
  with the edited words. No temporary audio remained.
- **Screenshots.** [Claim options](evidence/android/voice-claim/01-claim-options.png) and
  [transcript review](evidence/android/voice-claim/02-transcript-review.png).

Not verified: a physical phone microphone, a person's voice, or recognition quality in a noisy site.
