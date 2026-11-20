import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { initialize, messageOf, session } from "./api";
import { endNavigation } from "./nav-progress";
import { revision } from "./query-cache";
import { syncPageTheme } from "./theme";

export interface UseLoadOptions {
  /** 数据新鲜期：在此期间切回页面不重新请求。 */
  ttl?: number;
  /** 配合 invalidate(scope) 实现操作后强制刷新。 */
  scope?: string;
}

export function useLoad(
  load: (force: boolean) => Promise<void>,
  options: UseLoadOptions = {},
) {
  const ttl = options.ttl ?? 45_000;
  const scope = options.scope ?? "";
  const busy = ref(false);
  const hasLoaded = ref(false);
  const error = ref("");
  let loadedAt = 0;
  let inflight: Promise<void> | null = null;
  let seenRevision = revision(scope);

  /** 仅首次/换账号后没有内容时替换页面，其余情况保留旧内容。 */
  const loading = computed(() => busy.value && !hasLoaded.value);
  const refreshing = computed(() => busy.value && hasLoaded.value);
  const fatalError = computed(() => (hasLoaded.value ? "" : error.value));
  const staleError = computed(() => (hasLoaded.value ? error.value : ""));

  function run(force: boolean): Promise<void> {
    if (inflight) return inflight;
    if (
      !force &&
      hasLoaded.value &&
      Date.now() - loadedAt < ttl &&
      seenRevision === revision(scope)
    ) {
      return Promise.resolve();
    }
    error.value = "";
    busy.value = true;
    inflight = (async () => {
      try {
        await initialize();
        if (session.token && session.config) {
          await load(force);
          hasLoaded.value = true;
          loadedAt = Date.now();
          seenRevision = revision(scope);
        }
      } catch (reason) {
        error.value = messageOf(reason);
      } finally {
        busy.value = false;
        inflight = null;
      }
    })();
    return inflight;
  }

  /** 兼容模板里直接绑定的 @click="reload"：事件对象不会误触发强制刷新。 */
  function reload(force: boolean | Event = false): Promise<void> {
    return run(force === true);
  }

  onShow(() => {
    endNavigation();
    syncPageTheme();
    void reload();
  });

  return { loading, refreshing, error, fatalError, staleError, reload };
}
