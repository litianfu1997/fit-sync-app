import { session } from "./api";

type Entry<T> = { at: number; value?: T; flight?: Promise<T> };

const entries = new Map<string, Entry<unknown>>();
const revisions = new Map<string, number>();
let cacheToken = "";

/** 换账号（登录、登出、401）后旧缓存必须整体作废。 */
function ensureToken() {
  if (session.token === cacheToken) return;
  cacheToken = session.token;
  entries.clear();
  revisions.clear();
}

export function revision(scope: string): number {
  return revisions.get(scope) ?? 0;
}

export function invalidate(scope: string) {
  ensureToken();
  revisions.set(scope, revision(scope) + 1);
  for (const key of [...entries.keys()]) {
    if (key === scope || key.startsWith(`${scope}:`)) entries.delete(key);
  }
}

/**
 * GET 级缓存：TTL 内直接复用，同 key 的在途请求合并。
 * force 只跳过 TTL，不会打断已在途的请求。
 */
export function cachedQuery<T>(
  key: string,
  fetcher: () => Promise<T>,
  ttl: number,
  force = false,
): Promise<T> {
  ensureToken();
  const cached = entries.get(key) as Entry<T> | undefined;
  if (cached?.flight) return cached.flight;
  if (
    !force &&
    cached?.value !== undefined &&
    Date.now() - cached.at < ttl
  ) {
    return Promise.resolve(cached.value);
  }
  const flight = fetcher()
    .then((value) => {
      entries.set(key, { at: Date.now(), value });
      return value;
    })
    .catch((reason) => {
      if (cached) entries.set(key, cached);
      else entries.delete(key);
      throw reason;
    });
  entries.set(key, { at: cached?.at ?? 0, value: cached?.value, flight });
  return flight;
}
