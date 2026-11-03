import { tasks, type TaskStep } from "@/lib/tasks";

export function TaskSources({id, step}: {id: string; step: TaskStep}) {
  if (!step.sourceFacts?.length && !step.referencePhotoIds?.length) return null;
  return <details className="mt-2 text-sm"><summary>Supporting sources</summary>
    {step.sourceFacts?.map(f=><p key={f.factId}>{f.statement} · {f.citation.documentTitle}, {f.citation.sourceLocator} {f.citation.sourceUrl && /^https?:\/\//i.test(f.citation.sourceUrl) && <a href={f.citation.sourceUrl} target="_blank" rel="noreferrer">Open source</a>}</p>)}
    {step.referencePhotoIds?.map(photo=><a key={photo} href={tasks.photoUrl(id,photo)} target="_blank" rel="noreferrer"><img loading="lazy" className="max-h-60" src={tasks.photoUrl(id,photo)} alt={"Reference page " + photo}/></a>)}
  </details>;
}
