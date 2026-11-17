<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from "vue";
import { onHide } from "@dcloudio/uni-app";
import { themeColors, theme } from "../../lib/theme";
import AppShell from "../../components/AppShell.vue";
import SessionGate from "../../components/SessionGate.vue";
import LoadState from "../../components/LoadState.vue";
import ActivityRow from "../../components/ActivityRow.vue";
import { api, detail, go, messageOf, toast, uploadFit } from "../../lib/api";
import { useLoad } from "../../lib/page";
import { platformName, statusLabel } from "../../lib/format";
import type {
  Account,
  Activity,
  Page,
  PullStartResponse,
  PullStatusResponse,
} from "../../lib/types";

type ActivityFilter = "ALL" | "CYCLING" | "RUNNING" | "REVIEW";
type ActivitySort = "NEWEST" | "OLDEST";

const filterOptions: { value: ActivityFilter; label: string }[] = [
  { value: "ALL", label: "全部" },
  { value: "CYCLING", label: "骑行" },
  { value: "RUNNING", label: "跑步" },
  { value: "REVIEW", label: "待复核" },
];
const historyDayOptions = [30, 90, 180, 365];

const activities = ref<Activity[]>([]),
  total = ref(0),
  page = ref(0),
  listBusy = ref(false),
  importing = ref(false),
  importError = ref(""),
  importedId = ref(""),
  importMessage = ref("");
const selecting = ref(false),
  selectedIds = ref<string[]>([]);

const searchInput = ref(""),
  debouncedQuery = ref(""),
  filter = ref<ActivityFilter>("ALL"),
  platformFilter = ref(""),
  sort = ref<ActivitySort>("NEWEST");

const accounts = ref<Account[]>([]),
  selectedPullIds = ref<string[]>([]),
  pullHistoryDays = ref(90),
  pulling = ref(false),
  pullError = ref(""),
  pullSummary = ref(""),
  pullStatus = ref<PullStatusResponse | null>(null),
  activeJobIds = ref<string[]>([]);

let searchTimer: ReturnType<typeof setTimeout> | null = null;
let pullPollTimer: ReturnType<typeof setInterval> | null = null;
let initializedSelection = false;

const pullableAccounts = computed(() =>
  accounts.value.filter((a) => a.pullSupported !== false),
);
const oauthOnlyAccounts = computed(() =>
  accounts.value.filter((a) => a.pullSupported === false),
);
const platformFilterOptions = computed(() => {
  const seen = new Set<string>(["LOCAL"]);
  accounts.value.forEach((a) => seen.add(a.platform));
  activities.value.forEach((act) => {
    if (act.sourcePlatform) seen.add(act.sourcePlatform);
    act.platforms?.forEach((p) => seen.add(p));
  });
  return [
    { code: "", label: "全部来源" },
    ...[...seen].map((code) => ({ code, label: platformName(code) })),
  ];
});

const size = 20;

function activitiesQueryUrl() {
  const params = [
    `page=${page.value}`,
    `size=${size}`,
    `filter=${filter.value}`,
    `sort=${sort.value}`,
  ];
  if (debouncedQuery.value) {
    params.push(`query=${encodeURIComponent(debouncedQuery.value)}`);
  }
  if (platformFilter.value) {
    params.push(`platform=${encodeURIComponent(platformFilter.value)}`);
  }
  return `/activities?${params.join("&")}`;
}

async function fetchActivities() {
  listBusy.value = true;
  try {
    const result = await api<Page<Activity>>(activitiesQueryUrl());
    activities.value = result.items;
    total.value = result.total;
  } finally {
    listBusy.value = false;
  }
}

async function fetchAccounts() {
  const list = await api<Account[]>("/accounts");
  accounts.value = list;
  const pullableIds = list
    .filter((a) => a.pullSupported !== false)
    .map((a) => a.id);
  if (!initializedSelection) {
    selectedPullIds.value = pullableIds;
    initializedSelection = true;
  } else {
    const kept = selectedPullIds.value.filter((id) => pullableIds.includes(id));
    selectedPullIds.value = kept.length ? kept : pullableIds;
  }
}

function stopPullPoll() {
  if (pullPollTimer) {
    clearInterval(pullPollTimer);
    pullPollTimer = null;
  }
}

function formatPullSummary(status: PullStatusResponse) {
  const t = status.totals;
  const parts = [`扫描 ${t.fetched} 条`];
  if (t.imported > 0) parts.push(`云端新增 ${t.imported} 条`);
  if (t.merged > 0) parts.push(`去重合并 ${t.merged} 条`);
  if (t.existingSkipped > 0) parts.push(`已存在跳过 ${t.existingSkipped} 条`);
  if (t.needsReview > 0) parts.push(`待核对 ${t.needsReview} 条`);
  if (t.skippedNoFit > 0) parts.push(`无原始 FIT 跳过 ${t.skippedNoFit} 条`);
  const needsAttention = status.jobs.filter((j) =>
    ["WAITING_USER", "FAILED"].includes(j.status),
  );
  if (needsAttention.length) {
    return `${parts.join("，")}；${needsAttention.map((j) => `${platformName(j.platform)}：${j.message || statusLabel(j.status)}`).join("；")}`;
  }
  return `云端去重拉取完成：${parts.join("，")}。`;
}

async function refreshPullStatus(explicitIds?: string[]) {
  const ids = explicitIds ?? activeJobIds.value;
  const query = ids.length
    ? `?jobIds=${encodeURIComponent(ids.join(","))}`
    : "";
  const status = await api<PullStatusResponse>(`/activities/pull${query}`);
  pullStatus.value = status;
  if (status.activeCount > 0) {
    pulling.value = true;
    if (!ids.length) {
      activeJobIds.value = status.jobs.map((j) => j.id);
    }
    startPullPoll();
  } else {
    const wasPulling = pulling.value;
    pulling.value = false;
    stopPullPoll();
    if (wasPulling && status.jobs.length) {
      pullSummary.value = formatPullSummary(status);
      page.value = 0;
      await Promise.all([fetchActivities(), fetchAccounts()]);
    }
  }
}

function startPullPoll() {
  if (pullPollTimer) return;
  pullPollTimer = setInterval(() => {
    void refreshPullStatus().catch(() => {
      // 单次轮询波动忽略，下一轮继续查询。
    });
  }, 1500);
}

const { loading, error, reload } = useLoad(async () => {
  await Promise.all([fetchAccounts(), fetchActivities()]);
  await refreshPullStatus().catch(() => {});
});

watch(searchInput, (next) => {
  if (searchTimer) clearTimeout(searchTimer);
  searchTimer = setTimeout(() => {
    const trimmed = next.trim();
    if (trimmed === debouncedQuery.value) return;
    debouncedQuery.value = trimmed;
    page.value = 0;
    selectedIds.value = [];
    void fetchActivities().catch((reason) => {
      error.value = messageOf(reason);
    });
  }, 280);
});

onHide(() => {
  stopPullPoll();
});

onUnmounted(() => {
  stopPullPoll();
  if (searchTimer) clearTimeout(searchTimer);
});

function changeFilter(next: ActivityFilter) {
  if (filter.value === next) return;
  filter.value = next;
  page.value = 0;
  selectedIds.value = [];
  void fetchActivities().catch((reason) => {
    error.value = messageOf(reason);
  });
}

function changePlatformFilter(event: any) {
  const idx = Number(event.detail?.value ?? 0);
  const next = platformFilterOptions.value[idx]?.code ?? "";
  if (platformFilter.value === next) return;
  platformFilter.value = next;
  page.value = 0;
  selectedIds.value = [];
  void fetchActivities().catch((reason) => {
    error.value = messageOf(reason);
  });
}

function toggleSort() {
  sort.value = sort.value === "NEWEST" ? "OLDEST" : "NEWEST";
  page.value = 0;
  selectedIds.value = [];
  void fetchActivities().catch((reason) => {
    error.value = messageOf(reason);
  });
}

function clearSearchAndFilters() {
  searchInput.value = "";
  debouncedQuery.value = "";
  filter.value = "ALL";
  platformFilter.value = "";
  page.value = 0;
  selectedIds.value = [];
  void fetchActivities().catch((reason) => {
    error.value = messageOf(reason);
  });
}

function togglePullAccount(id: string) {
  selectedPullIds.value = selectedPullIds.value.includes(id)
    ? selectedPullIds.value.filter((x) => x !== id)
    : [...selectedPullIds.value, id];
}

function openConnect(platform?: string) {
  const suffix = platform
    ? `?connect=${encodeURIComponent(platform)}`
    : "?connect=1";
  uni.reLaunch({ url: `/pages/sync/index${suffix}` });
}

async function pullFromConnectedPlatforms() {
  if (pulling.value) return;
  pullError.value = "";
  pullSummary.value = "";
  if (!selectedPullIds.value.length) {
    pullError.value = "请至少选择一个支持拉取的已连接平台";
    return;
  }
  pulling.value = true;
  try {
    const res = await api<PullStartResponse>("/activities/pull", "POST", {
      accountIds: selectedPullIds.value,
      historyDays: pullHistoryDays.value,
    });
    activeJobIds.value = res.jobIds;
    toast("已开始云端去重拉取");
    await refreshPullStatus(res.jobIds);
  } catch (reason) {
    pulling.value = false;
    pullError.value = messageOf(reason);
  }
}

function chooseActivities(event: any) {
  selectedIds.value = event.detail.value;
}

function deliverSelected() {
  if (!selectedIds.value.length) return;
  uni.navigateTo({
    url: `/pages/sync/index?activityIds=${encodeURIComponent(selectedIds.value.join(","))}`,
  });
}

async function turn(delta: number) {
  page.value += delta;
  selectedIds.value = [];
  await fetchActivities();
}

async function importFile() {
  if (importing.value) return;
  importError.value = "";
  importedId.value = "";
  importMessage.value = "";
  let path = "";
  try {
    // #ifdef H5
    path = await new Promise<string>((resolve, reject) =>
      uni.chooseFile({
        count: 1,
        extension: [".fit"],
        success: (r) => resolve(r.tempFilePaths[0]),
        fail: reject,
      }),
    );
    // #endif
    // #ifdef MP-WEIXIN
    path = await new Promise<string>((resolve, reject) =>
      uni.chooseMessageFile({
        count: 1,
        type: "file",
        extension: ["fit"],
        success: (r) => resolve(r.tempFiles[0].path),
        fail: reject,
      }),
    );
    // #endif
  } catch (reason: any) {
    if (!String(reason?.errMsg || "").includes("cancel"))
      importError.value = "无法选择文件，请重试。";
    return;
  }
  if (!path) return;
  importing.value = true;
  try {
    const result = await uploadFit(path);
    importedId.value = result.activityId;
    importMessage.value = result.duplicate
      ? "这份 FIT 已在云端活动库中，未重复导入。"
      : "导入完成，活动已保存到你的云端骑行空间。";
    page.value = 0;
    await fetchActivities();
    toast(result.duplicate ? "已找到原有活动" : "FIT 导入完成");
  } catch (reason) {
    importError.value = messageOf(reason);
  } finally {
    importing.value = false;
  }
}

const hasActiveFilter = computed(
  () =>
    !!debouncedQuery.value ||
    filter.value !== "ALL" ||
    !!platformFilter.value,
);
const selectedPlatformFilterIndex = computed(() =>
  Math.max(
    0,
    platformFilterOptions.value.findIndex((o) => o.code === platformFilter.value),
  ),
);
</script>
<template>
  <AppShell
    active="activities"
    title="运动记录"
    subtitle="所有来源已合并去重并保存至云端"
    ><template #action
      ><button
        class="btn secondary small"
        :disabled="loading || listBusy || importing"
        @click="reload"
      >
        刷新活动
      </button></template
    ><SessionGate @ready="reload"
      ><view class="activities-entry-grid"
        ><view class="card pull-card"
          ><view class="card-heading"
            ><view class="import-copy"
              ><view class="feature-icon"
                ><image
                  :src="`/static/sync${theme.dark ? '-dark' : ''}.svg`" /></view
              ><view
                ><text class="section-title">从连接平台去重拉取</text
                ><text class="muted"
                  >拉取已连接账号的原始 FIT 存入云端，跨平台重复记录自动合并。</text
                ></view
              ></view
            ><button class="text-button" @click="go('sync')">
              管理账号 ›
            </button></view
          ><template v-if="pullableAccounts.length"
            ><view class="pull-section"
              ><text class="field-label">选择要拉取的平台</text
              ><view class="destination-options"
                ><button
                  v-for="account in pullableAccounts"
                  :key="account.id"
                  class="destination-chip"
                  :class="{
                    checked: selectedPullIds.includes(account.id),
                    expired: account.status === 'AUTH_EXPIRED',
                  }"
                  :disabled="pulling"
                  @click="togglePullAccount(account.id)"
                >
                  <text class="check-square">{{
                    selectedPullIds.includes(account.id) ? "✓" : ""
                  }}</text
                  >{{ platformName(account.platform)
                  }}<text
                    v-if="account.status === 'AUTH_EXPIRED'"
                    class="tiny danger"
                    >（登录失效）</text
                  >
                </button></view
              ></view
            ><view class="pull-section"
              ><text class="field-label">拉取历史范围</text
              ><view class="filter-chips"
                ><button
                  v-for="days in historyDayOptions"
                  :key="days"
                  class="filter-chip"
                  :class="{ selected: pullHistoryDays === days }"
                  :disabled="pulling"
                  @click="pullHistoryDays = days"
                >
                  最近 {{ days }} 天
                </button></view
              ></view
            ><text v-if="oauthOnlyAccounts.length" class="tiny muted block"
              >{{
                oauthOnlyAccounts.map((a) => platformName(a.platform)).join("、")
              }}
              官方 OAuth 暂不支持导出原始 FIT，仅作为同步接收平台。</text
            ><text v-if="pullError" class="error-text">{{ pullError }}</text
            ><view v-if="pullSummary" class="notice success-notice compact-notice"
              ><text>{{ pullSummary }}</text></view
            ><view
              v-if="pullStatus && pullStatus.jobs.length && (pulling || pullStatus.activeCount > 0)"
              class="pull-progress-list"
              ><view
                v-for="job in pullStatus.jobs"
                :key="job.id"
                class="pull-progress-row"
                ><view class="pull-progress-info"
                  ><text class="item-title"
                    >{{ platformName(job.platform) }} ·
                    {{ statusLabel(job.status) }}</text
                  ><text class="tiny muted">{{
                    job.message || "正在连接平台拉取与校验 FIT…"
                  }}</text></view
                ><button
                  v-if="job.status === 'WAITING_USER'"
                  class="text-button"
                  @click="openConnect(job.platform)"
                >
                  重连 ›
                </button></view
              ></view
            ><view class="pull-actions"
              ><button
                class="btn primary full"
                :disabled="pulling || !selectedPullIds.length"
                :loading="pulling"
                @click="pullFromConnectedPlatforms"
              >
                {{
                  pulling ? "正在云端拉取并去重…" : "从已连接平台去重拉取"
                }}
              </button></view
            ></template
          ><template v-else
            ><view class="pull-empty"
              ><text class="muted"
                >{{
                  oauthOnlyAccounts.length
                    ? "当前已连接的 Strava OAuth 账号不支持导出原始 FIT。连接 COROS、iGPSPORT、迈金、行者或 Garmin 后，即可一键去重拉取到云端。"
                    : "还没有连接支持拉取的运动平台。连接后即可直接把历史活动与原始 FIT 拉取去重并保存到云端。"
                }}</text
              ><button class="btn primary" @click="openConnect()">
                去连接运动平台
              </button></view
            ></template
          ></view
        ><view class="card import-card"
          ><view class="import-copy"
            ><view class="feature-icon"
              ><image
                :src="`/static/upload${theme.dark ? '-dark' : ''}.svg`" /></view
            ><view
              ><text class="section-title">导入本地 FIT 文件</text
              ><text class="muted"
                >上传原始 FIT 到云端，自动校验并与已有记录去重合并。</text
              ><text class="tiny muted"
                ><!-- #ifdef MP-WEIXIN -->从微信聊天中选择 .fit 文件<!-- #endif --><!-- #ifdef H5 -->选择电脑或手机上的
                .fit 文件<!-- #endif --></text
              ></view
            ></view
          ><button
            class="btn secondary"
            :disabled="importing"
            :loading="importing"
            @click="importFile"
          >
            {{ importing ? "正在导入…" : "导入 FIT 文件" }}
          </button></view
        ></view
      ><view v-if="importError" class="notice error-notice">{{
        importError
      }}</view
      ><view v-if="importMessage" class="notice success-notice"
        ><text>{{ importMessage }}</text
        ><button class="text-button" @click="detail(importedId)">
          查看活动 ›
        </button></view
      ><view class="list-heading"
        ><text class="section-title"
          >云端活动库<text class="count-pill">{{ total }}</text></text
        ><button
          v-if="total"
          class="text-button"
          @click="
            selecting = !selecting;
            selectedIds = [];
          "
        >
          {{ selecting ? "结束选择" : "选择活动同步" }}</button
        ><text v-else class="muted">按活动时间排序</text></view
      ><view class="card filter-toolbar"
        ><view class="search-row"
          ><input
            v-model="searchInput"
            class="input search-input"
            placeholder="搜索活动标题或运动类型（如 骑行、跑步）"
            confirm-type="search"
          /><button
            v-if="searchInput"
            class="btn secondary small"
            @click="searchInput = ''"
          >
            清除
          </button></view
        ><view class="filter-controls"
          ><view class="filter-chips"
            ><button
              v-for="opt in filterOptions"
              :key="opt.value"
              class="filter-chip"
              :class="{ selected: filter === opt.value }"
              @click="changeFilter(opt.value)"
            >
              {{ opt.label }}
            </button></view
          ><view class="filter-right"
            ><picker
              :range="platformFilterOptions"
              range-key="label"
              :value="selectedPlatformFilterIndex"
              @change="changePlatformFilter"
              ><view class="filter-chip"
                >{{
                  platformFilterOptions[selectedPlatformFilterIndex]?.label ||
                  "全部来源"
                }}
                ⌄</view
              ></picker
            ><button class="filter-chip" @click="toggleSort">
              {{ sort === "NEWEST" ? "时间倒序 ↓" : "时间正序 ↑" }}
            </button></view
          ></view
        ></view
      ><LoadState
        :loading="loading && !activities.length"
        :error="error"
        :empty="!activities.length"
        :empty-title="hasActiveFilter ? '没有匹配的活动' : '还没有运动记录'"
        :empty-text="
          hasActiveFilter
            ? '试试调整搜索关键词、运动类型或来源筛选。'
            : '上方可一键从已连接平台去重拉取，或导入本地 FIT 文件。'
        "
        @retry="reload"
        ><template #empty-action
          ><button
            v-if="hasActiveFilter"
            class="btn secondary"
            @click="clearSearchAndFilters"
          >
            清除筛选</button
          ><button
            v-else-if="pullableAccounts.length"
            class="btn primary"
            :disabled="pulling"
            @click="pullFromConnectedPlatforms"
          >
            立即从平台拉取</button
          ><button v-else class="btn primary" @click="openConnect()">
            连接运动平台
          </button></template
        ><view v-if="selecting" class="selection-toolbar"
          ><text class="muted"
            >已选 {{ selectedIds.length }} 条（待核对活动需先确认，每页最多 20
            条）</text
          ><button
            class="btn primary small"
            :disabled="!selectedIds.length"
            @click="deliverSelected"
          >
            同步选中活动
          </button></view
        ><view class="card activity-list"
          ><checkbox-group v-if="selecting" @change="chooseActivities"
            ><view
              v-for="activity in activities"
              :key="activity.id"
              class="selection-row"
              ><label class="selection-check"
                ><checkbox
                  :value="activity.id"
                  :disabled="activity.needsReview"
                  :checked="selectedIds.includes(activity.id)"
                  :color="themeColors.primary" /></label
              ><ActivityRow :activity="activity" /></view></checkbox-group
          ><template v-else
            ><ActivityRow
              v-for="activity in activities"
              :key="activity.id"
              :activity="activity" /></template></view
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
