<script setup lang="ts">
import { computed, ref } from "vue";
import {
  initialize,
  loginAsDev,
  loginWithPassword,
  loginWithWechat,
  messageOf,
  register,
  session,
} from "../lib/api";
import WebLogin from "./WebLogin.vue";
import { theme } from "../lib/theme";
const emit = defineEmits<{ ready: [] }>();
const passwordEnabled = computed(
  () => session.config?.passwordLoginEnabled !== false,
);
const wechatEnabled = computed(() => !!session.config?.wechatLoginEnabled);
const webEnabled = computed(
  () => session.config?.webLoginEnabled ?? wechatEnabled.value,
);
const mode = ref<"login" | "register">("login");
const busy = ref(false),
  error = ref(""),
  username = ref(""),
  password = ref(""),
  confirmPassword = ref(""),
  displayName = ref("");
function switchMode(next: "login" | "register") {
  if (busy.value) return;
  mode.value = next;
  error.value = "";
  password.value = "";
  confirmPassword.value = "";
}
async function submitPassword() {
  const name = username.value.trim(),
    pwd = password.value;
  if (!name) return (error.value = "请输入用户名");
  if (!pwd) return (error.value = "请输入密码");
  if (name.length < 3 || name.length > 32)
    return (error.value = "用户名需为 3–32 个字符");
  if (!/^[A-Za-z0-9_\u4e00-\u9fff-]+$/.test(name))
    return (error.value = "用户名只能包含中英文、数字、下划线或连字符");
  if (pwd.length < 8 || pwd.length > 128)
    return (error.value = "密码需为 8–128 个字符");
  if (mode.value === "register" && pwd !== confirmPassword.value)
    return (error.value = "两次输入的密码不一致");
  busy.value = true;
  error.value = "";
  try {
    if (mode.value === "register")
      await register(name, pwd, displayName.value.trim());
    else await loginWithPassword(name, pwd);
    password.value = "";
    confirmPassword.value = "";
    emit("ready");
  } catch (reason) {
    error.value = messageOf(reason);
  } finally {
    busy.value = false;
  }
}
async function signIn(kind: "wechat" | "dev") {
  busy.value = true;
  error.value = "";
  try {
    if (kind === "wechat") await loginWithWechat();
    else await loginAsDev("");
    emit("ready");
  } catch (reason) {
    error.value = messageOf(reason);
  } finally {
    busy.value = false;
  }
}
async function retry() {
  await initialize(true);
  if (session.config && session.token) emit("ready");
}
</script>
<template>
  <view v-if="session.connecting && !session.config" class="card empty-state"
    ><view class="spinner" /><text class="empty-title">正在连接</text></view
  >
  <view v-else-if="session.configError" class="card empty-state"
    ><image class="empty-icon" :src="`/static/cloud${theme.dark ? '-dark' : ''}.svg`" /><text
      class="empty-title"
      >连接失败</text
    ><text class="muted">{{ session.configError }}</text
    ><view class="button-row"
      ><button
        class="btn primary"
        :disabled="session.connecting"
        @click="retry"
      >
        重试</button
      ></view
    ></view
  >
  <view v-else-if="!session.token" class="login-card card"
    ><view class="login-art"
      ><image class="login-logo" src="/static/logo.png" />
      <text class="login-title">FitSync</text>
      <text class="muted">跨平台 FIT 活动管理与自动同步。</text></view
    ><view class="login-form"
      ><view v-if="passwordEnabled" class="password-login"
        ><view class="auth-tabs"
          ><button
            class="auth-tab"
            :class="{ selected: mode === 'login' }"
            :aria-pressed="mode === 'login'"
            @click="switchMode('login')"
          >
            登录</button
          ><button
            class="auth-tab"
            :class="{ selected: mode === 'register' }"
            :aria-pressed="mode === 'register'"
            @click="switchMode('register')"
          >
            注册</button
          ></view
        ><view class="form-field"
          ><text class="field-label">用户名</text
          ><input
            v-model="username"
            class="input"
            maxlength="32"
            placeholder="输入用户名"
            :disabled="busy"
            @confirm="submitPassword" /></view
        ><view v-if="mode === 'register'" class="form-field"
          ><text class="field-label">昵称</text
          ><input
            v-model="displayName"
            class="input"
            maxlength="24"
            placeholder="选填"
            :disabled="busy" /></view
        ><view class="form-field"
          ><text class="field-label">密码</text
          ><input
            v-model="password"
            class="input"
            type="password"
            password
            maxlength="128"
            :placeholder="mode === 'register' ? '至少 8 位' : '输入密码'"
            :disabled="busy"
            @confirm="submitPassword" /></view
        ><view v-if="mode === 'register'" class="form-field"
          ><text class="field-label">确认密码</text
          ><input
            v-model="confirmPassword"
            class="input"
            type="password"
            password
            maxlength="128"
            placeholder="再次输入密码"
            :disabled="busy"
            @confirm="submitPassword" /></view
        ><button
          class="btn primary full"
          :loading="busy"
          :disabled="busy"
          @click="submitPassword"
        >
          {{ mode === "register" ? "注册并登录" : "登录" }}</button
        ></view
      >

      <!-- #ifdef MP-WEIXIN -->
      <button
        v-if="wechatEnabled"
        class="btn secondary full"
        :loading="busy"
        :disabled="busy"
        @click="signIn('wechat')"
      >
        微信一键登录
      </button>
      <!-- #endif -->

      <!-- #ifdef H5 -->
      <WebLogin v-if="webEnabled" @ready="emit('ready')" />
      <view v-if="session.config?.devLoginEnabled" class="dev-login"
        ><button
          class="btn secondary full"
          :loading="busy"
          :disabled="busy"
          @click="signIn('dev')"
        >
          本地开发体验
        </button></view
      >
      <!-- #endif -->

      <text v-if="error" class="error-text">{{ error }}</text></view
    ></view
  >
  <slot v-else />
</template>
