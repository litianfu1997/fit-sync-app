import { defineConfig, loadEnv } from "vite";
import uni from "@dcloudio/vite-plugin-uni";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const isH5 = (process.env.UNI_PLATFORM || "h5") === "h5";
  return {
    publicDir: isH5 ? "public" : undefined,
    plugins: [uni()],
    server: {
      port: 5273,
      proxy: {
        "/api": {
          target: env.VITE_DEV_PROXY_TARGET || "http://127.0.0.1:8080",
          changeOrigin: true,
          timeout: 120000,
          proxyTimeout: 120000,
        },
      },
    },
    build: { sourcemap: false },
  };
});
