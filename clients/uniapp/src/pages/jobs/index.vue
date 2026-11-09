<script setup lang="ts">
import { ref } from "vue";
import { theme } from "../../lib/theme";
import AppShell from "../../components/AppShell.vue";
import SessionGate from "../../components/SessionGate.vue";
import LoadState from "../../components/LoadState.vue";
import { api, go, messageOf, toast } from "../../lib/api";
import { useLoad } from "../../lib/page";
import { date, jobKind, platformName, statusLabel } from "../../lib/format";
import type { Job, Page } from "../../lib/types";
const jobs = ref<Job[]>([]),
  total = ref(0),
  page = ref(0),
  retrying = ref(""),
  size = 30;
const { loading, error, reload } = useLoad(async () => {
  const result = await api<Page<Job>>(`/jobs?page=${page.value}&size=${size}`);
  jobs.value = result.items;
  total.value = result.total;
});
async function turn(delta: number) {
  page.value += delta;
  await reload();
}
async function retry(job: Job) {
  retrying.value = job.id;
  try {
    await api(`/jobs/${job.id}/retry`, "POST");
    toast("任务已重新排队");
    await reload();
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
      ><button class="btn secondary small" :disabled="loading" @click="reload">
        刷新进度
      </button></template
    ><SessionGate @ready="reload"
      ><view class="notice info-notice"
        ><image :src="`/static/cloud${theme.dark ? '-dark' : ''}.svg`" /><text
          >任务由云服务持续执行，关闭此页面后仍会继续。点击「刷新进度」查看最新结果。</text
        ></view
      ><LoadState
        :loading="loading"
        :error="error"
        :empty="!jobs.length"
        empty-title="还没有同步任务"
        empty-text="在同步中心发起同步后，这里会展示每一步的执行结果。"
        @retry="reload"
        ><template #empty-action
          ><button class="btn primary" @click="go('sync')">
            前往同步中心
          </button></template
        ><view class="card jobs-list"
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
            :disabled="loading || page === 0"
            @click="turn(-1)"
          >
            上一页</button
          ><text class="muted"
            >第 {{ page + 1 }} /
            {{ Math.max(1, Math.ceil(total / size)) }} 页</text
          ><button
            class="btn secondary small"
            :disabled="loading || (page + 1) * size >= total"
            @click="turn(1)"
          >
            下一页
          </button></view
        ></LoadState
      ></SessionGate
    ></AppShell
  >
</template>
