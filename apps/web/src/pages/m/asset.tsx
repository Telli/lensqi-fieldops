import { Link } from "wouter";
import { GitCompare, BookOpen } from "lucide-react";
import { Button } from "@/components/ui/button";
import { TopBar, Page } from "@/components/fo/mobile-shell";
import { EvidenceBadge, Section, Citation } from "@/components/fo/primitives";
import { EvidencePhoto } from "@/components/fo/evidence-photo";
import { ExpectedState } from "@/components/fo/expected-state";
import { useInspections, useKnowledge } from "@/lib/hooks";
import { firstPhoto, useGeometry, useInspectionBundle } from "@/lib/inspection";
import { fmtWhen, stageLabel, stageState } from "@/lib/labels";

export default function Asset({ id }: { id: string }) {
  const { i, fixture, photoUrl } = useInspectionBundle(id);
  const knowledge = useKnowledge(id);
  const list = useInspections();
  const photo = firstPhoto(i);
  const geometry = useGeometry(id, i, photo?.evidenceId);
  const asset = fixture?.assets.find((a) => a.assetId === i?.assetId);
  const previous = list.data?.filter((s) => s.assetId === i?.assetId && s.inspectionId !== id);
  const rows: [string, string][] = [
    ["Device", asset?.kind.replace(/_/g, " ") ?? "—"],
    ["Site", fixture ? `${fixture.fixtureId} · revision ${fixture.fixtureRevision}` : "—"],
    ["Last inspection", previous?.[0] ? `${fmtWhen(previous[0].updatedAt)} · ${stageLabel[previous[0].stage].toLowerCase()}` : "None before this one"],
  ];
  return (
    <>
      <TopBar title="Asset" back={`/m/i/${id}`} />
      <Page>
        <div className="panel overflow-hidden">
          {photo && photoUrl(photo.evidenceId) && <EvidencePhoto src={photoUrl(photo.evidenceId)!} geometry={geometry.data} imgClassName="h-44 object-cover" />}
          <div className="p-4">
            <div className="text-xs text-primary">{i?.assetId}</div>
            <div className="mt-1 text-lg font-semibold">{asset?.displayName ?? "…"}</div>
            <div className="mt-3 grid grid-cols-2 gap-2">
              <div className="rounded-lg border border-border p-2.5">
                <div className="eyebrow">Identity</div>
                <div className="mt-1.5"><EvidenceBadge state="human-confirmed" label="From asset label" /></div>
              </div>
              <div className="rounded-lg border border-border p-2.5">
                <div className="eyebrow">Condition</div>
                <div className="mt-1.5">{i && <EvidenceBadge state={stageState[i.stage]} label={stageLabel[i.stage]} />}</div>
              </div>
            </div>
          </div>
        </div>
        <dl className="panel divide-y divide-border">
          {rows.map(([k, v]) => (
            <div key={k} className="flex justify-between gap-4 px-3.5 py-2.5 text-sm"><dt className="text-muted-foreground">{k}</dt><dd className="text-right">{v}</dd></div>
          ))}
        </dl>
        {i && <Section title="Expected installation state"><ExpectedState fixture={fixture} assetId={i.assetId} /></Section>}
        {knowledge.data && knowledge.data.facts.length > 0 && (
          <Section title="Documentation" action={<Link href={`/m/i/${id}/knowledge`} className="text-xs text-primary">All</Link>}>
            {knowledge.data.facts.slice(0, 2).map((f) => <Citation key={f.factId} title={f.citation.documentTitle} section={f.citation.sourceLocator} href={f.citation.sourceUrl} />)}
          </Section>
        )}
        <div className="space-y-2">
          <Button asChild size="lg" className="h-12 w-full"><Link href={`/m/i/${id}`}><GitCompare /> Compare with expected state</Link></Button>
          <Button asChild variant="outline" className="h-11 w-full"><Link href={`/m/i/${id}/knowledge`}><BookOpen /> Vendor knowledge</Link></Button>
        </div>
      </Page>
    </>
  );
}
