<script setup lang="ts">
import { computed, ref } from "vue";
import { theme } from "../../lib/theme";
import { onLoad } from "@dcloudio/uni-app";
import AppShell from "../../components/AppShell.vue";
import SessionGate from "../../components/SessionGate.vue";
import LoadState from "../../components/LoadState.vue";
import TrackPreview from "../../components/TrackPreview.vue";
import NativeAd from "../../components/NativeAd.vue";
import {
  api,
  apiBase,
  confirm,
  detail,
  go,
  messageOf,
  session,
  toast,
} from "../../lib/api";
import { useLoad } from "../../lib/page";
import { date, duration, km, platformName } from "../../lib/format";
import type { Activity } from "../../lib/types";
const id = ref(""),
  activity = ref<Activity | null>(null),
  downloading = ref(false);
const reviewing = ref(false);
const averageSpeed = computed(() => {
  const ride = activity.value;
  return ride?.distanceMeters != null && ride.elapsedSeconds && ride.elapsedSeconds > 0
    ? ((ride.distanceMeters / ride.elapsedSeconds) * 3.6).toFixed(1)
    : "—";
});
onLoad((query) => {
  id.value = String(query?.id || "");
});
const { loading, error, reload } = useLoad(async () => {
  if (!id.value) throw new Error("缺少活动编号，请从活动列表重新进入");
  activity.value = await api<Activity>(
    `/activities/${encodeURIComponent(id.value)}`,
  );
});
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
    await reload();
  } catch (reason) {
    toast(messageOf(reason));
  } finally {
    reviewing.value = false;
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
      const error = await response.json().catch(() => ({}));
      throw new Error(error.message || "下载失败，请重试");
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
    subtitle="查看活动数据与原始 FIT"
    ><template #action
      ><button class="btn secondary small" @click="go('activities')">
        返回活动列表
      </button></template
    ><SessionGate @ready="reload"
      ><LoadState :loading="loading" :error="error" @retry="reload"
        ><template v-if="activity"
          ><view class="card detail-header activity-hero"
            ><view
              ><text class="tag">{{
                platformName(activity.sourcePlatform)
              }}</text
              ><text class="detail-title">{{
                activity.title || "骑行活动"
              }}</text
              ><text class="muted"
                >{{ date(activity.startTimeEpochMs) }} ·
                {{ activity.variantCount }} 份来源副本</text
              ><text class="activity-hero-distance">{{ km(activity.distanceMeters) }}<text class="activity-hero-unit"> km</text></text
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
                >系统暂停投递这条活动。先查看相似记录，只有确认是另一场活动时再保持分离。</text
              ></view
            ><view class="button-row"
              ><button
                v-if="activity.reviewCandidateId"
                class="btn secondary small"
                :disabled="reviewing"
                @click="detail(activity.reviewCandidateId)"
              >
                查看相似活动</button
              ><button
                class="btn primary small"
                :disabled="reviewing"
                :loading="reviewing"
                @click="keepSeparate"
              >
                确认这是另一场活动，保持分离
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
              ><text class="muted"
                >{{ activity.track?.length || 0 }} 个采样点</text
              ></view
            ><TrackPreview
              v-if="activity.track && activity.track.length > 1"
              :points="activity.track"
            /><view v-else class="empty-state compact"
              ><image class="empty-icon" :src="`/static/route${theme.dark ? '-dark' : ''}.svg`" /><text
                class="muted"
                >这份活动未包含可显示的 GPS 轨迹</text
              ></view
            ></view
          ><view v-if="activity.warnings?.length" class="notice"
            ><text
              v-for="warning in activity.warnings"
              :key="warning"
              class="block"
              >{{ warning }}</text
            ></view
          ><NativeAd /></template></LoadState></SessionGate
  ></AppShell>
</template>
