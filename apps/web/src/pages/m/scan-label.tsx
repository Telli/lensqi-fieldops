import { useEffect, useRef, useState } from "react";
import { Link, useLocation } from "wouter";
import { useMutation } from "@tanstack/react-query";
import { toast } from "sonner";
import { Camera as CameraIcon, Loader2, QrCode, X } from "lucide-react";
import { api } from "@/lib/api";
import { assetFromScan, type ScannedAsset } from "@/lib/assets";
import { cameraError, connectCamera } from "@/lib/camera";
import { friendlyError, useFixtures } from "@/lib/hooks";
import { cn } from "@/lib/utils";

interface Detected { rawValue: string }
interface Detector { detect(source: HTMLVideoElement): Promise<Detected[]> }
type DetectorCtor = new (opts?: { formats?: string[] }) => Detector;

/** The browser's barcode reader (Chrome on Android and desktop); nothing leaves the device while reading a label. */
function barcodeDetector(): Detector | null {
  const Ctor = typeof window !== "undefined" ? (window as unknown as { BarcodeDetector?: DetectorCtor }).BarcodeDetector : undefined;
  if (!Ctor) return null;
  try { return new Ctor({ formats: ["qr_code", "code_128", "code_39", "data_matrix", "ean_13"] }); } catch { try { return new Ctor(); } catch { return null; } }
}

/**
 * Start an inspection from the asset label: the camera reads the QR code or barcode on the device and the id is matched
 * against the site records, so there is no list to scroll and no look-alike switch to mis-tap. A code that names no
 * recorded asset is shown as read and never becomes an inspection. Typing the id uses the same rule.
 */
export default function ScanLabel() {
  const fixtures = useFixtures();
  const [, nav] = useLocation();
  const video = useRef<HTMLVideoElement>(null);
  const [live, setLive] = useState<"starting" | "on" | "off">("starting");
  const [note, setNote] = useState<string | null>(null);
  const [read, setRead] = useState<string | null>(null);
  const [typed, setTyped] = useState("");
  const [supported] = useState(() => !!barcodeDetector());
  const create = useMutation({
    mutationFn: (a: ScannedAsset) => api.create(a.fixtureId, a.assetId),
    onSuccess: (i) => nav(`/m/i/${i.inspectionId}/scan`),
    // A failed create releases the lock, and automatic reading pauses so the same label in view is not sent again and
    // again; the technician asks for another read or types the id.
    onError: (e) => { pausedRef.current = true; started.current = false; setPaused(friendlyError(e) || "Try again."); },
  });
  const started = useRef(false);
  const [paused, setPaused] = useState<string | null>(null);
  // Set before the lock is released, so a read already in flight cannot start the same inspection again.
  const pausedRef = useRef(false);
  const start = (a: ScannedAsset) => { if (started.current) return; started.current = true; create.mutate(a); };

  useEffect(() => {
    if (!navigator.mediaDevices?.getUserMedia || !window.isSecureContext) { setLive("off"); setNote("The camera isn't available here. Type the asset id instead."); return; }
    const v = video.current;
    const dispose = connectCamera(
      () => navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: "environment" }, width: { ideal: 1280 }, height: { ideal: 720 } }, audio: false }),
      (s) => { if (v) { v.srcObject = s; setLive("on"); } },
      (e) => { setLive("off"); setNote(cameraError(e)); },
    );
    return () => { dispose(); if (v) v.srcObject = null; };
  }, []);

  // Read a few times a second while the camera is on; the first code that names a recorded asset starts the inspection.
  useEffect(() => {
    if (live !== "on") return;
    const detector = barcodeDetector();
    if (!detector) return;
    let busy = false, left = false;
    const t = setInterval(async () => {
      const v = video.current;
      if (busy || !v || !v.videoWidth || started.current || paused) return;
      busy = true;
      try {
        const codes = await detector.detect(v);
        // A read that finishes after the page was left, after a failed create, or while another is starting must not start an inspection.
        if (left || pausedRef.current || started.current) return;
        for (const code of codes) {
          const value = code.rawValue?.trim();
          if (!value) continue;
          setRead(value);
          const asset = assetFromScan(value, fixtures.data);
          if (asset) { start(asset); break; }
        }
      } catch { /* a frame the detector could not read; the next one is tried */ } finally { busy = false; }
    }, 350);
    return () => { left = true; clearInterval(t); };
  }, [live, fixtures.data, paused]); // eslint-disable-line react-hooks/exhaustive-deps

  const typedAsset = assetFromScan(typed, fixtures.data);
  const readAsset = read ? assetFromScan(read, fixtures.data) : undefined;

  return (
    <div className="dark relative flex h-dvh flex-col overflow-hidden bg-overlay text-overlay-foreground md:h-full">
      <div className="relative min-h-0 flex-1 bg-black">
        <video ref={video} autoPlay playsInline muted className={cn("absolute inset-0 size-full object-contain", live !== "on" && "hidden")} />
        {live === "on" && (
          <div className="pointer-events-none absolute left-1/2 top-1/2 size-56 -translate-x-1/2 -translate-y-1/2 rounded-2xl border-2 border-dashed border-overlay-foreground/60" aria-hidden />
        )}
        {live !== "on" && <p role="status" className="absolute inset-0 grid place-items-center px-8 text-center text-sm opacity-70">{live === "starting" ? <span className="flex items-center gap-2"><Loader2 className="size-4 animate-spin" /> Starting camera…</span> : note}</p>}
        <div className="absolute inset-x-0 top-0 flex items-center gap-2 p-3">
          <Link href="/m/start" aria-label="Close" className="glass grid size-11 place-items-center rounded-full"><X className="size-5" /></Link>
          <span className="glass flex flex-1 items-center gap-2 truncate rounded-full px-3 py-2 text-xs"><QrCode className="size-4 text-primary" /> Point at the asset label</span>
        </div>
      </div>

      <div className="space-y-3 rounded-t-3xl border-t border-overlay-foreground/10 bg-overlay px-4 pb-[max(1rem,env(safe-area-inset-bottom))] pt-3 text-sm">
        {!supported && live === "on" && <p className="text-xs text-warning">This browser can't read codes from the camera. Type the id from the label below.</p>}
        {create.isPending ? (
          <p role="status" className="flex items-center gap-2"><Loader2 className="size-4 animate-spin" /> Starting the inspection…</p>
        ) : paused ? (
          <div role="alert" className="space-y-2 text-xs">
            <p>The inspection couldn't be started. {paused}</p>
            <button type="button" onClick={() => { pausedRef.current = false; setPaused(null); }} className="min-h-10 rounded-xl border border-overlay-foreground/30 px-3">Read the label again</button>
          </div>
        ) : read ? (
          <p role="status" className="text-xs">
            Code read: <span className="font-mono">{read}</span>
            {readAsset ? <span className="text-success"> · {readAsset.displayName}</span> : <span className="text-warning"> · not an asset on a site record</span>}
          </p>
        ) : (
          <p className="text-xs opacity-70">The code is read on this device and matched against the site records. Only a recorded asset starts an inspection.</p>
        )}
        <form onSubmit={(e) => { e.preventDefault(); if (typedAsset) start(typedAsset); }} className="flex items-center gap-2">
          <input value={typed} onChange={(e) => setTyped(e.target.value)} placeholder="Or type the asset id, e.g. LAB-SW-01" aria-label="Asset id"
            className="h-12 min-w-0 flex-1 rounded-2xl border border-overlay-foreground/20 bg-transparent px-3 text-sm outline-none focus:border-primary" />
          <button type="submit" disabled={!typedAsset || create.isPending} className="h-12 rounded-2xl bg-primary px-4 text-sm font-medium text-primary-foreground disabled:opacity-40">
            {typedAsset ? `Start · ${typedAsset.displayName}` : "Start"}
          </button>
        </form>
        <Link href="/m/start" className="flex min-h-11 items-center justify-center gap-2 text-xs opacity-70"><CameraIcon className="size-4" /> Choose from the list instead</Link>
      </div>
    </div>
  );
}
