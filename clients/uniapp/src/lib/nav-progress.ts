import { ref } from "vue";

/** 页面切换（含路由分包下载、navigateTo/Back）期间的全局进度提示。 */
export const navigating = ref(false);
let fallback: ReturnType<typeof setTimeout> | null = null;

export function beginNavigation() {
  navigating.value = true;
  if (fallback) clearTimeout(fallback);
  fallback = setTimeout(() => {
    fallback = null;
    navigating.value = false;
  }, 8000);
}

export function endNavigation() {
  navigating.value = false;
  if (fallback) {
    clearTimeout(fallback);
    fallback = null;
  }
}
