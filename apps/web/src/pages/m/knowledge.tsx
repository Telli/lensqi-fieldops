import { useState } from "react";
import { BookOpen, Loader2, Sparkles } from "lucide-react";
import { Button } from "@/components/ui/button";
import { TopBar, Page } from "@/components/fo/mobile-shell";
import { AiCard } from "@/components/fo/ai";
import { Citation, Empty, Expandable, TechDetails } from "@/components/fo/primitives";
import { api, commandScope, type Knowledge as KnowledgeData } from "@/lib/api";
import { advice, adviceDisclaimer, predictionNote } from "@/lib/advisor";
import { useAct, useInspection, useKnowledge } from "@/lib/hooks";
import { isClosed } from "@/lib/inspection";
import { excludedReason } from "@/lib/labels";

const MaxObservation = 2000;

/**
 * Vendor knowledge for the inspected asset, as the server resolved it: the facts that apply, the ones it did not apply
 * and why (VK-R03.4), where the pack came from, and a place to ask the grounded advisor about what you are seeing.
 */
export default function Knowledge({ id }: { id: string }) {
  const k = useKnowledge(id);
  const insp = useInspection(id);
  const facts = k.data?.facts ?? [];
  const excluded = k.data?.excluded ?? [];
  const warnings = k.data?.warnings ?? [];
  const synthetic = k.data?.originKind === "Synthetic";
  return (
    <>
      <TopBar title="Vendor knowledge" back={`/m/i/${id}`} />
      <Page>
        <p className="text-sm text-muted-foreground">Guidance for this equipment, with sources. Documentation helps you investigate; it never proves a repair worked.</p>
        {synthetic && (
          <span className="inline-flex items-center rounded border border-dashed border-warning/60 px-1.5 py-0.5 font-mono text-[10px] uppercase tracking-wider text-warning" data-origin="Synthetic">
            Synthetic · demo pack, not a vendor document
          </span>
        )}
        {warnings.map((w) => (
          <p key={w} className="rounded-xl border border-warning/40 bg-warning/10 px-3 py-2 text-xs text-warning" role="status">{w}</p>
        ))}
        {facts.length === 0 && !k.isLoading && <Empty icon={BookOpen} title="No documentation for this asset" />}
        {facts.map((f) => (
          <article key={f.factId} className="panel space-y-2.5 p-4">
            <div className="flex items-center justify-between gap-2">
              <span className="rounded-full border border-border px-2 py-0.5 text-[11px] text-muted-foreground">{f.provenance === "SiteConfigured" ? "Site setup" : synthetic ? "Demo documentation" : "Vendor documentation"}</span>
              {f.safety !== "ObserveOnly" && <span className="text-[11px] text-warning">{f.safety.replace(/([a-z])([A-Z])/g, "$1 $2")}</span>}
            </div>
            <p className="text-sm">{f.statement}</p>
            <Citation title={f.citation.documentTitle} section={f.citation.sourceLocator} href={f.citation.sourceUrl} />
          </article>
        ))}
        {excluded.length > 0 && (
          <Expandable title={`Not applied to this unit · ${excluded.length}`}>
            <ul className="space-y-2">
              {excluded.map((x) => (
                <li key={x.factId} className="flex flex-col gap-0.5">
                  <span className="font-mono text-[11px] text-foreground/80">{x.factId}</span>
                  <span className="text-xs">{excludedReason(x.reason)}</span>
                </li>
              ))}
            </ul>
            <p className="mt-3 text-[11px]">The server left these out because it could not confirm they apply to this exact unit. They are not advice for it.</p>
          </Expandable>
        )}
        {k.data && <Advisor id={id} knowledge={k.data} closed={isClosed(insp.data)} scope={commandScope(insp.data)} />}
        {k.data?.packId && (
          <TechDetails rows={[
            ["Knowledge pack", `${k.data.packId} rev ${k.data.packRevision}`], ["Pack digest", k.data.packSha256 ?? "—"],
            ["Origin", k.data.originKind ?? "—"], ["Status", k.data.status], ["Approved checks", (k.data.approvedDiagnostics ?? []).join(", ") || "—"],
          ]} />
        )}
      </Page>
    </>
  );
}

/**
 * "What are you seeing?" → POST /knowledge/reason. The server grounds the model in the pack, validates the proposal and
 * records the outcome before answering; this panel only shows what it recorded, labelled as a suggestion.
 */
function Advisor({ id, knowledge, closed, scope }: { id: string; knowledge: KnowledgeData; closed: boolean; scope: string }) {
  const [observation, setObservation] = useState("");
  const ask = useAct(id, (text: string) => api.reason(id, text, scope));
  const noPack = knowledge.status === "NoPack";
  const text = observation.trim();
  const disabled = ask.isPending || closed || noPack || text.length === 0 || text.length > MaxObservation;
  const shown = ask.data ? advice(ask.data, knowledge.facts) : null;
  return (
    <section className="panel space-y-3 p-4" data-advisor>
      <div className="eyebrow flex items-center gap-1.5"><Sparkles className="size-3.5 text-primary" /> Ask the grounded advisor</div>
      <p className="text-xs text-muted-foreground">
        {closed ? "This inspection is closed; the advisor only answers about open inspections."
          : noPack ? "There is no documentation bound to this asset, so there is nothing to ground an answer in."
          : "Describe what you see. LensQi answers only from the documentation above, and the server checks every suggestion before it is recorded."}
      </p>
      <textarea value={observation} onChange={(e) => setObservation(e.target.value.slice(0, MaxObservation))} maxLength={MaxObservation} rows={3}
        disabled={closed || noPack || ask.isPending} placeholder="What are you seeing?" aria-label="What are you seeing?"
        className="w-full rounded-2xl border border-input bg-background px-4 py-3 text-sm outline-none focus:border-primary disabled:opacity-60" />
      <div className="flex items-center justify-between gap-2">
        <span className="text-[11px] text-muted-foreground tabular-nums">{text.length}/{MaxObservation}</span>
        <Button className="h-10 rounded-xl" disabled={disabled} onClick={() => ask.mutate([text])}>
          {ask.isPending ? <Loader2 className="animate-spin" /> : <Sparkles />} {ask.isPending ? "Asking…" : "Ask LensQi"}
        </Button>
      </div>
      {shown && (
        shown.kind === "accepted" ? (
          <AiCard tone={undefined} says={shown.replayed ? "Answered from the record of an earlier identical question; the model was not called again." : "A suggestion, checked by the server against the documentation."}
            title={shown.hypothesis ?? shown.nextStep} hint={shown.hypothesis ? shown.nextStep : undefined}>
            <dl className="space-y-2 text-xs text-muted-foreground">
              {shown.basedOn.length > 0 && (
                <div>
                  <dt className="font-medium text-foreground">Based on</dt>
                  <dd><ul className="list-disc space-y-0.5 pl-4">{shown.basedOn.map((s) => <li key={s}>{s}</li>)}</ul></dd>
                </div>
              )}
              {shown.requestedView && <div><dt className="font-medium text-foreground">View it asks for</dt><dd>{shown.requestedView}</dd></div>}
              {shown.proposedCheck && <div><dt className="font-medium text-foreground">Proposed check</dt><dd className="font-mono">{shown.proposedCheck}</dd></div>}
              {shown.prediction && <div><dt className="font-medium text-foreground">Prediction · inferred</dt><dd>{shown.prediction} · {predictionNote}</dd></div>}
              <div><dt className="font-medium text-foreground">Next</dt><dd>{shown.nextStep}</dd></div>
            </dl>
            <p className="text-[11px] text-muted-foreground">{adviceDisclaimer}</p>
          </AiCard>
        ) : (
          <AiCard tone="failure" title="The server refused LensQi's suggestion and recorded why" says={shown.replayed ? "Answered from the record of an earlier identical question." : undefined}>
            <ul className="space-y-1 text-xs text-muted-foreground">
              {shown.refusals.map((x) => <li key={x.code + x.message}><span className="font-mono text-foreground/80">{x.code}</span> · {x.message}</li>)}
            </ul>
            <p className="text-[11px] text-muted-foreground">Nothing ran and nothing changed; the refusal is in the inspection's record.</p>
          </AiCard>
        )
      )}
    </section>
  );
}
