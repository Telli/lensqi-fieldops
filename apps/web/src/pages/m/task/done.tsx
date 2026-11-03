import { Link, useLocation } from "wouter";
import { toast } from "sonner";
import { CheckCircle2, Download, Loader2, MessageCircle, ShieldCheck, SkipForward } from "lucide-react";
import { useMutation } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { Page, TopBar } from "@/components/fo/mobile-shell";
import { AiCard } from "@/components/fo/ai";
import { friendlyError, useInspection } from "@/lib/hooks";
import { stageLabel } from "@/lib/labels";
import { tasks, useTask, verdictLabel } from "@/lib/tasks";
import { cn } from "@/lib/utils";

function saveBlob(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url; a.download = name; a.click();
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

/** What was done, what was skipped, what LensQi was asked. Technician jobs on a site record continue to FieldProof. */
export default function TaskDone({ id }: { id: string }) {
  const { data: t } = useTask(id);
  const [, nav] = useLocation();
  const linked = useInspection(t?.inspectionId ?? "", { enabled: !!t?.inspectionId });
  const link = useMutation({ mutationFn: () => tasks.linkInspection(id), onSuccess: (r) => nav(`/m/i/${r.inspectionId}/scan`), onError: (e) => toast.error(friendlyError(e)) });
  // Fetched, not linked: a refused package (a missing or altered photo, a damaged receipt) is reported, never saved as a broken .zip.
  const download = useMutation({ mutationFn: () => tasks.package(id), onSuccess: (blob) => saveBlob(blob, `${id}-work-package.zip`), onError: (e) => toast.error(friendlyError(e)) });
  const packageButton = (
    <Button variant="outline" className="rounded-2xl" disabled={download.isPending} onClick={() => download.mutate()}>
      {download.isPending ? <Loader2 className="animate-spin" /> : <Download />} Download work package
    </Button>
  );
  if (t && !t.plan) return <><TopBar title="Summary" back="/m" /><Page><h1>{t.title ?? t.goal}</h1>
    {t.titleByAi && <p className="text-xs text-muted-foreground">Topic named by AI from the conversation</p>}
    {packageButton}
    {t.answers.map(a=><p key={a.at}><b>{a.question}</b> {a.answer}</p>)}
    <p className="text-sm text-muted-foreground">{t.phase === "Abandoned" ? "This task was stopped before it had a plan." : "This task has no plan yet."}</p></Page></>;
  if (!t?.plan) return <><TopBar title="Summary" back="/m" /><Page><Loader2 className="mx-auto animate-spin" /></Page></>;
  const steps = t.plan.steps;
  const done = steps.filter((s) => s.status === "Done").length;
  const skipped = steps.filter((s) => s.status === "Skipped");
  return (
    <>
      <TopBar title="Summary" back="/m" />
      <Page className="space-y-4">
        {packageButton}
        <AiCard tone={skipped.length ? "warning" : "success"} says={t.plan.title}
          title={t.phase === "Completed" ? (skipped.length ? `Done, with ${skipped.length} step${skipped.length === 1 ? "" : "s"} skipped` : "All steps done") : t.phase === "Abandoned" ? "Stopped · record retained" : t.pendingCompletion ? "All steps marked done on this device · waiting for server confirmation" : "In progress"}
          hint={t.mode === "Diy" ? "LensQi guided this from an AI-written plan. Recheck anything safety-critical, such as leaks, levels and fastenings." : undefined}>
          {t.site && t.inspectionId && linked.data && (
            <p className={cn("mb-2 flex items-center gap-1.5 text-sm font-medium", linked.data.stage === "VerifiedComplete" ? "text-success" : linked.data.stage === "VerificationFailed" ? "text-failure" : "text-muted-foreground")}>
              <ShieldCheck className="size-4" />FieldProof: {linked.data.stage === "VerifiedComplete" && linked.data.outcomeKind === "VerifiedCompleteSimulated" ? "Verified complete · simulated check" : stageLabel[linked.data.stage]}
            </p>
          )}
          {t.site && (t.inspectionId
            ? <Button asChild size="lg" className="h-12 w-full rounded-2xl"><Link href={linked.data?.stage === "VerifiedComplete" ? `/m/i/${t.inspectionId}/receipt` : `/m/i/${t.inspectionId}`}><ShieldCheck /> {linked.data?.stage === "VerifiedComplete" ? "See the FieldProof receipt" : "Open the FieldProof inspection"}</Link></Button>
            : <Button size="lg" className="h-12 w-full rounded-2xl" disabled={link.isPending} onClick={() => link.mutate()}>{link.isPending ? <Loader2 className="animate-spin" /> : <ShieldCheck />} Verify with FieldProof</Button>)}
        </AiCard>
        <ol className="divide-y divide-border/70 overflow-hidden rounded-3xl border border-border bg-surface">
          {steps.map((s, n) => (
            <li key={s.id} className="flex items-start gap-3 p-3.5 text-sm">
              {s.status === "Done" ? <CheckCircle2 className="mt-0.5 size-5 shrink-0 text-success" /> : <SkipForward className="mt-0.5 size-5 shrink-0 text-muted-foreground" />}
              <div className="min-w-0">
                <p className={cn("font-medium", s.status !== "Done" && "text-muted-foreground")}>{n + 1}. {s.title}</p>
                {s.lastCheck && <p className={cn("text-xs", verdictLabel[s.lastCheck.verdict].tone)}>AI check: {verdictLabel[s.lastCheck.verdict].text} · {s.lastCheck.reason}</p>}
              </div>
            </li>
          ))}
        </ol>
        {t.answers.length > 0 && (
          <section className="panel space-y-2 p-3.5">
            <p className="flex items-center gap-1.5 text-sm font-medium"><MessageCircle className="size-4" /> Your questions</p>
            {t.answers.map((a, k) => <div key={k} className="text-xs"><p className="text-muted-foreground">{a.question}</p><p>{a.answer}</p></div>)}
          </section>
        )}
        <p className="text-center text-xs text-muted-foreground">{done} of {steps.length} steps done · {t.photos.filter((p) => p.purpose === "StepCheck").length} check photos kept</p>
      </Page>
    </>
  );
}
