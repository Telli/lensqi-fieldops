/** Own every stream, including one delivered after the user has left the camera. */
export function connectCamera(request: () => Promise<MediaStream>, attach: (stream: MediaStream) => void, onError: (error: unknown) => void) {
  let disposed = false;
  let stream: MediaStream | undefined;
  const stop = (s?: MediaStream) => s?.getTracks().forEach((track) => track.stop());
  request().then((s) => {
    if (disposed) { stop(s); return; }
    stream = s;
    attach(s);
  }).catch((error: unknown) => { if (!disposed) onError(error); });
  return () => { disposed = true; stop(stream); };
}

export function cameraError(error: unknown): string {
  const name = error instanceof Error ? error.name : "";
  if (name === "NotAllowedError" || name === "SecurityError") return "Camera access is blocked. Allow it in your browser's site settings, then try again.";
  if (name === "NotFoundError") return "No camera was found. Connect a camera or choose a photo.";
  if (name === "NotReadableError") return "The camera is busy. Close any other app using it, then try again.";
  return "The camera couldn't start. Try again or choose a photo.";
}

interface PhotoCapturer { takePhoto(): Promise<Blob> }
type CaptureCtor = new (track: MediaStreamTrack) => PhotoCapturer;

/** Whether this browser can take a still from a camera track at the sensor's resolution (Chrome on Android and desktop). */
export function hasStillCapture(win: unknown = typeof window !== "undefined" ? window : undefined): boolean {
  return !!win && typeof (win as { ImageCapture?: unknown }).ImageCapture === "function";
}

/** The preview frame as a JPEG: never larger than the video track, usually 1080p. */
function frameOf(video: HTMLVideoElement, quality: number): Promise<Blob | null> {
  const c = document.createElement("canvas");
  c.width = video.videoWidth; c.height = video.videoHeight;
  c.getContext("2d")!.drawImage(video, 0, 0);
  return new Promise((resolve) => c.toBlob(resolve, "image/jpeg", quality));
}

/**
 * The shutter photo. Where the browser can, a full-resolution still from the camera itself (`ImageCapture.takePhoto`),
 * which carries far more detail for the model and the crops than the preview; otherwise the preview frame. `still` says
 * which, so the screen can tell the technician what was saved.
 */
export async function takeStill(video: HTMLVideoElement, quality = 0.92): Promise<{ blob: Blob; still: boolean } | null> {
  if (!video.videoWidth) return null;
  const track = video.srcObject instanceof MediaStream ? video.srcObject.getVideoTracks().find((t) => t.readyState === "live") : undefined;
  if (track && hasStillCapture()) {
    try {
      const Ctor = (window as unknown as { ImageCapture: CaptureCtor }).ImageCapture;
      const blob = await new Ctor(track).takePhoto();
      if (blob.size > 0) return { blob, still: true };
    } catch {
      // Some cameras refuse a still while streaming; the frame below is the fallback.
    }
  }
  const frame = await frameOf(video, quality);
  return frame ? { blob: frame, still: false } : null;
}
