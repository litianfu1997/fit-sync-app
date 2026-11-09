<script setup lang="ts">
import { computed, ref } from "vue";
import {
  go,
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
  displayName = ref(""),
  devName = ref("");
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
    else await loginAsDev(devName.value);
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
    ><view class="spinner" /><text class="empty-title"
      >正在连接云服务</text
    ><text class="muted">首次连接可能需要几秒钟</text></view
  >
  <view v-else-if="session.configError" class="card empty-state"
    ><image class="empty-icon" :src="`/static/cloud${theme.dark ? '-dark' : ''}.svg`" /><text
      class="empty-title"
      >暂时无法连接云服务</text
    ><text class="muted">{{ session.configError }}</text
    ><view class="button-row"
      ><button
        class="btn primary"
        :disabled="session.connecting"
        @click="retry"
      >
        重新连接</button
      ><button class="btn secondary" @click="go('settings')">
        服务器设置
      </button></view
    ></view
  >
  <view v-else-if="!session.token" class="login-card card"
    ><view class="login-art"
      ><image class="login-logo" src="/static/logo.svg" />
      <text class="login-title">跨平台活动同步</text
      ><text class="muted"
        >集中管理活动，保留原始 FIT，按规则自动同步到运动平台。</text
      ><view class="login-feature"
        ><text class="small-dot" />FIT 原文件导入<text
          class="small-dot"
        />跨平台自动同步</view
      ></view
    ><view class="login-form"
      ><text class="section-title">登录 FitSync</text
      ><text class="muted">登录后连接运动账号，开始管理活动。</text>

      <view v-if="passwordEnabled" class="password-login"
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
            placeholder="3–32 位中英文、数字、_ 或 -"
            :disabled="busy"
            @confirm="submitPassword" /></view
        ><view v-if="mode === 'register'" class="form-field"
          ><text class="field-label">昵称（可选）</text
          ><input
            v-model="displayName"
            class="input"
            maxlength="24"
            placeholder="展示给你的称呼"
            :disabled="busy" /></view
        ><view class="form-field"
          ><text class="field-label">密码</text
          ><input
            v-model="password"
            class="input"
            type="password"
            password
            maxlength="128"
            placeholder="至少 8 位"
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
        ><text class="tiny muted"
          >一个 FitSync 账号可在网页和微信小程序通用；注册后可在设置里绑定微信或补设密码。</text
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
      <text v-else class="tiny muted"
        >微信一键登录尚未配置；可直接用用户名密码登录，登录后在设置里绑定微信。</text
      >
      <!-- #endif -->

      <!-- #ifdef H5 -->
      <WebLogin v-if="webEnabled" @ready="emit('ready')" />
      <view v-if="session.config?.devLoginEnabled" class="dev-login"
        ><text class="tag amber">仅限本地开发</text
        ><text class="muted"
          >开发登录每次新建独立用户，不用于生产或正式账号找回。</text
        ><input
          v-model="devName"
          class="input"
          maxlength="24"
          placeholder="你的称呼（可选）"
        /><button
          class="btn secondary full"
          :loading="busy"
          :disabled="busy"
          @click="signIn('dev')"
        >
          进入本地开发空间
        </button></view
      >
      <!-- #endif -->

      <text v-if="error" class="error-text">{{ error }}</text
      ><text class="login-note">仅在你明确授权后连接平台与自动同步。</text
      ><button class="text-button" @click="go('settings')">
        检查服务器设置
      </button></view
    ></view
  >
  <slot v-else />
</template>
