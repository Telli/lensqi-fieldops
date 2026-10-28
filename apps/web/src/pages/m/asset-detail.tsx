import { Link } from "wouter";
import { ScanLine, Boxes, ArrowRight, Camera } from "lucide-react";
import { Button } from "@/components/ui/button";
import { TopBar, Page } from "@/components/fo/mobile-shell";
import { EvidenceBadge, Empty, Section } from "@/components/fo/primitives";
import { ArFrame } from "@/components/fo/ar-frame";
import { ExpectedState } from "@/components/fo/expected-state";
import { arTags, usePhotoOutlines } from "@/lib/ar";
import { useFixtures, useInspections } from "@/lib/hooks";
import { lastPhoto, useInspectionBundle } from "@/lib/inspection";
import { fmtWhen, stageLabel, stageState } from "@/lib/labels";
import { InspectionCard } from "./home";

const isOpen = (stage: string) => stage !== "VerifiedComplete" && stage !== "Inconclusive";

/** One asset outside any inspection: its latest photo with the AI's outlines, how the site says it should be installed, and its history. */
export default function AssetDetail({ assetId }: { assetId: string }) {
  const fixtures = useFixtures();
  const list = useInspections();
  const fixture = fixtures.data?.find((f) => f.assets.some((a) => a.assetId === assetId));
  const asset = fixture?.assets.find((a) => a.assetId === assetId);
  const mine = (list.data ?? []).filter((s) => s.assetId === assetId);
  const withPhoto = mine.find((s) => s.photos > 0);
  const open = mine.find((s) => isOpen(s.stage));
  return (
    <>
      <TopBar title={asset?.displayName ?? "Asset"} back="/m/assets" />
      <Page className="space-y-4">
        {!fixtures.isLoading && !asset ? <Empty icon={Boxes} title={`No asset ${assetId} in the site record`} /> : (
          <>
            {withPhoto ? <AssetHero inspectionId={withPhoto.inspectionId} /> : (
              <Link href={`/m/start?asset=${encodeURIComponent(assetId)}`} className="grid aspect-[4/3] place-items-center rounded-[26px] bg-overlay text-sm text-overlay-foreground/70">
                <span className="flex flex-col items-center gap-2"><Camera className="size-9" />No photos yet · scan it</span>
              </Link>
            )}
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <div className="text-xl font-semibold tracking-tight">{asset?.displayName ?? "…"}</div>
                <div className="text-xs text-muted-foreground">{assetId} · {asset?.kind.replace(/_/g, " ")} · {fixture?.fixtureId}</div>
              </div>
              {mine[0] && <EvidenceBadge state={stageState[mine[0].stage]} label={stageLabel[mine[0].stage]} />}
            </div>
            {open ? (
              <Button asChild size="lg" className="h-12 w-full rounded-2xl"><Link href={`/m/i/${open.inspectionId}`}>Continue the open inspection <ArrowRight /></Link></Button>
            ) : (
              <Button asChild size="lg" className="h-12 w-full rounded-2xl"><Link href={`/m/start?asset=${encodeURIComponent(assetId)}`}><ScanLine /> Start an inspection</Link></Button>
            )}
            <Section title="Expected installation state"><ExpectedState fixture={fixture} assetId={assetId} /></Section>
            <Section title={`History · ${mine.length}`} action={mine.length > 5 ? <Link href="/m/inspections" className="text-xs text-primary">All</Link> : undefined}>
              {mine.length === 0 && <p className="text-sm text-muted-foreground">Not inspected yet.</p>}
              {mine.slice(0, 5).map((s) => <InspectionCard key={s.inspectionId} s={s} assetName={asset?.displayName} />)}
            </Section>
          </>
        )}
      </Page>
    </>
  );
}

/** The asset's latest photo with the AI's outlines, uncropped so every outline stays on its part. */
export function AssetHero({ inspectionId }: { inspectionId: string }) {
  const { i, fixture, assessment, photoUrl } = useInspectionBundle(inspectionId);
  const photo = lastPhoto(i);
  const outlines = usePhotoOutlines(inspectionId, i, photo?.evidenceId);
  const change = i?.changeAnalyses.find((c) => c.photoEvidenceId === photo?.evidenceId);
  const src = photo && photoUrl(photo.evidenceId);
  return (
    <div className="relative overflow-hidden rounded-[26px] bg-overlay shadow-lg shadow-black/15">
      {src ? <ArFrame src={src} outlines={outlines} tags={arTags(fixture, i?.currentClaimId ? undefined : assessment, outlines, change)} /> : <div className="aspect-[4/3]" />}
      {photo && <span className="glass pointer-events-none absolute left-3 top-3 rounded-full px-2.5 py-1 text-[11px]">Latest photo · {fmtWhen(photo.observedAt)}</span>}
    </div>
  );
}
