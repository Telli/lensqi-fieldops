import { Link } from "wouter";
import { ScanLine, Boxes } from "lucide-react";
import { Button } from "@/components/ui/button";
import { TopBar, Page } from "@/components/fo/mobile-shell";
import { Empty, HealthDot, Section } from "@/components/fo/primitives";
import { ExpectedState } from "@/components/fo/expected-state";
import { LatestEvidence } from "@/pages/ops/more";
import { useFixtures, useInspections } from "@/lib/hooks";
import { InspectionCard } from "./home";

/** One asset outside any inspection: how the site says it should be installed, its latest photo, and its history. */
export default function AssetDetail({ assetId }: { assetId: string }) {
  const fixtures = useFixtures();
  const list = useInspections();
  const fixture = fixtures.data?.find((f) => f.assets.some((a) => a.assetId === assetId));
  const asset = fixture?.assets.find((a) => a.assetId === assetId);
  const mine = (list.data ?? []).filter((s) => s.assetId === assetId);
  const withPhoto = mine.find((s) => s.photos > 0);
  const h = !mine[0] ? "unknown" : mine[0].stage === "VerifiedComplete" ? "healthy" : ["FaultConfirmed", "VerificationFailed", "AwaitingRepair"].includes(mine[0].stage) ? "fault" : "attention";
  return (
    <>
      <TopBar title="Asset" back="/m/assets" right={<HealthDot h={h} />} />
      <Page>
        {!fixtures.isLoading && !asset ? <Empty icon={Boxes} title={`No asset ${assetId} in the site record`} /> : (
          <>
            <div>
              <div className="text-xs text-primary">{assetId}</div>
              <div className="mt-0.5 text-xl font-semibold">{asset?.displayName ?? "…"}</div>
              <div className="text-xs text-muted-foreground">{asset?.kind.replace(/_/g, " ")} · {fixture?.fixtureId}</div>
            </div>
            <Button asChild size="lg" className="h-12 w-full"><Link href={`/m/start?asset=${encodeURIComponent(assetId)}`}><ScanLine /> Start an inspection</Link></Button>
            <Section title="Expected installation state"><ExpectedState fixture={fixture} assetId={assetId} /></Section>
            {withPhoto && <Section title="Latest evidence"><LatestEvidence inspectionId={withPhoto.inspectionId} /></Section>}
            <Section title={`History · ${mine.length}`}>
              {mine.length === 0 && <p className="text-sm text-muted-foreground">Not inspected yet.</p>}
              {mine.slice(0, 10).map((s) => <InspectionCard key={s.inspectionId} s={s} assetName={asset?.displayName} />)}
            </Section>
          </>
        )}
      </Page>
    </>
  );
}
