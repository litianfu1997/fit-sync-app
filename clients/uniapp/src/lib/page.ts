import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { initialize, messageOf, session } from "./api";
import { syncPageTheme } from "./theme";
export function useLoad(load: () => Promise<void>) {
  const loading = ref(false),
    error = ref("");
  async function reload() {
    if (loading.value) return;
    loading.value = true;
    error.value = "";
    try {
      await initialize();
      if (session.token && session.config) await load();
    } catch (reason) {
      error.value = messageOf(reason);
    } finally {
      loading.value = false;
    }
  }
  onShow(() => {
    syncPageTheme();
    void reload();
  });
  return { loading, error, reload };
}
