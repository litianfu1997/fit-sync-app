import { createSSRApp } from "vue";
import App from "./App.vue";

// #ifdef H5
if (typeof window !== "undefined" && "serviceWorker" in navigator && !import.meta.env.DEV) {
  window.addEventListener("load", () => {
    void navigator.serviceWorker.register("/sw.js").catch(() => {});
  });
}
// #endif

export function createApp() {
  return { app: createSSRApp(App) };
}
