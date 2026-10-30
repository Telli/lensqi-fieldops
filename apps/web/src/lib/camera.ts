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
