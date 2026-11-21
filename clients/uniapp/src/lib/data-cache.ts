import { api } from "./api";
import { cachedQuery, invalidate } from "./query-cache";
import type { Account, Activity } from "./types";

const ACCOUNTS_TTL = 60_000;
const ACTIVITY_TTL = 60_000;

export function fetchAccounts(force = false): Promise<Account[]> {
  return cachedQuery(
    "accounts",
    () => api<Account[]>("/accounts"),
    ACCOUNTS_TTL,
    force,
  );
}

export function fetchActivity(id: string, force = false): Promise<Activity> {
  return cachedQuery(
    `activity:${id}`,
    () => api<Activity>(`/activities/${encodeURIComponent(id)}`),
    ACTIVITY_TTL,
    force,
  );
}

export function invalidateAll() {
  ["accounts", "activity", "activities", "dashboard", "jobs"].forEach(
    invalidate,
  );
}

export { invalidate };
