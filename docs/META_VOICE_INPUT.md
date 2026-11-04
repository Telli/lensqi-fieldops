# Meta speech input on web and Android

Web and Android dictation use `muse-voice-transcribe-1.0` through the configured LensQi server.
Browser speech synthesis and Android TextToSpeech are unchanged. Goals, questions and voice commands continue through
existing text handlers; a transcription alone cannot verify an inspection or submit a repair claim.

Clients capture mono PCM WAV, stop after a pause or 60 seconds, and POST multipart `audio` to `/v1/voice/transcribe`.
The shared server adapter calls Meta's `/v1/asr/transcribe` in PUSH_TO_TALK mode. This is bounded file transcription,
not realtime token streaming: the UI shows transcription progress after the utterance ends. A second microphone tap
during transcription cancels it. Silence detection is a simple energy threshold, not a learned VAD; noisy environments
and quiet speakers need physical-device validation.

The route reuses the existing WAV validation, server-side key, provider timeout and per-IP model-call rate limit.
No audio is persisted on the server. Web audio is held in memory; Android temporary WAV files are deleted after processing
or cancellation. Navigation/backgrounding invalidates callbacks and releases the microphone. Typing remains available;
there is no silent fallback to browser or Android speech recognition. Continuous hands-free sends each detected utterance
to Meta, including while listening for a resume command. The UI discloses the processing path before microphone use.

The existing inspection-specific voice-claim endpoint still enforces closed-inspection checks and explicit review.

## Verification

- Genuine call through the general route: HTTP 200, `muse-voice-transcribe-1.0`, using macOS-generated synthetic speech.
  Input: “Which cable should I check next?” Output: “which cables should I check next?” This is connectivity evidence,
  not an accuracy benchmark or physical microphone validation. [Digests and result](evidence/meta-voice-input/live-transcribe.json).
- Web page rendered with the Meta processing notice: [screenshot](evidence/meta-voice-input/web-notice.jpg).
- Automated checks cover WAV encoding, cancelled permission resolution, server forwarding, errors, limits, silence,
  utterance boundaries and privacy labels; existing speech-output tests remain in place.
- A physical phone microphone and browser microphone have not been exercised for this integration. No new Android
  runtime demonstration is claimed. The available emulator is headless and was not controlled through the desktop UI.

Meta's [file transcription reference](https://dev.meta.ai/docs/api-reference/voice/transcribe) was checked for this integration.
