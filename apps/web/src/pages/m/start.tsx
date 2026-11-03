import { Link, useLocation, useSearch } from "wouter";
import { useMutation } from "@tanstack/react-query";
import { toast } from "sonner";
import { Boxes, ChevronRight, Loader2, QrCode } from "lucide-react";
import { TopBar, Page } from "@/components/fo/mobile-shell";
import { Section } from "@/components/fo/primitives";
import { api } from "@/lib/api";
import { friendlyError, useFixtures } from "@/lib/hooks";
import { useEffect } from "react";

/** Choose what you are inspecting. The fixture comes from the asset label; the model never identifies serials. */
export default function Start() {
  const fixtures = useFixtures();
  const [, nav] = useLocation();
  const search = new URLSearchParams(useSearch());
  const preset = search.get("asset");
  const create = useMutation({
    mutationFn: ({ fixtureId, assetId }: { fixtureId: string; assetId: string }) => api.create(fixtureId, assetId),
    onSuccess: (i) => nav(`/m/i/${i.inspectionId}/scan`),
    onError: (e) => toast.error(friendlyError(e)),
  });
  useEffect(() => {
    if (!preset || !fixtures.data || create.isPending || create.isSuccess) return;
    const f = fixtures.data.find((x) => x.assets.some((a) => a.assetId === preset));
    if (f) create.mutate({ fixtureId: f.fixtureId, assetId: preset });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [preset, fixtures.data]);

  return (
    <>
      <TopBar title="Start an inspection" back="/m" />
      <Page>
        <p className="text-sm text-muted-foreground">Pick the equipment from its asset label. LensQi checks it against the site's expected setup.</p>
        <Link href="/m/scan-label" className="panel flex w-full items-center gap-3 p-3.5 text-left hover:border-primary/40">
          <span className="grid size-10 place-items-center rounded-xl bg-primary/15 text-primary"><QrCode className="size-5" /></span>
          <span className="flex-1"><span className="block text-sm font-medium">Scan the asset label</span><span className="block text-xs text-muted-foreground">QR code or barcode, matched to the site records on this device</span></span>
          <ChevronRight className="size-4 text-muted-foreground" />
        </Link>
        {fixtures.data?.map((f) => (
          <Section key={f.fixtureId} title={f.fixtureId}>
            <p className="text-xs text-muted-foreground">{f.fixtureDescription}</p>
            {f.assets.map((a) => (
              <button key={a.assetId} disabled={create.isPending} onClick={() => create.mutate({ fixtureId: f.fixtureId, assetId: a.assetId })}
                className="panel flex w-full items-center gap-3 p-3.5 text-left hover:border-primary/40 disabled:opacity-60">
                <span className="grid size-10 place-items-center rounded-xl bg-accent text-accent-foreground"><Boxes className="size-5" /></span>
                <span className="flex-1">
                  <span className="block text-sm font-medium">{a.displayName}</span>
                  <span className="block text-xs text-muted-foreground">{a.assetId} · {a.kind}</span>
                </span>
                {create.isPending && create.variables?.assetId === a.assetId ? <Loader2 className="size-4 animate-spin" /> : <ChevronRight className="size-4 text-muted-foreground" />}
              </button>
            ))}
          </Section>
        ))}
      </Page>
    </>
  );
}
