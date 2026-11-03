import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

// The build lands in the API's wwwroot, so the API serves the app with no Node at runtime.
// `npm run dev` proxies API calls to a locally running API (default http://127.0.0.1:5199).
const api = process.env.FIELDOPS_API ?? "http://127.0.0.1:5199";

export default defineConfig({
  plugins: [react(), tailwindcss(), {
    name: "job-offline-shell",
    enforce: "post",
    generateBundle(_, bundle) {
      const assets = Object.keys(bundle).filter(k => k.startsWith("assets/")).map(k => "/" + k);
      const worker = readFileSync(new URL("./src/offline/jobs-worker.js", import.meta.url), "utf8");
      this.emitFile({type: "asset", fileName: "jobs-worker.js", source: "const assets = " + JSON.stringify(assets) + ";\n" + worker});
    },
  }],
  resolve: { alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) } },
  build: { outDir: "../../src/LensQi.FieldOps.Api/wwwroot", emptyOutDir: true, sourcemap: false },
  server: { port: 5173, proxy: { "/v1": api, "/health": api, "/api": api } },
});
