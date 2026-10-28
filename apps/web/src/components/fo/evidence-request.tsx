import { Link } from "wouter";
import { Camera } from "lucide-react";
import type { EvidenceRequest } from "@/lib/api";
import { fmtWhen } from "@/lib/labels";

/** An open request from operations: what they want to see, and the way to answer it (a photo). */
export function EvidenceRequestBanner({ id, requests }: { id: string; requests?: EvidenceRequest[] }) {
  const open = requests?.filter((r) => !r.answeredByPhotoId) ?? [];
  if (open.length === 0) return null;
  return (
    <div className="animate-rise space-y-2 rounded-xl border border-warning/50 bg-warning/10 p-3.5">
      {open.map((r) => (
        <div key={r.requestId}>
          <div className="text-[11px] font-medium uppercase tracking-wide text-warning">Evidence requested · {r.requestedBy} · {fmtWhen(r.requestedAt)}</div>
          <div className="mt-0.5 text-sm">{r.note}</div>
        </div>
      ))}
      <Link href={`/m/i/${id}/scan`} className="flex h-10 items-center justify-center gap-2 rounded-lg bg-warning text-sm font-medium text-background"><Camera className="size-4" /> Take the photo</Link>
      <p className="text-[11px] text-muted-foreground">Your next photo answers it. A request never changes the verification result.</p>
    </div>
  );
}
