import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { Route, Switch, Redirect, useSearch } from "wouter";
import { Toaster } from "@/components/ui/sonner";
import { MobileFrame } from "@/components/fo/mobile-shell";
import { OpsShell } from "@/components/fo/ops-shell";
import Home from "@/pages/m/home";
import ModeHome from "@/pages/m/task/home";
import NewTask from "@/pages/m/task/new";
import TaskPlanPage from "@/pages/m/task/plan";
import TaskStepPage from "@/pages/m/task/step";
import TaskDone from "@/pages/m/task/done";
import Start from "@/pages/m/start";
import Scan from "@/pages/m/scan";
import GlassesView from "@/pages/m/glasses";
import { initTheme } from "@/lib/theme";
import MobileAssetDetail from "@/pages/m/asset-detail";
import Investigate from "@/pages/m/investigate";
import Asset from "@/pages/m/asset";
import Knowledge from "@/pages/m/knowledge";
import Diagnostics from "@/pages/m/diagnostics";
import Verify from "@/pages/m/verify";
import ReceiptPage from "@/pages/m/receipt";
import { Inspections, Assets, Profile } from "@/pages/m/lists";
import Overview from "@/pages/ops/overview";
import { TasksList } from "@/pages/ops/tasks";
import { InspectionsList, InspectionWorkspace } from "@/pages/ops/inspections";
import { AssetRegistry, AssetDetail, WorkOrders, KnowledgePacks, Reports } from "@/pages/ops/more";
import "./styles.css";

const queryClient = new QueryClient({ defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: true } } });

/** Old links (`/?inspection=I-…`) still open the inspection. */
function Root() {
  const id = new URLSearchParams(useSearch()).get("inspection");
  if (id) return <Redirect to={`/m/i/${id}`} replace />;
  return <Redirect to={typeof window !== "undefined" && window.innerWidth >= 1024 ? "/ops" : "/m"} replace />;
}

/** ?embed=1: shown inside the Android app, which draws its own navigation. */
const embedded = () => new URLSearchParams(location.search).has("embed");
const m = (node: React.ReactNode, immersive = false) => <MobileFrame immersive={immersive}>{node}</MobileFrame>;
const ops = (node: React.ReactNode) => <OpsShell>{node}</OpsShell>;

initTheme();

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <Switch>
        <Route path="/">{() => <Root />}</Route>
        <Route path="/m">{() => m(<ModeHome />)}</Route>
        <Route path="/m/tech">{() => m(<Home />)}</Route>
        <Route path="/m/new">{() => m(<NewTask />)}</Route>
        <Route path="/m/t/:id/step/:stepId">{(p) => m(<TaskStepPage key={p.id} id={p.id} stepId={p.stepId} />, true)}</Route>
        <Route path="/m/t/:id/step">{(p) => m(<TaskStepPage key={p.id} id={p.id} />, true)}</Route>
        <Route path="/m/t/:id/done">{(p) => m(<TaskDone id={p.id} />)}</Route>
        <Route path="/m/t/:id">{(p) => m(<TaskPlanPage id={p.id} />)}</Route>
        <Route path="/m/start">{() => m(<Start />)}</Route>
        <Route path="/m/inspections">{() => m(<Inspections />)}</Route>
        <Route path="/m/assets">{() => m(<Assets />)}</Route>
        <Route path="/m/assets/:assetId">{(p) => m(<MobileAssetDetail assetId={p.assetId} />)}</Route>
        <Route path="/m/profile">{() => m(<Profile />)}</Route>
        <Route path="/m/i/:id/scan">{(p) => m(<Scan key={p.id} id={p.id} />, true)}</Route>
        <Route path="/m/i/:id/glasses">{(p) => m(<GlassesView key={p.id} id={p.id} />, true)}</Route>
        <Route path="/m/i/:id/asset">{(p) => m(<Asset id={p.id} />)}</Route>
        <Route path="/m/i/:id/knowledge">{(p) => m(<Knowledge id={p.id} />)}</Route>
        <Route path="/m/i/:id/diagnostics">{(p) => m(<Diagnostics id={p.id} />)}</Route>
        <Route path="/m/i/:id/verify">{(p) => m(<Verify id={p.id} />)}</Route>
        <Route path="/m/i/:id/receipt">{(p) => m(<ReceiptPage id={p.id} />, embedded())}</Route>
        <Route path="/m/i/:id">{(p) => m(<Investigate id={p.id} />)}</Route>
        <Route path="/ops">{() => ops(<Overview />)}</Route>
        <Route path="/ops/tasks">{() => ops(<TasksList />)}</Route>
        <Route path="/ops/inspections">{() => ops(<InspectionsList />)}</Route>
        <Route path="/ops/inspections/:id">{(p) => ops(<InspectionWorkspace id={p.id} />)}</Route>
        <Route path="/ops/assets">{() => ops(<AssetRegistry />)}</Route>
        <Route path="/ops/assets/:assetId">{(p) => ops(<AssetDetail assetId={p.assetId} />)}</Route>
        <Route path="/ops/work-orders">{() => ops(<WorkOrders />)}</Route>
        <Route path="/ops/knowledge">{() => ops(<KnowledgePacks />)}</Route>
        <Route path="/ops/reports">{() => ops(<Reports />)}</Route>
        <Route>{() => <Redirect to="/m" replace />}</Route>
      </Switch>
      <Toaster theme="dark" position="top-center" richColors />
    </QueryClientProvider>
  </StrictMode>,
);
