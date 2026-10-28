import { Link } from "wouter";
import { BookOpen, ChevronRight } from "lucide-react";
import { TopBar, Page } from "@/components/fo/mobile-shell";
import { EvidenceBadge, Section, Citation } from "@/components/fo/primitives";
import { ExpectedState } from "@/components/fo/expected-state";
import { useInspections, useKnowledge } from "@/lib/hooks";
import { useInspectionBundle } from "@/lib/inspection";
import { fmtWhen, stageLabel, stageState } from "@/lib/labels";
import { AssetHero } from "./asset-detail";

/** The asset behind one inspection: its latest outlined photo, how it should be installed, and its documentation. */
export default function Asset({ id }: { id: string }) {
  const { i, fixture } = useInspectionBundle(id);
  const knowledge = useKnowledge(id);
  const list = useInspections();
  const asset = fixture?.assets.find((a) => a.assetId === i?.assetId);
  const previous = list.data?.filter((s) => s.assetId === i?.assetId && s.inspectionId !== id);
  const rows: [string, string][] = [
    ["Identity", "From the asset label"],
    ["Site", fixture ? `${fixture.fixtureId} · revision ${fixture.fixtureRevision}` : "—"],
    ["Last inspection", previous?.[0] ? `${fmtWhen(previous[0].updatedAt)} · ${stageLabel[previous[0].stage].toLowerCase()}` : "None before this one"],
  ];
  return (
    <>
      <TopBar title={asset?.displayName ?? "Asset"} back={`/m/i/${id}`} />
      <Page className="space-y-4">
        <AssetHero inspectionId={id} />
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <div className="text-xl font-semibold tracking-tight">{asset?.displayName ?? "…"}</div>
            <div className="text-xs text-muted-foreground">{i?.assetId} · {asset?.kind.replace(/_/g, " ")}</div>
          </div>
          {i && <EvidenceBadge state={stageState[i.stage]} label={stageLabel[i.stage]} />}
        </div>
        <dl className="panel divide-y divide-border">
          {rows.map(([k, v]) => (
            <div key={k} className="flex justify-between gap-4 px-3.5 py-2.5 text-sm"><dt className="text-muted-foreground">{k}</dt><dd className="text-right">{v}</dd></div>
          ))}
        </dl>
        {i && <Section title="Expected installation state"><ExpectedState fixture={fixture} assetId={i.assetId} /></Section>}
        {knowledge.data && knowledge.data.facts.length > 0 && (
          <Section title="Documentation">
            {knowledge.data.facts.slice(0, 2).map((f) => <Citation key={f.factId} title={f.citation.documentTitle} section={f.citation.sourceLocator} href={f.citation.sourceUrl} />)}
            <Link href={`/m/i/${id}/knowledge`} className="panel flex items-center gap-3 p-3.5 text-sm hover:border-primary/40">
              <BookOpen className="size-4 text-primary" /><span className="flex-1">All vendor knowledge</span><ChevronRight className="size-4 text-muted-foreground" />
            </Link>
          </Section>
        )}
      </Page>
    </>
  );
}
