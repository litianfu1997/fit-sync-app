import { session } from "./api";

type Entry<T> = { at: number; value?: T; flight?: Promise<T> };

const entries = new Map<string, Entry<unknown>>();
const revisions = new Map<string, number>();
const STORE_PREFIX = "fitsync.qc.v1:";
/** 快照超过该时长后不再直出，回退为网络加载（避免长时间展示陈旧数据）。 */
const DEFAULT_MAX_STALE = 10 * 60_000;
let cacheToken = "";

/** 换账号（登录、登出、401）后旧缓存必须整体作废。 */
function ensureToken() {
  if (session.token === cacheToken) return;
  cacheToken = session.token;
  entries.clear();
  revisions.clear();
  hydrateFromStorage();
}

/** 冷启动时从本地快照恢复上次会话的数据，让页面首帧即可直出旧内容。 */
function hydrateFromStorage() {
  let keys: string[];
  try {
    keys = uni.getStorageInfoSync().keys;
  } catch {
    return;
  }
  for (const storageKey of keys) {
    if (!storageKey.startsWith(STORE_PREFIX)) continue;
    try {
      const snapshot = JSON.parse(String(uni.getStorageSync(storageKey))) as {
        token?: string;
        at?: number;
        value?: unknown;
      };
      if (
        !snapshot ||
        snapshot.token !== session.token ||
        typeof snapshot.at !== "number"
      )
        continue;
      entries.set(storageKey.slice(STORE_PREFIX.length), {
        at: snapshot.at,
        value: snapshot.value,
      });
    } catch {
      // 单条快照损坏时跳过，不影响其余恢复。
    }
  }
}

function persist(key: string, at: number, value: unknown) {
  try {
    uni.setStorageSync(
      STORE_PREFIX + key,
      JSON.stringify({ token: session.token, at, value }),
    );
  } catch {
    // 快照写不进去（空间不足等）只损失直出能力，不影响本次会话。
  }
}

function forget(key: string) {
  try {
    uni.removeStorageSync(STORE_PREFIX + key);
  } catch {
    // 忽略删除失败。
  }
}

export function revision(scope: string): number {
  return revisions.get(scope) ?? 0;
}

export function invalidate(scope: string) {
  ensureToken();
  revisions.set(scope, revision(scope) + 1);
  for (const key of [...entries.keys()]) {
    if (key === scope || key.startsWith(`${scope}:`)) {
      entries.delete(key);
      forget(key);
    }
  }
}

function fetchIntoCache<T>(
  key: string,
  cached: Entry<T> | undefined,
  fetcher: () => Promise<T>,
  keepStaleOnError: boolean,
): Promise<T> {
  const flight = fetcher()
    .then((value) => {
      const at = Date.now();
      entries.set(key, { at, value });
      persist(key, at, value);
      return value;
    })
    .catch((reason) => {
      if (cached) entries.set(key, cached);
      else entries.delete(key);
      if (keepStaleOnError) return cached?.value as T;
      throw reason;
    });
  entries.set(key, { at: cached?.at ?? 0, value: cached?.value, flight });
  return flight;
}

/**
 * GET 级缓存：TTL 内直接复用，同 key 的在途请求合并。
 * force 只跳过 TTL，不会打断已在途的请求。
 * 超过 TTL 但未超过 maxStale 时旧值直出并后台刷新（下次进入即新值），
 * 冷启动的水合快照同样适用，从而避免整页骨架等待网络。
 */
export function cachedQuery<T>(
  key: string,
  fetcher: () => Promise<T>,
  ttl: number,
  force = false,
  maxStale = DEFAULT_MAX_STALE,
): Promise<T> {
  ensureToken();
  const cached = entries.get(key) as Entry<T> | undefined;
  if (cached?.flight) return cached.flight;
  if (cached?.value !== undefined && !force) {
    const age = Date.now() - cached.at;
    if (age < ttl) return Promise.resolve(cached.value);
    if (age < maxStale) {
      // 旧值立即返回给调用方，网络刷新在后台静默进行。
      void fetchIntoCache(key, cached, fetcher, true);
      return Promise.resolve(cached.value);
    }
  }
  return fetchIntoCache(key, cached, fetcher, false);
}
