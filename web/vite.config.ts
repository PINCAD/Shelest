import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { readFileSync } from "node:fs";
const androidBuild = readFileSync(new URL("../app/build.gradle.kts", import.meta.url), "utf8");
const appVersion = androidBuild.match(/versionName\s*=\s*"([^"]+)"/)?.[1];
if (!appVersion) throw new Error("Android versionName is required for a consistent release");

export default defineConfig({
  plugins: [react()],
  define: { __APP_VERSION__: JSON.stringify(appVersion) },
  server: {
    proxy: {
      "/api": { target: "http://127.0.0.1:8000", changeOrigin: true },
    },
  },
  build: {
    outDir: "dist",
    emptyOutDir: true,
  },
});
