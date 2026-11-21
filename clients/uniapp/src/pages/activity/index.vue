<script setup lang="ts">
import { computed, ref } from "vue";
import { theme } from "../../lib/theme";
import { onLoad } from "@dcloudio/uni-app";
import AppShell from "../../components/AppShell.vue";
import SessionGate from "../../components/SessionGate.vue";
import LoadState from "../../components/LoadState.vue";
import TrackMap from "../../components/TrackMap.vue";
import SeriesChart from "../../components/SeriesChart.vue";
import NativeAd from "../../components/NativeAd.vue";
import { filterValidPoints } from "../../lib/geo";
import {
  fetchAccounts,
  fetchActivity,
  invalidate,
} from "../../lib/data-cache";
import {
  api,
  apiBase,
  confirm,
  detail,
  go,
  messageOf,
  openMap,
  session,
  toast,
} from "../../lib/api";
import { useLoad } from "../../lib/page";
import {
  date,
  duration,
  fileSize,
  km,
  platformInitials,
  platformName,
  sportLabel,
  statusLabel,
} from "../../lib/format";
import type {
  Account,
  Activity,
  ActivityDelivery,
  ActivityReconcileResult,
} from "../../lib/types";

const id = ref(""),
  activity = ref<Activity | null>(null),
  accounts = ref<Account[]>([]),
  downloading = ref(false),
  reviewing = ref(false),
  deleting = ref(false),
  dispatchingId = ref(""),
  retryingJobId = ref(""),
  reconciling = ref(false);
let autoReconciledId = "";

const averageSpeed = computed(() => {
  const ride = activity.value;
  return ride?.distanceMeters != null &&
    ride.elapsedSeconds &&
    ride.elapsedSeconds > 0
    ? ((ride.distanceMeters / ride.elapsedSeconds) * 3.6).toFixed(1)
    : "—";
});

const platformsLabel = computed(() => {
  const ride = activity.value;
  if (!ride) return "";
  const list = ride.platforms?.length
    ? ride.platforms
    : [ride.sourcePlatform || "LOCAL"];
  return [...new Set(list)].map((p) => platformName(p)).join(" · ");
});

const trackPoints = computed(() => filterValidPoints(activity.value?.track));
const speedSeries = computed(() =>
  (activity.value?.profile || [])
    .map((p) => (p.speedMps != null && Number.isFinite(p.speedMps) ? p.speedMps * 3.6 : null))
    .filter((v): v is number => v !== null && v >= 0),
);
const altitudeSeries = computed(() =>
  (activity.value?.profile || [])
    .map((p) => (p.altitudeMeters != null && Number.isFinite(p.altitudeMeters) ? p.altitudeMeters : null))
    .filter((v): v is number => v !== null),
);

function copyForAccount(account: Account) {
  return activity.value?.copies?.find(
    (c) => c.accountId === account.id || c.platform === account.platform,
  );
}

function deliveryForAccount(account: Account) {
  return activity.value?.deliveries?.find(
    (d) => d.accountId === account.id || d.platform === account.platform,
  );
}

function isActiveDelivery(job?: ActivityDelivery) {
  return !!job && ["PENDING", "RUNNING", "CONFIRMING", "RETRY"].includes(job.status);
}

function isRetryableDelivery(job?: ActivityDelivery) {
  return !!job && ["FAILED", "WAITING_USER"].includes(job.status);
}

function deliveryTone(status: string) {
  if (status === "SUCCEEDED") return "success";
  if (status === "FAILED") return "danger";
  if (["WAITING_USER", "RETRY"].includes(status)) return "amber";
  return "neutral";
}

function accountSubtitle(account: Account) {
  const copy = copyForAccount(account);
  if (copy) {
    return `已存在云端记录（远端编号 ${copy.remoteActivityId}）`;
  }
  const job = deliveryForAccount(account);
  if (job && isActiveDelivery(job)) {
    return job.message
      ? `${statusLabel(job.status)}：${job.message}`
      : `同步中（${statusLabel(job.status)}）`;
  }
  if (job && isRetryableDelivery(job)) {
    return job.message
      ? `${statusLabel(job.status)}：${job.message}`
      : `同步未完成（${statusLabel(job.status)}）`;
  }
  if (account.status === "AUTH_EXPIRED") {
    return "平台连接已过期，请在设置中重新连接";
  }
  if (reconciling.value && account.status === "CONNECTED") {
    return "正在核对目标平台是否已有该活动…";
  }
  return "尚未同步到该平台";
}

onLoad((query) => {
  id.value = String(query?.id || "");
});

const { loading, refreshing, fatalError, staleError, reload } = useLoad(async (force) => {
  if (!id.value) throw new Error("缺少活动编号，请从活动列表重新进入");
  const [act, accs] = await Promise.all([
    fetchActivity(id.value, force),
    fetchAccounts().catch(() => [] as Account[]),
  ]);
  activity.value = act;
  accounts.value = accs;
  const hasMissingConnected =
    !act.needsReview &&
    accs.some(
      (a) =>
        a.status === "CONNECTED" &&
        !act.copies?.some(
          (c) => c.accountId === a.id || c.platform === a.platform,
        ),
    );
  if (hasMissingConnected && autoReconciledId !== act.id) {
    autoReconciledId = act.id;
    void reconcilePlatforms(false);
  }
}, { ttl: 60_000, scope: "activity" });

async function reconcilePlatforms(force: boolean) {
  if (!activity.value || activity.value.needsReview || reconciling.value) return;
  const targetId = activity.value.id;
  reconciling.value = true;
  try {
    const res = await api<ActivityReconcileResult>(
      `/activities/${encodeURIComponent(targetId)}/reconcile`,
      "POST",
      { force },
    );
    if (res.matchedCount > 0) {
      const [freshAct, freshAccs] = await Promise.all([
        fetchActivity(targetId, true),
        fetchAccounts(true).catch(() => accounts.value),
      ]);
      if (id.value === targetId) {
        activity.value = freshAct;
        accounts.value = freshAccs;
      }
      if (force) {
        toast(`已检测到 ${res.matchedCount} 个平台存在该活动`);
      }
    } else if (force) {
      if (res.results.some((r) => r.status === "AUTH_EXPIRED")) {
        const freshAccs = await fetchAccounts(true).catch(
          () => accounts.value,
        );
        if (id.value === targetId) {
          accounts.value = freshAccs;
        }
      }
      toast(
        res.checkedCount > 0
          ? "已完成核对，暂未发现新增已同步平台"
          : "当前没有需要核对的已连接平台",
      );
    }
  } catch (reason) {
    if (force) toast(messageOf(reason));
  } finally {
    reconciling.value = false;
  }
}

async function mergeIntoCandidate() {
  const candidate = activity.value?.reviewCandidate;
  const targetTitle = candidate?.title || "候选活动";
  if (
    !(await confirm(
      "合并到候选活动",
      `将把本活动的原始 FIT 副本与平台映射合并到「${targetTitle}」，并删除本条重复记录。合并后按规则自动同步。确认合并？`,
    ))
  )
    return;
  reviewing.value = true;
  try {
    const res = await api<{ activityId: string }>(
      `/activities/${encodeURIComponent(id.value)}/review`,
      "POST",
      { decision: "MERGE_INTO_CANDIDATE" },
    );
    toast("已合并到候选活动");
    invalidate("activity");
    invalidate("activities");
    invalidate("dashboard");
    invalidate("jobs");
    uni.redirectTo({
      url: `/pages/activity/index?id=${encodeURIComponent(res.activityId)}`,
    });
  } catch (reason) {
    toast(messageOf(reason));
  } finally {
    reviewing.value = false;
  }
}

async function keepSeparate() {
  if (
    !(await confirm(
      "确认保留为独立活动",
      "确认这是另一场活动，而不是同一次骑行的重复记录？确认后会解除待核对状态，并按已开启的规则继续自动同步。",
    ))
  )
    return;
  reviewing.value = true;
  try {
    await api(`/activities/${encodeURIComponent(id.value)}/review`, "POST", {
      decision: "KEEP_SEPARATE",
    });
    toast("已确认为独立活动");
    invalidate("activity");
    invalidate("activities");
    await reload(true);
  } catch (reason) {
    toast(messageOf(reason));
  } finally {
    reviewing.value = false;
  }
}

async function dispatchToAccount(account: Account) {
  if (!activity.value || dispatchingId.value || retryingJobId.value) return;
  if (activity.value.needsReview) {
    toast("请先完成相似活动核对");
    return;
  }
  dispatchingId.value = account.id;
  try {
    const res = await api<{ jobIds: string[] }>("/sync", "POST", {
      sourcePlatform: activity.value.sourcePlatform || "LOCAL",
      destinationAccountIds: [account.id],
      activityIds: [activity.value.id],
    });
    toast(
      res.jobIds.length
        ? `已加入 ${platformName(account.platform)} 投递队列`
        : "该平台已有此活动或任务",
    );
    invalidate("activity");
    invalidate("jobs");
    await reload(true);
  } catch (reason) {
    toast(messageOf(reason));
  } finally {
    dispatchingId.value = "";
  }
}

async function retryDelivery(job: ActivityDelivery) {
  if (!activity.value || dispatchingId.value || retryingJobId.value) return;
  retryingJobId.value = job.jobId;
  try {
    await api(`/jobs/${encodeURIComponent(job.jobId)}/retry`, "POST");
    toast("任务已重新排队");
    invalidate("activity");
    invalidate("jobs");
    await reload(true);
  } catch (reason) {
    toast(messageOf(reason));
  } finally {
    retryingJobId.value = "";
  }
}

async function removeActivity() {
  if (
    !(await confirm(
      "删除云端活动",
      "将删除此活动在云端的记录、副本与未完成任务；各运动平台网站上已有的活动不会被删除，下次全量拉取可能会重新拉回。确认删除？",
    ))
  )
    return;
  deleting.value = true;
  try {
    await api(`/activities/${encodeURIComponent(id.value)}`, "DELETE");
    toast("活动已删除");
    invalidate("activity");
    invalidate("activities");
    invalidate("dashboard");
    invalidate("jobs");
    go("activities", { kind: "refresh" });
  } catch (reason) {
    toast(messageOf(reason));
  } finally {
    deleting.value = false;
  }
}

async function download() {
  downloading.value = true;
  try {
    const url = `${apiBase()}/v1/activities/${encodeURIComponent(id.value)}/file`;
    // #ifdef H5
    const response = await fetch(url, {
      headers: { Authorization: `Bearer ${session.token}` },
    });
    if (!response.ok) {
      const err = await response.json().catch(() => ({}));
      throw new Error(err.message || "下载失败，请重试");
    }
    const blob = await response.blob(),
      link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = `FitSync-${id.value}.fit`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(link.href), 1000);
    // #endif
    // #ifdef MP-WEIXIN
    const path = await new Promise<string>((resolve, reject) =>
      uni.downloadFile({
        url,
        header: { Authorization: `Bearer ${session.token}` },
        success: (r) =>
          r.statusCode === 200
            ? resolve(r.tempFilePath)
            : reject(new Error("文件下载失败，请检查活动原始文件是否仍存在")),
        fail: () => reject(new Error("下载失败，请检查网络后重试")),
      }),
    );
    await new Promise<void>((resolve, reject) =>
      uni.shareFileMessage({
        filePath: path,
        fileName: `FitSync-${id.value}.fit`,
        success: () => resolve(),
        fail: (r: any) =>
          String(r.errMsg).includes("cancel")
            ? resolve()
            : reject(new Error("未能分享 FIT 文件，请稍后再试")),
      }),
    );
    // #endif
  } catch (reason) {
    toast(messageOf(reason));
  } finally {
    downloading.value = false;
  }
}
</script>
<template>
  <AppShell
    active="activities"
    title="活动详情"
    subtitle="查看云端活动数据、去重副本与平台分发"
    ><template #action
      ><button class="btn secondary small" @click="go('activities')">
        返回活动列表
      </button></template
    ><SessionGate @ready="reload(true)"
      ><LoadState
        :loading="loading"
        :refreshing="refreshing"
        :error="fatalError"
        :stale-error="staleError"
        skeleton="detail"
        @retry="reload(true)"
        ><template v-if="activity"
          ><view class="card detail-header activity-hero"
            ><view
              ><text class="tag">{{ platformsLabel }}</text
              ><text class="detail-title">{{
                activity.title || sportLabel(activity.sportType)
              }}</text
              ><text class="muted"
                >{{ date(activity.startTimeEpochMs) }} ·
                {{ sportLabel(activity.sportType) }} ·
                {{ activity.variantCount }} 份云端副本</text
              ><text class="activity-hero-distance"
                >{{ km(activity.distanceMeters)
                }}<text class="activity-hero-unit"> km</text></text
              ><text v-if="activity.needsReview" class="tag amber"
                >相似活动待核对</text
              ></view
            ><button
              class="btn primary"
              :disabled="downloading"
              :loading="downloading"
              @click="download"
            >
              导出原始 FIT
            </button></view
          ><view v-if="activity.needsReview" class="notice review-notice"
            ><view
              ><text class="item-title">发现相似活动，需要你核对</text
              ><text class="muted"
                >{{
                  activity.reviewCandidate
                    ? `与「${activity.reviewCandidate.title}」（${date(activity.reviewCandidate.startTimeEpochMs)} · ${km(activity.reviewCandidate.distanceMeters)} km · ${platformName(activity.reviewCandidate.sourcePlatform)}）高度相似，系统暂缓了自动投递。`
                    : "系统暂停投递这条活动。先核对是否与现有活动重复，可合并到候选或确认保持分离。"
                }}</text
              ></view
            ><view class="button-row"
              ><button
                v-if="activity.reviewCandidateId && activity.reviewCandidate"
                class="btn primary small"
                :disabled="reviewing"
                :loading="reviewing"
                @click="mergeIntoCandidate"
              >
                合并到候选活动</button
              ><button
                v-if="activity.reviewCandidateId"
                class="btn secondary small"
                :disabled="reviewing"
                @click="detail(activity.reviewCandidateId)"
              >
                查看相似活动</button
              ><button
                class="btn secondary small"
                :disabled="reviewing"
                :loading="reviewing"
                @click="keepSeparate"
              >
                确认是另一场活动，保持分离
              </button></view
            ></view
          ><view class="stats-grid detail-stats"
            ><view class="stat-card"
              ><text class="muted">平均速度</text
              ><text class="stat-value"
                >{{ averageSpeed
                }}<text class="stat-unit">km/h</text></text
              ></view
            ><view class="stat-card"
              ><text class="muted">活动时长</text
              ><text class="stat-value duration-value">{{
                duration(activity.elapsedSeconds)
              }}</text></view
            ><view class="stat-card"
              ><text class="muted">平均心率</text
              ><text class="stat-value"
                >{{ activity.avgHeartRate ?? "—"
                }}<text class="stat-unit">bpm</text></text
              ></view
            ><view class="stat-card"
              ><text class="muted">平均功率</text
              ><text class="stat-value"
                >{{ activity.avgPower ?? "—"
                }}<text class="stat-unit">W</text></text
              ></view
            ></view
          ><view class="card route-card"
            ><view class="card-heading"
              ><text class="section-title">沿途轨迹</text
              ><view v-if="trackPoints.length > 0" class="route-heading-meta"
                ><text class="muted tiny">{{ trackPoints.length }} 个采样点</text
                ><button class="text-button" @click="openMap(activity.id)">
                  全屏查看
                </button></view
              ><text v-else class="muted"
                >0 个采样点</text
              ></view
            ><TrackMap
              v-if="trackPoints.length > 0"
              :points="trackPoints"
              :interactive="false"
              height="200px"
              @tap="openMap(activity.id)"
            /><view v-else class="empty-state compact"
              ><image
                class="empty-icon"
                :src="`/static/route${theme.dark ? '-dark' : ''}.svg`"
              /><text class="muted"
                >这份活动未包含可显示的 GPS 轨迹</text
              ></view
            ><view v-if="speedSeries.length >= 2 || altitudeSeries.length >= 2" class="profile-charts"
              ><SeriesChart
                v-if="speedSeries.length >= 2"
                title="速度"
                unit="km/h"
                :values="speedSeries"
                tone="info"
              /><SeriesChart
                v-if="altitudeSeries.length >= 2"
                title="海拔"
                unit="m"
                :values="altitudeSeries"
                tone="muted"
              /></view
            ></view
          ><view v-if="accounts.length" class="card"
            ><view class="card-heading"
              ><view
                ><text class="section-title">平台同步状态</text
                ><text class="muted"
                  >查看此活动在各已连接平台的存在状态或单条补发</text
                ></view
              ><button
                class="btn secondary small"
                :disabled="reconciling || loading || activity.needsReview"
                :loading="reconciling"
                @click="reconcilePlatforms(true)"
              >
                {{ reconciling ? "检测中…" : "检测状态" }}
              </button></view
            ><view
              v-for="account in accounts"
              :key="account.id"
              class="account-row"
              ><view class="copy-account-left"
                ><view
                  class="platform-monogram"
                  :class="account.platform.toLowerCase()"
                  >{{ platformInitials[account.platform] || "平" }}</view
                ><view
                  ><text class="item-title"
                    >{{ platformName(account.platform) }} ·
                    {{ account.displayName }}</text
                  ><text class="muted">{{ accountSubtitle(account) }}</text></view
                ></view
              ><text v-if="copyForAccount(account)" class="tag success"
                >已同步</text
              ><text
                v-else-if="isActiveDelivery(deliveryForAccount(account))"
                class="tag"
                :class="deliveryTone(deliveryForAccount(account)!.status)"
                >{{ statusLabel(deliveryForAccount(account)!.status) }}</text
              ><button
                v-else-if="isRetryableDelivery(deliveryForAccount(account))"
                class="btn secondary small"
                :disabled="!!dispatchingId || !!retryingJobId || activity.needsReview"
                :loading="retryingJobId === deliveryForAccount(account)!.jobId"
                @click="retryDelivery(deliveryForAccount(account)!)"
              >
                重试
              </button><button
                v-else
                class="btn secondary small"
                :disabled="!!dispatchingId || !!retryingJobId || activity.needsReview"
                :loading="dispatchingId === account.id"
                @click="dispatchToAccount(account)"
              >
                补发
              </button></view
            ></view
          ><view v-if="activity.variants?.length" class="card"
            ><view class="card-heading"
              ><view
                ><text class="section-title">云端原始 FIT 副本</text
                ><text class="muted"
                  >同一骑行多平台拉取去重后保留的原始文件记录</text
                ></view
              ><text class="tag neutral"
                >{{ activity.variants.length }} 份</text
              ></view
            ><view
              v-for="variant in activity.variants"
              :key="variant.id"
              class="account-row"
              ><view
                ><text class="item-title"
                  >{{ platformName(variant.sourcePlatform)
                  }}{{ variant.isPrimary ? "（主副本）" : "" }}</text
                ><text class="muted"
                  >{{ fileSize(variant.fileSize) || "FIT 原件" }} · 质量分
                  {{ variant.qualityScore ?? 0 }}</text
                ></view
              ><text
                class="tag"
                :class="variant.isPrimary ? 'success' : 'neutral'"
                >{{ variant.isPrimary ? "当前采用" : "已关联" }}</text
              ></view
            ></view
          ><view v-if="activity.warnings?.length" class="notice"
            ><text
              v-for="warning in activity.warnings"
              :key="warning"
              class="block"
              >{{ warning }}</text
            ></view
          ><view class="danger-zone"
            ><button
              class="text-button danger"
              :disabled="deleting"
              @click="removeActivity"
            >
              {{ deleting ? "正在删除…" : "删除云端活动" }}
            </button></view
          ><NativeAd /></template></LoadState></SessionGate
  ></AppShell>
</template>
