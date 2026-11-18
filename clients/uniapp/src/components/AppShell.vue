<script setup lang="ts">
import { computed } from "vue";
import { go, session } from "../lib/api";
import { theme, themeStyle } from "../lib/theme";
const props = defineProps<{ active: string; title: string; subtitle?: string }>();
const tabs = [
  { id: "overview", label: "概览", icon: "grid" },
  { id: "activities", label: "活动", icon: "activity" },
  { id: "sync", label: "同步", icon: "sync" },
  { id: "jobs", label: "任务", icon: "jobs" },
  { id: "settings", label: "设置", icon: "settings" },
];
/** 未登录时页面只展示登录入口，导航不应出现。 */
const signedIn = computed(() => !!session.token);
const navTabs = computed(() => (signedIn.value ? tabs : []));
const primaryTabs = computed(() =>
  signedIn.value
    ? tabs.filter((tab) => ["overview", "activities", "settings"].includes(tab.id))
    : [],
);
const workspaceTabs = computed(() =>
  signedIn.value
    ? tabs.filter((tab) => ["overview", "sync", "jobs"].includes(tab.id))
    : [],
);
const inWorkspace = computed(() => ["overview", "sync", "jobs"].includes(props.active));
const primaryActive = computed(() => inWorkspace.value ? "overview" : props.active);
const miniHeaderStyle: Record<string, string> = {};
// #ifdef MP-WEIXIN
const menu = uni.getMenuButtonBoundingClientRect();
const windowInfo = uni.getSystemInfoSync();
if (menu.height > 0) {
  miniHeaderStyle.paddingTop = `${menu.top}px`;
  miniHeaderStyle.paddingBottom = "8px";
  miniHeaderStyle.minHeight = `${menu.bottom + 8}px`;
  miniHeaderStyle.paddingRight = `${windowInfo.windowWidth - menu.left + 12}px`;
}
// #endif
function iconFor(icon: string, selected: boolean) {
  return `/static/${icon}${selected ? "-active" : ""}${theme.dark ? "-dark" : ""}.svg`;
}
</script>
<template>
  <view
    class="app-shell"
    :class="{
      'theme-dark': theme.dark,
      'guest-mode': !signedIn,
      'guest-auth': !signedIn && active !== 'settings',
    }"
    :style="themeStyle"
  >
    <view class="desktop-sidebar">
      <view class="brand"
        ><image class="brand-mark" src="/static/logo.svg" /><view
          ><text class="brand-name">FitSync</text
          ><text class="brand-caption">跨平台活动同步</text></view
        ></view
      >
      <button
        v-for="tab in navTabs"
        :key="tab.id"
        class="side-nav"
        :class="{ 'side-nav-active': active === tab.id }"
        @click="go(tab.id)"
      >
        <image class="nav-icon" :src="iconFor(tab.icon, active === tab.id)" /><text>{{
          tab.label
        }}</text
        ><text v-if="active === tab.id" class="nav-dot" />
      </button>
      <view class="sidebar-note"><text>云端自动同步 · 原始 FIT 保留</text></view>
    </view>
    <view class="main-wrap">
      <view class="mobile-brand" :style="miniHeaderStyle"
        ><image class="brand-mark" src="/static/logo.svg" /><text
          class="brand-name"
          >FitSync</text
        ><!-- #ifdef H5 --><text class="mobile-tag">云端同步</text><!-- #endif --></view
      >
      <view class="page-content"
        ><view class="page-heading"
          ><view
            ><text class="page-title">{{ title }}</text
            ><text v-if="subtitle" class="page-subtitle">{{
              subtitle
            }}</text></view
          ><slot name="action" /></view>
        <view v-if="inWorkspace && workspaceTabs.length" class="workspace-tabs">
          <button v-for="tab in workspaceTabs" :key="tab.id" :class="{ selected: active === tab.id }" @click="go(tab.id)">{{ tab.label }}</button>
        </view>
        <slot /></view>
      <view class="page-footer"
        ><text>FitSync · 跨平台活动同步</text
        ><text>免费使用 · 广告支持运营</text></view
      >
    </view>
    <view class="mobile-tabs"
      ><button
        v-for="tab in primaryTabs"
        :key="tab.id"
        :class="{ selected: primaryActive === tab.id }"
        @click="go(tab.id)"
      >
        <view class="mobile-tab-icon"><image :src="iconFor(tab.icon, primaryActive === tab.id)" /></view><text>{{ tab.label }}</text>
      </button></view
    >
  </view>
</template>
