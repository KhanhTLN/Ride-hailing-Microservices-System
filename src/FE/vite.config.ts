import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const ports = { user: 8082, driver: 8081, trip: 8080, pricing: 8083 };
  const proxy = Object.fromEntries(
    Object.entries(ports).map(([name, port]) => [
      `/backend/${name}`,
      {
        target:
          env[`${name.toUpperCase()}_API_URL`] || `http://127.0.0.1:${port}`,
        changeOrigin: true,
        rewrite: (path: string) => path.replace(`/backend/${name}`, ""),
      },
    ]),
  );
  return {
    plugins: [react()],
    server: { port: 5173, strictPort: true, proxy },
    preview: { port: 4173, proxy },
  };
});
