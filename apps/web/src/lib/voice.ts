import { useCallback, useEffect, useRef, useState } from "react";

/** Hands-free commands. Anything else said to LensQi is a question. */
export type Command = "next" | "back" | "repeat" | "check" | "skip" | "mute" | "unmute" | "pause" | "resume";

const phrases: Record<Command, string[]> = {
  next: ["next", "next step", "done", "im done", "i am done", "finished", "step done", "continue", "go on"],
  back: ["back", "go back", "previous", "previous step", "last step"],
  repeat: ["repeat", "repeat that", "say that again", "again", "what was that", "read the step"],
  check: ["check", "check this", "check step", "check the step", "check my work", "take photo", "take a photo", "take picture", "take a picture"],
  skip: ["skip", "skip step", "skip this", "skip this step"],
  mute: ["mute", "quiet", "stop talking", "be quiet"],
  unmute: ["unmute", "speak", "talk to me", "voice on"],
  // Pause holds everything (speech and live coaching) until "resume"; mute only silences the voice.
  pause: ["pause", "hold on", "hang on", "wait", "pause coaching", "stop coaching"],
  resume: ["resume", "unpause", "resume coaching", "im back", "i am back"],
};
const lookup = new Map(Object.entries(phrases).flatMap(([c, ps]) => ps.map((p) => [p, c as Command])));

/** The whole utterance must be a command ("next step", "hey LensQi, check this"); a sentence with more in it is a question. */
export function parseCommand(text: string): Command | null {
  const t = text.toLowerCase().replace(/[’']/g, "").replace(/[^a-z ]/g, " ").replace(/\b(hey|ok|okay)?\s*lens\s?qi\b/g, " ").replace(/\bplease\b/g, " ").replace(/\s+/g, " ").trim();
  return lookup.get(t) ?? null;
}

/** Speaks text aloud and cancels whatever was being said. Off when the browser has no speech synthesis. */
export function useSpeak(initiallyOn = true) {
  const supported = typeof window !== "undefined" && "speechSynthesis" in window && typeof SpeechSynthesisUtterance !== "undefined";
  const [on, setOn] = useState(initiallyOn && supported);
  // Held while the person said "pause": nothing is spoken, without turning the voice off.
  const [held, setHeld] = useState(false);
  const last = useRef("");
  const speak = useCallback((text: string, force = false) => {
    if (!supported || !on || held || !text || (!force && text === last.current)) return;
    last.current = text;
    speechSynthesis.cancel();
    speechSynthesis.speak(new SpeechSynthesisUtterance(text));
  }, [on, held, supported]);
  const hold = useCallback((value: boolean) => { setHeld(value); if (value && supported) speechSynthesis.cancel(); }, [supported]);
  useEffect(() => () => { if (supported) speechSynthesis.cancel(); }, [supported]);
  const toggle = useCallback((value?: boolean) => {
    setOn((now) => { const next = value ?? !now; if (!next && supported) speechSynthesis.cancel(); return next; });
  }, [supported]);
  return { supported, on, toggle, speak, hold };
}

/** "Pause" holds speech and live coaching; "resume" brings back what was on before. */
export function usePause(coaching: { enabled: boolean; setEnabled: (on: boolean) => void }, speech: { hold: (on: boolean) => void }) {
  const [paused, setPaused] = useState(false);
  const wasCoaching = useRef(false);
  const pause = () => {
    if (paused) return;
    wasCoaching.current = coaching.enabled;
    coaching.setEnabled(false);
    speech.hold(true);
    setPaused(true);
  };
  const resume = () => {
    if (!paused) return;
    setPaused(false);
    speech.hold(false);
    if (wasCoaching.current) coaching.setEnabled(true);
  };
  return { paused, pause, resume };
}

interface RecognitionResult { readonly isFinal: boolean; readonly 0: { transcript: string } }
interface RecognitionEvent { readonly resultIndex: number; readonly results: ArrayLike<RecognitionResult> }
interface Recognition {
  lang: string; interimResults: boolean; continuous: boolean;
  onresult: ((e: RecognitionEvent) => void) | null; onend: (() => void) | null; onerror: ((e: { error: string }) => void) | null;
  start(): void; stop(): void; abort(): void;
}
type RecognitionCtor = new () => Recognition;

/** Shown before the first use of the microphone, then not again on this device. */
export const speechNotice = "Your browser may send microphone audio to its speech provider. Only recognised text reaches LensQi. You can type instead.";
const NOTICE_KEY = "lensqi.speechNoticeSeen";
const noticeSeen = () => { try { return localStorage.getItem(NOTICE_KEY) === "1"; } catch { return false; } };

/**
 * Push-to-talk speech recognition in the browser (Web Speech API). The browser may use a remote speech provider; only the final text is
 * used. Unsupported browsers report it so the page can offer typing instead.
 */
export function usePushToTalk(onFinal: (text: string) => void) {
  const Ctor = typeof window !== "undefined" ? ((window as unknown as { SpeechRecognition?: RecognitionCtor; webkitSpeechRecognition?: RecognitionCtor }).SpeechRecognition
    ?? (window as unknown as { webkitSpeechRecognition?: RecognitionCtor }).webkitSpeechRecognition) : undefined;
  const [listening, setListening] = useState(false);
  const [heard, setHeard] = useState("");
  const [error, setError] = useState<string | null>(null);
  const rec = useRef<Recognition | null>(null);
  const final = useRef("");
  const deliver = useRef(onFinal);
  deliver.current = onFinal;
  const [seen, setSeen] = useState(noticeSeen);

  const start = useCallback(() => {
    if (!Ctor || rec.current) return;
    try { localStorage.setItem(NOTICE_KEY, "1"); } catch { /* shown again next time */ }
    setSeen(true);
    const r = new Ctor();
    r.lang = navigator.language || "en-US"; r.interimResults = true; r.continuous = false;
    final.current = ""; setHeard(""); setError(null);
    r.onresult = (e) => {
      let text = "";
      for (let i = 0; i < e.results.length; i++) text += e.results[i]![0].transcript;
      setHeard(text);
      if (e.results[e.results.length - 1]?.isFinal) final.current = text;
    };
    r.onerror = (e) => setError(e.error === "not-allowed" ? "Microphone access is blocked for this site." : e.error === "no-speech" ? "I didn't hear anything." : "Voice input stopped.");
    r.onend = () => {
      const aborted = rec.current !== r;
      rec.current = null; setListening(false);
      const text = final.current.trim();
      // Nothing is delivered after the page let go of the recognizer (unmount aborts it).
      if (text && !aborted) deliver.current(text);
    };
    rec.current = r;
    setListening(true);
    r.start();
  }, [Ctor]);

  const stop = useCallback(() => rec.current?.stop(), []);
  useEffect(() => () => { const r = rec.current; rec.current = null; r?.abort(); }, []);
  return { supported: !!Ctor, listening, heard, error, start, stop, notice: !!Ctor && !seen };
}
