<script setup lang="ts">
import { ref } from "vue";
import { theme } from "../../lib/theme";
import AppShell from "../../components/AppShell.vue";
import SessionGate from "../../components/SessionGate.vue";
import LoadState from "../../components/LoadState.vue";
import { api, go, messageOf, toast } from "../../lib/api";
import { useLoad } from "../../lib/page";
import { cachedQuery } from "../../lib/query-cache";
import { invalidate } from "../../lib/data-cache";
import { date, jobKind, platformName, statusLabel } from "../../lib/format";
import type { Job, Page } from "../../lib/types";
const jobs = ref<Job[]>([]),
  total = ref(0),
  page = ref(0),
  retrying = ref(""),
  listBusy = ref(false),
  size = 30;
const { loading, refreshing, fatalError, staleError, reload } = useLoad(async (force) => {
  await fetchPage(force);
}, { ttl: 45_000, scope: "jobs" });
async function fetchPage(force = false) {
  if (listBusy.value) return;
  listBusy.value = true;
  try {
    const url = `/jobs?page=${page.value}&size=${size}`;
    const result = await cachedQuery(
      `jobs:${url}`,
      () => api<Page<Job>>(url),
      20_000,
      force,
    );
    jobs.value = result.items;
    total.value = result.total;
  } finally {
    listBusy.value = false;
  }
}
async function turn(delta: number) {
  page.value += delta;
  try {
    await fetchPage();
  } catch (reason) {
    toast(messageOf(reason));
  }
}
async function retry(job: Job) {
  retrying.value = job.id;
  try {
    await api(`/jobs/${job.id}/retry`, "POST");
    toast("任务已重新排队");
    invalidate("jobs");
    await fetchPage(true);
  } catch (reason) {
    toast(messageOf(reason));
  } finally {
    retrying.value = "";
  }
}
function tone(status: string) {
  if (status === "SUCCEEDED") return "success";
  if (status === "FAILED") return "danger";
  if (["WAITING_USER", "RETRY"].includes(status)) return "amber";
  return "neutral";
}
</script>
<template>
  <AppShell
    active="jobs"
    title="同步任务"
    subtitle="查看执行进度与失败原因"
    ><template #action
      ><button
        class="btn secondary small"
        :disabled="loading || refreshing"
        @click="reload(true)"
      >
        {{ refreshing ? "刷新中…" : "刷新进度" }}
      </button></template
    ><SessionGate @ready="reload(true)"
      ><view class="notice info-notice"
        ><image :src="`/static/cloud${theme.dark ? '-dark' : ''}.svg`" /><text
          >任务由云服务持续执行，关闭此页面后仍会继续。点击「刷新进度」查看最新结果。</text
        ></view
      ><LoadState
        :loading="loading"
        :refreshing="refreshing"
        :error="fatalError"
        :stale-error="staleError"
        :empty="!jobs.length"
        skeleton="list"
        empty-title="还没有同步任务"
        empty-text="在同步中心发起同步后，这里会展示每一步的执行结果。"
        @retry="reload(true)"
        ><template #empty-action
          ><button class="btn primary" @click="go('sync')">
            前往同步中心
          </button></template
        ><view v-if="listBusy" class="inline-loading" role="status"
          ><view class="spinner" /><text>正在加载任务…</text></view
        ><view class="card jobs-list" :class="{ 'is-loading': listBusy }"
          ><view v-for="job in jobs" :key="job.id" class="job-row"
            ><view class="job-status-icon" :class="tone(job.status)"
              ><text>{{
                job.status === "SUCCEEDED"
                  ? "✓"
                  : ["FAILED", "WAITING_USER"].includes(job.status)
                    ? "!"
                    : "↻"
              }}</text></view
            ><view class="job-info"
              ><view class="job-top"
                ><text class="item-title">{{
                  job.activityTitle || jobKind(job.kind)
                }}</text
                ><text class="tag" :class="tone(job.status)">{{
                  statusLabel(job.status)
                }}</text></view
              ><text class="muted"
                >{{ jobKind(job.kind) }} · {{ platformName(job.platform) }} ·
                {{ date(job.createdAtEpochMs) }}</text
              ><text v-if="job.message" class="job-message">{{
                job.message
              }}</text
              ><text v-if="job.status === 'WAITING_USER'" class="tiny muted"
                >如平台登录已过期，请先在同步中心重新连接，再重试。</text
              ></view
            ><button
              v-if="['FAILED', 'RETRY', 'WAITING_USER'].includes(job.status)"
              class="btn secondary small"
              :disabled="!!retrying"
              :loading="retrying === job.id"
              @click="retry(job)"
            >
              重试
            </button></view
          ></view
        ><view class="pagination"
          ><button
            class="btn secondary small"
            :disabled="listBusy || page === 0"
            @click="turn(-1)"
          >
            上一页</button
          ><text class="muted"
            >第 {{ page + 1 }} /
            {{ Math.max(1, Math.ceil(total / size)) }} 页</text
          ><button
            class="btn secondary small"
            :disabled="listBusy || (page + 1) * size >= total"
            @click="turn(1)"
          >
            下一页
          </button></view
        ></LoadState
      ></SessionGate
    ></AppShell
  >
</template>
