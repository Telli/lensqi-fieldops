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

/**
 * Speaks text aloud and cancels whatever was being said. Off when the browser has no speech synthesis. `speaking` is true
 * while an utterance plays, and `interrupt()` stops one so the voice never talks over the microphone.
 */
export function useSpeak(initiallyOn = true) {
  const supported = typeof window !== "undefined" && "speechSynthesis" in window && typeof SpeechSynthesisUtterance !== "undefined";
  const [on, setOn] = useState(initiallyOn && supported);
  // Held while the person said "pause": nothing is spoken, without turning the voice off.
  const [held, setHeld] = useState(false);
  // While the microphone is open nothing is spoken, so the voice is never heard as a command or a question. Kept in a ref
  // as well, because the recogniser reports the close synchronously, right before it delivers a command that may speak.
  const listeningRef = useRef(false);
  const [, setListeningState] = useState(false);
  const [speaking, setSpeaking] = useState(false);
  const last = useRef("");
  // The utterance in flight and its time limit; callbacks and timers from an earlier one are ignored.
  const current = useRef<{ utterance: SpeechSynthesisUtterance; limit: ReturnType<typeof setTimeout> } | null>(null);
  const finish = useCallback((u?: SpeechSynthesisUtterance) => {
    if (!current.current || (u && current.current.utterance !== u)) return;
    clearTimeout(current.current.limit);
    current.current = null;
    setSpeaking(false);
  }, []);
  const speak = useCallback((text: string, force = false) => {
    if (!supported || !on || held || listeningRef.current || !text || (!force && text === last.current)) return;
    last.current = text;
    finish();
    speechSynthesis.cancel();
    const u = new SpeechSynthesisUtterance(text);
    // Some browsers never fire onend for a long utterance; a generous time limit keeps hands-free from waiting forever.
    const limit = setTimeout(() => finish(u), 3000 + text.length * 90);
    current.current = { utterance: u, limit };
    u.onstart = () => { if (current.current?.utterance === u) setSpeaking(true); };
    u.onend = () => finish(u);
    u.onerror = () => finish(u);
    setSpeaking(true);
    speechSynthesis.speak(u);
  }, [on, held, supported, finish]);
  const interrupt = useCallback(() => { finish(); if (supported) speechSynthesis.cancel(); }, [supported, finish]);
  const hold = useCallback((value: boolean) => { setHeld(value); if (value) interrupt(); }, [interrupt]);
  /** Tells the voice the microphone is open: what is playing stops and nothing new is spoken until it closes. */
  const setListening = useCallback((value: boolean) => { listeningRef.current = value; setListeningState(value); if (value) interrupt(); }, [interrupt]);
  useEffect(() => () => { if (supported) speechSynthesis.cancel(); }, [supported]);
  const toggle = useCallback((value?: boolean) => {
    setOn((now) => { const next = value ?? !now; if (!next) interrupt(); return next; });
  }, [interrupt]);
  return { supported, on, toggle, speak, hold, speaking, interrupt, setListening };
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

const BLOCKED_MIC = "Microphone access is blocked for this site.";
const BLOCKED_SERVICE = "Speech recognition isn't allowed for this site.";
/** Errors that will not change by trying again, so hands-free stops asking. */
export const voiceBlocked = (error: string | null) => error === BLOCKED_MIC || error === BLOCKED_SERVICE;

/** Shown before the first use of the microphone, then not again on this device. */
export const speechNotice = "Your browser may send microphone audio to its speech provider. Only recognised text reaches LensQi. You can type instead.";
const NOTICE_KEY = "lensqi.speechNoticeSeen";
const noticeSeen = () => { try { return localStorage.getItem(NOTICE_KEY) === "1"; } catch { return false; } };

/**
 * Push-to-talk speech recognition in the browser (Web Speech API). The browser may use a remote speech provider; only the final text is
 * used. Unsupported browsers report it so the page can offer typing instead.
 */
export function usePushToTalk(onFinal: (text: string) => void, opts?: { /** Told synchronously when the microphone opens and closes, before any final text is delivered. */ onListening?: (on: boolean) => void }) {
  const Ctor = typeof window !== "undefined" ? ((window as unknown as { SpeechRecognition?: RecognitionCtor; webkitSpeechRecognition?: RecognitionCtor }).SpeechRecognition
    ?? (window as unknown as { webkitSpeechRecognition?: RecognitionCtor }).webkitSpeechRecognition) : undefined;
  const [listening, setListening] = useState(false);
  const [heard, setHeard] = useState("");
  const [error, setError] = useState<string | null>(null);
  const rec = useRef<Recognition | null>(null);
  const final = useRef("");
  const deliver = useRef(onFinal);
  deliver.current = onFinal;
  const tell = useRef(opts?.onListening);
  tell.current = opts?.onListening;
  const [seen, setSeen] = useState(noticeSeen);

  const start = useCallback(() => {
    if (!Ctor || rec.current) return;
    try { localStorage.setItem(NOTICE_KEY, "1"); } catch { /* shown again next time */ }
    setSeen(true);
    const r = new Ctor();
    r.lang = navigator.language || "en-US"; r.interimResults = true; r.continuous = false;
    final.current = ""; setHeard(""); setError(null);
    // Every callback is ignored once this recogniser is no longer the current one (aborted, or replaced by a newer one).
    r.onresult = (e) => {
      if (rec.current !== r) return;
      let text = "";
      for (let i = 0; i < e.results.length; i++) text += e.results[i]![0].transcript;
      setHeard(text);
      if (e.results[e.results.length - 1]?.isFinal) final.current = text;
    };
    r.onerror = (e) => {
      if (rec.current !== r) return;
      setError(e.error === "not-allowed" ? BLOCKED_MIC : e.error === "service-not-allowed" ? BLOCKED_SERVICE : e.error === "no-speech" ? "I didn't hear anything." : "Voice input stopped.");
    };
    r.onend = () => {
      // Nothing is delivered after the page let go of the recognizer (unmount or abort), and nothing of a newer one is touched.
      if (rec.current !== r) return;
      rec.current = null; setListening(false); tell.current?.(false);
      const text = final.current.trim();
      if (text) deliver.current(text);
    };
    rec.current = r;
    setListening(true); tell.current?.(true);
    // A recogniser that refuses to start never fires onend, so the button would stay on "listening" without this.
    try { r.start(); } catch { rec.current = null; setListening(false); tell.current?.(false); setError("Voice input couldn't start. Try again."); }
  }, [Ctor]);

  const stop = useCallback(() => rec.current?.stop(), []);
  /** Ends an open listen and delivers nothing from it, unlike `stop`, which still hands over the final text. */
  const abort = useCallback(() => { const r = rec.current; rec.current = null; r?.abort(); setListening(false); setHeard(""); tell.current?.(false); }, []);
  useEffect(() => () => { const r = rec.current; rec.current = null; r?.abort(); }, []);
  return { supported: !!Ctor, listening, heard, error, start, stop, abort, notice: !!Ctor && !seen };
}

/**
 * Hands-free: the microphone opens again by itself after each utterance, once the answer has been spoken and nothing is
 * pending, so the technician never touches the phone between questions. The recogniser tells the voice when the
 * microphone is open (`usePushToTalk`'s `onListening`), so nothing is spoken into it. Off again when the microphone is blocked.
 */
export function useHandsFree(voice: { supported: boolean; listening: boolean; error: string | null; start: () => void; abort: () => void },
  speech: { speaking: boolean }, busy: boolean) {
  const [on, setOn] = useState(false);
  const blocked = voiceBlocked(voice.error);
  // Turning hands-free on allows one attempt even after a denial, in case access was granted since; a second denial ends it.
  const retried = useRef(false);
  useEffect(() => {
    if (!on || !voice.supported) return;
    if (blocked && retried.current) { setOn(false); return; }
    if (voice.listening || speech.speaking || busy) return;
    // The retry is spent only when a start is really attempted, not while something else holds hands-free back.
    const t = setTimeout(() => { if (blocked) retried.current = true; voice.start(); }, 400);
    return () => clearTimeout(t);
  }, [on, voice.supported, voice.listening, speech.speaking, busy, blocked]); // eslint-disable-line react-hooks/exhaustive-deps
  const toggle = () => setOn((v) => {
    // Off ends a listen that is open right now, so nothing said after the tap is taken as a command.
    if (v && voice.listening) voice.abort();
    if (!v) retried.current = false;
    return !v;
  });
  return { on, toggle };
}
