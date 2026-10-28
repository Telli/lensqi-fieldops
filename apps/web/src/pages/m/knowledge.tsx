import { BookOpen } from "lucide-react";
import { TopBar, Page } from "@/components/fo/mobile-shell";
import { Citation, Empty, TechDetails } from "@/components/fo/primitives";
import { useKnowledge } from "@/lib/hooks";

export default function Knowledge({ id }: { id: string }) {
  const k = useKnowledge(id);
  const facts = k.data?.facts ?? [];
  return (
    <>
      <TopBar title="Vendor knowledge" back={`/m/i/${id}`} />
      <Page>
        <p className="text-sm text-muted-foreground">Guidance for this equipment, with sources. Documentation helps you investigate; it never proves a repair worked.</p>
        {facts.length === 0 && !k.isLoading && <Empty icon={BookOpen} title="No documentation for this asset" />}
        {facts.map((f) => (
          <article key={f.factId} className="panel space-y-2.5 p-4">
            <div className="flex items-center justify-between gap-2">
              <span className="rounded-full border border-border px-2 py-0.5 text-[11px] text-muted-foreground">{f.provenance === "SiteConfigured" ? "Site setup" : "Vendor documentation"}</span>
              {f.safety !== "ObserveOnly" && <span className="text-[11px] text-warning">{f.safety.replace(/([a-z])([A-Z])/g, "$1 $2")}</span>}
            </div>
            <p className="text-sm">{f.statement}</p>
            <Citation title={f.citation.documentTitle} section={f.citation.sourceLocator} href={f.citation.sourceUrl} />
          </article>
        ))}
        {k.data?.packId && <TechDetails rows={[["Knowledge pack", `${k.data.packId} rev ${k.data.packRevision}`], ["Pack digest", k.data.packSha256 ?? "—"], ["Status", k.data.status]]} />}
      </Page>
    </>
  );
}
