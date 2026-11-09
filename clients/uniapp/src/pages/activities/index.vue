<script setup lang="ts">
import { ref } from "vue";
import { themeColors, theme } from "../../lib/theme";
import AppShell from "../../components/AppShell.vue";
import SessionGate from "../../components/SessionGate.vue";
import LoadState from "../../components/LoadState.vue";
import ActivityRow from "../../components/ActivityRow.vue";
import { api, detail, messageOf, toast, uploadFit } from "../../lib/api";
import { useLoad } from "../../lib/page";
import type { Activity, Page } from "../../lib/types";
const activities = ref<Activity[]>([]),
  total = ref(0),
  page = ref(0),
  importing = ref(false),
  importError = ref(""),
  importedId = ref(""),
  importMessage = ref("");
const selecting = ref(false),
  selectedIds = ref<string[]>([]);
function chooseActivities(event: any) {
  selectedIds.value = event.detail.value;
}
function deliverSelected() {
  if (!selectedIds.value.length) return;
  uni.navigateTo({
    url: `/pages/sync/index?activityIds=${encodeURIComponent(selectedIds.value.join(","))}`,
  });
}
const size = 20;
const { loading, error, reload } = useLoad(async () => {
  const result = await api<Page<Activity>>(
    `/activities?page=${page.value}&size=${size}`,
  );
  activities.value = result.items;
  total.value = result.total;
});
async function turn(delta: number) {
  page.value += delta;
  selectedIds.value = [];
  await reload();
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
      ? "这份 FIT 已在活动库中，未重复导入。"
      : "导入完成，活动已保存到你的骑行空间。";
    page.value = 0;
    await reload();
    toast(result.duplicate ? "已找到原有活动" : "FIT 导入完成");
  } catch (reason) {
    importError.value = messageOf(reason);
  } finally {
    importing.value = false;
  }
}
</script>
<template>
  <AppShell
    active="activities"
    title="运动记录"
    subtitle="所有来源已合并去重"
    ><template #action
      ><button
        class="btn secondary small"
        :disabled="loading || importing"
        @click="reload"
      >
        刷新活动
      </button></template
    ><SessionGate @ready="reload"
      ><view class="import-card card"
        ><view class="import-copy"
          ><view class="feature-icon"><image :src="`/static/upload${theme.dark ? '-dark' : ''}.svg`" /></view
          ><view
            ><text class="section-title">导入活动</text
            ><text class="muted"
              >上传原始 FIT 文件，自动校验并识别重复记录。</text
            ><text class="tiny muted"
              ><!-- #ifdef MP-WEIXIN -->从微信聊天中选择 .fit 文件<!-- #endif --><!-- #ifdef H5 -->选择电脑或手机上的
              .fit 文件<!-- #endif --></text
            ></view
          ></view
        ><button
          class="btn primary"
          :disabled="importing"
          :loading="importing"
          @click="importFile"
        >
          {{ importing ? "正在导入…" : "导入 FIT 文件" }}
        </button></view
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
          >全部活动<text class="count-pill">{{ total }}</text></text
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
      ><LoadState
        :loading="loading"
        :error="error"
        :empty="!activities.length"
        empty-title="还没有运动记录"
        empty-text="导入 FIT 文件，或在同步中心连接运动账号。"
        @retry="reload"
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
