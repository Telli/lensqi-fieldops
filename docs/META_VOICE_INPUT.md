# Meta speech input on web and Android

Web and Android dictation use `muse-voice-transcribe-1.0` through the configured LensQi server.
This covers input. For Android spoken output, see [its record](evidence/android/speech-output/README.md). Goals, questions and voice commands continue through
existing text handlers; a transcription alone cannot verify an inspection or submit a repair claim.

Clients capture mono PCM WAV, stop after a pause or 60 seconds, and POST multipart `audio` to `/v1/voice/transcribe`.
The shared server adapter calls Meta's `/v1/asr/transcribe` in PUSH_TO_TALK mode. This is bounded file transcription,
not realtime token streaming: the UI shows transcription progress after the utterance ends. A second microphone tap
during transcription cancels it. Silence detection is a simple energy threshold, not a learned VAD; noisy environments
and quiet speakers need physical-device validation.

The route reuses the existing WAV validation, server-side key and provider timeout. It has its own per-IP limit,
`VoiceInputCallsPerHourPerIp`, because hands-free sends every utterance and must not use up the budget for plans and questions.
No audio is persisted on the server. Web audio is held in memory; Android temporary WAV files are deleted after processing
or cancellation. Navigation/backgrounding invalidates callbacks and releases the microphone. Typing remains available;
there is no silent fallback to browser or Android speech recognition. Audio that holds no words is an ordinary answer,
`no_speech`, and leaves hands-free running. Continuous hands-free sends each detected utterance
to Meta, including while listening for a resume command. The UI discloses the processing path before microphone use.

The existing inspection-specific voice-claim endpoint still enforces closed-inspection checks and explicit review.

## Verification

- Genuine call through the general route: HTTP 200, `muse-voice-transcribe-1.0`, using macOS-generated synthetic speech.
  Input: “Which cable should I check next?” Output: “which cables should I check next?” This is connectivity evidence,
  not an accuracy benchmark or physical microphone validation. [Digests and result](evidence/meta-voice-input/live-transcribe.json).
- Web page rendered with the Meta processing notice: [screenshot](evidence/meta-voice-input/web-notice.jpg).
- Automated checks cover WAV encoding, cancelled permission resolution, server forwarding, errors, limits, silence,
  utterance boundaries and privacy labels; existing speech-output tests remain in place.
- The app's own clients, in [this run record](evidence/meta-voice-input/client-paths-20261104.json):
  - **Web.** The real recorder and upload code ran in Chrome, with a stream decoded from a WAV file as the microphone input.
    Synthetic speech filled the goal box with a genuine transcript. Noise bursts returned `no_speech` three times and
    hands-free stayed on.
  - **Android.** On the emulator, the notice appeared, the microphone opened and silence ended as "I didn't catch that."
    with no crash. The emulator's microphone provided no speech.
- A person's voice on a Galaxy S23 Ultra microphone, in [this run record](evidence/android/camera-layout/s23-run-20261104.json):
  spoken questions were answered, and Android repair claims worked end to end. Muse Voice misheard "reseated" on all three
  takes that used it, while simpler words came back nearly right. This is one run, not an accuracy benchmark.
- A person's voice on a real browser microphone and recognition quality on a noisy site have not been tested.

Meta's [file transcription reference](https://dev.meta.ai/docs/api-reference/voice/transcribe) was checked for this integration.
