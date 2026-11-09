<script setup lang="ts">
import { ref } from "vue";
import AppShell from "../../components/AppShell.vue";
import SessionGate from "../../components/SessionGate.vue";
import LoadState from "../../components/LoadState.vue";
import ActivityRow from "../../components/ActivityRow.vue";
import NativeAd from "../../components/NativeAd.vue";
import { api, go } from "../../lib/api";
import { theme } from "../../lib/theme";
import { useLoad } from "../../lib/page";
import { date, km } from "../../lib/format";
import type { Activity, Dashboard, Page } from "../../lib/types";
const dashboard = ref<Dashboard | null>(null),
  recent = ref<Activity[]>([]);
const { loading, error, reload } = useLoad(async () => {
  const [summary, list] = await Promise.all([
    api<Dashboard>("/dashboard"),
    api<Page<Activity>>("/activities?page=0&size=4"),
  ]);
  dashboard.value = summary;
  recent.value = list.items;
});
</script>
<template>
  <AppShell
    active="overview"
    title="概览"
    subtitle="云端活动、平台连接与同步状态"
    ><template #action
      ><button class="btn secondary small" :disabled="loading" @click="reload">
        刷新数据
      </button></template
    ><SessionGate @ready="reload"
      ><LoadState :loading="loading" :error="error" @retry="reload"
        ><view class="hero-card">
          <view class="hero-kicker"><view class="small-dot" /><text>云端同步</text></view>
          <text class="hero-data-label">活动总数</text>
          <text class="hero-value">{{ dashboard?.activityCount ?? 0 }}</text>
          <view class="hero-stat-strip">
            <view class="hero-stat"><text class="hero-stat-value distance-tone">{{ km(dashboard?.totalDistanceMeters ?? 0) }}</text><text class="hero-stat-label">累计距离 · km</text></view>
            <view class="hero-stat"><text class="hero-stat-value">{{ dashboard?.connectedAccounts ?? 0 }}</text><text class="hero-stat-label">已连接平台</text></view>
            <view class="hero-stat"><text class="hero-stat-value pending-tone">{{ dashboard?.pendingJobs ?? 0 }}</text><text class="hero-stat-label">待处理任务</text></view>
          </view>
          <view class="button-row">
            <button class="btn primary" @click="go('sync')">{{ dashboard?.connectedAccounts ? "立即同步" : "连接平台" }}</button>
            <button class="btn ghost-light" @click="go('activities')">导入 FIT</button>
            <text class="hero-last-sync">{{ dashboard?.lastSyncAt ? `上次同步 ${date(dashboard.lastSyncAt)}` : "尚未完成过同步" }}</text>
          </view>
        </view>
        <view class="overview-columns"
          ><view class="card recent-card"
            ><view class="card-heading"
              ><view
                ><text class="section-title">最近活动</text
                ><text class="muted">从平台拉取或导入的 FIT 活动</text></view
              ><button class="text-button" @click="go('activities')">
                查看全部 ›
              </button></view
            ><ActivityRow
              v-for="activity in recent"
              :key="activity.id"
              :activity="activity"
            /><view v-if="!recent.length" class="empty-state compact"
              ><image class="empty-icon" :src="`/static/activity${theme.dark ? '-dark' : ''}.svg`" /><text
                class="empty-title"
                >从第一份活动开始</text
              ><text class="muted">导入 FIT 文件，或连接账号拉取活动。</text
              ><button class="btn secondary small" @click="go('activities')">
                导入我的活动
              </button></view
            ></view
          ><view class="getting-started card"
            ><text class="section-title">开始同步</text
            ><view class="step"
              ><text class="step-number">01</text
              ><view
                ><text class="step-title">连接平台</text
                ><text class="muted">授权你希望同步的运动账号。</text></view
              ></view
            ><view class="step"
              ><text class="step-number">02</text
              ><view
                ><text class="step-title">设定方向</text
                ><text class="muted">选择来源和目标，开启自动同步。</text></view
              ></view
            ><view class="step"
              ><text class="step-number">03</text
              ><view
                ><text class="step-title">查看任务</text
                ><text class="muted">在任务页查看同步结果与待处理项。</text></view
              ></view
            ><button class="btn secondary full" @click="go('sync')">
              管理同步规则
            </button></view
          ></view
        ><NativeAd /></LoadState></SessionGate
  ></AppShell>
</template>
