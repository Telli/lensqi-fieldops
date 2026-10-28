import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import { fileURLToPath } from "node:url";

// The build lands in the API's wwwroot, so the API serves the app with no Node at runtime.
// `npm run dev` proxies API calls to a locally running API (default http://127.0.0.1:5199).
const api = process.env.FIELDOPS_API ?? "http://127.0.0.1:5199";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: { alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) } },
  build: { outDir: "../../src/LensQi.FieldOps.Api/wwwroot", emptyOutDir: true, sourcemap: false },
  server: { port: 5173, proxy: { "/v1": api, "/health": api, "/api": api } },
});
