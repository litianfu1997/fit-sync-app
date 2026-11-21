<script setup lang="ts">
import { onLaunch } from "@dcloudio/uni-app";
import { initialize } from "./lib/api";
import { initializeTheme } from "./lib/theme";
onLaunch(() => {
  initializeTheme();
  void initialize();
  // #ifdef H5
  preloadPageChunks();
  // #endif
});
// #ifdef H5
/**
 * H5 生产构建按页分包，会话内首次进入某个菜单要先下载对应 chunk，
 * 期间整屏空白。启动后空闲时把常用页面 chunk 预先拉下来（Vite 会复用
 * 路由跳转所需的同一分包），菜单切换即可即时渲染；SW 也会顺带缓存。
 */
function preloadPageChunks() {
  // 固定短延迟而非 idle 回调：用户可能在 idle 触发前就点菜单，预载必须赶在第一次切换前。
  setTimeout(() => {
    void Promise.allSettled([
      import("./pages/overview/index.vue"),
      import("./pages/activities/index.vue"),
      import("./pages/sync/index.vue"),
      import("./pages/jobs/index.vue"),
      import("./pages/settings/index.vue"),
      import("./pages/activity/index.vue"),
      import("./pages/map/index.vue"),
    ]);
  }, 300);
}
// #endif
</script>
<style lang="scss">
@use './styles/app.scss';
</style>
