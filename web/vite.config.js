import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

// GitHub Pages ke liye repo path base (deploy-web.yml se override ho sakta hai)
export default defineConfig({
  plugins: [react(), tailwindcss()],
  base: process.env.VITE_BASE || "/healthwish/",
  build: { outDir: "dist", target: "es2019" },
});
