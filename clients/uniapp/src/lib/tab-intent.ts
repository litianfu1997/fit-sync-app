import { onUnmounted } from "vue";
import { onShow } from "@dcloudio/uni-app";

/** 藏在页面栈里的 tab 页需要的一次性参数：页面被重新激活时消费。 */
export type TabIntent =
  | { kind: "connect"; platform?: string }
  | { kind: "activityIds"; ids: string[] }
  | { kind: "refresh" };

const EVENT = "fitsync:tab-intent";
const INTENT_TTL_MS = 30_000;
const pending = new Map<string, { intent: TabIntent; at: number }>();

export function routeOf(page: unknown): string {
  const value = page as { route?: string; $page?: { path?: string } } | null;
  return String(value?.route || value?.$page?.path || "").replace(/^\//, "");
}

export function setTabIntent(page: string, intent: TabIntent) {
  pending.set(page, { intent, at: Date.now() });
}

export function takeTabIntent(page: string): TabIntent | undefined {
  const hit = pending.get(page);
  pending.delete(page);
  return hit && Date.now() - hit.at < INTENT_TTL_MS ? hit.intent : undefined;
}

/** 目标 tab 已经在栈顶时，不需要导航，直接唤醒该页消费意图。 */
export function notifyIfActive(page: string) {
  const stack = getCurrentPages();
  if (routeOf(stack[stack.length - 1]) !== `pages/${page}/index`) return;
  uni.$emit(EVENT, { page });
}

export function useTabIntent(
  page: string,
  handler: (intent: TabIntent) => void,
) {
  const consume = (event?: { page?: string }) => {
    if (event?.page && event.page !== page) return;
    const intent = takeTabIntent(page);
    if (intent) handler(intent);
  };
  onShow(() => consume());
  const listener = (event: { page: string }) => consume(event);
  uni.$on(EVENT, listener);
  onUnmounted(() => uni.$off(EVENT, listener));
}
