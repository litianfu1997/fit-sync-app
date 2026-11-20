<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from "vue";
import {
  initialize,
  loginAsDev,
  loginWithEmailCode,
  loginWithPassword,
  loginWithWechat,
  messageOf,
  register,
  registerWithEmail,
  sendEmailCode,
  session,
  startGoogleOAuth,
  toast,
} from "../lib/api";
import { theme } from "../lib/theme";
import PageSkeleton from "./PageSkeleton.vue";

const emit = defineEmits<{ ready: [] }>();
const EMAIL_RE = /^[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}$/i;

const passwordEnabled = computed(
  () => session.config?.passwordLoginEnabled !== false,
);
const emailAuthEnabled = computed(() => !!session.config?.emailAuthEnabled);
const googleLoginEnabled = computed(() => !!session.config?.googleLoginEnabled);
const wechatEnabled = computed(() => !!session.config?.wechatLoginEnabled);

const mode = ref<"login" | "register">("login");
const registerByEmail = ref(true);
const busy = ref(false),
  sendingCode = ref(false),
  cooldown = ref(0),
  error = ref(""),
  account = ref(""),
  email = ref(""),
  emailCode = ref(""),
  username = ref(""),
  password = ref(""),
  confirmPassword = ref(""),
  displayName = ref("");

let cooldownTimer: ReturnType<typeof setInterval> | null = null;

watch(
  emailAuthEnabled,
  (enabled) => {
    registerByEmail.value = enabled;
  },
  { immediate: true },
);

onUnmounted(() => {
  if (cooldownTimer) {
    clearInterval(cooldownTimer);
    cooldownTimer = null;
  }
});

const useEmailRegister = computed(
  () => emailAuthEnabled.value && registerByEmail.value,
);
const loginByEmail = ref(false);
const useEmailLogin = computed(
  () => emailAuthEnabled.value && loginByEmail.value,
);
const showEmailFields = computed(
  () =>
    (mode.value === "login" && useEmailLogin.value) ||
    (mode.value === "register" && useEmailRegister.value),
);
const displayError = computed(() => error.value || session.authNotice);

function clearNotice() {
  error.value = "";
  session.authNotice = "";
}

function switchMode(next: "login" | "register") {
  if (busy.value) return;
  mode.value = next;
  clearNotice();
  password.value = "";
  confirmPassword.value = "";
  emailCode.value = "";
}

function toggleLoginKind() {
  if (busy.value || !emailAuthEnabled.value) return;
  clearNotice();
  password.value = "";
  emailCode.value = "";
  if (!loginByEmail.value) {
    if (!email.value && account.value.includes("@"))
      email.value = account.value.trim();
  } else if (!account.value && email.value) {
    account.value = email.value;
  }
  loginByEmail.value = !loginByEmail.value;
}

function toggleRegisterKind() {
  if (busy.value || !emailAuthEnabled.value) return;
  registerByEmail.value = !registerByEmail.value;
  clearNotice();
  password.value = "";
  confirmPassword.value = "";
}

function startCooldown() {
  if (cooldownTimer) clearInterval(cooldownTimer);
  cooldown.value = 60;
  cooldownTimer = setInterval(() => {
    if (cooldown.value <= 1) {
      cooldown.value = 0;
      if (cooldownTimer) {
        clearInterval(cooldownTimer);
        cooldownTimer = null;
      }
      return;
    }
    cooldown.value -= 1;
  }, 1000);
}

async function requestEmailCode() {
  clearNotice();
  const addr = email.value.trim().toLowerCase();
  if (!addr) return (error.value = "请输入邮箱地址");
  if (addr.length > 160 || !EMAIL_RE.test(addr))
    return (error.value = "邮箱格式不正确");
  sendingCode.value = true;
  try {
    await sendEmailCode(addr, mode.value === "login" ? "LOGIN" : "REGISTER");
    startCooldown();
    toast("验证码已发送至邮箱，请查收");
  } catch (reason) {
    error.value = messageOf(reason);
  } finally {
    sendingCode.value = false;
  }
}

async function submitPassword() {
  clearNotice();
  const pwd = password.value;

  if (mode.value === "login") {
    if (useEmailLogin.value) {
      const addr = email.value.trim().toLowerCase();
      const code = emailCode.value.trim();
      if (!addr) return (error.value = "请输入邮箱地址");
      if (addr.length > 160 || !EMAIL_RE.test(addr))
        return (error.value = "邮箱格式不正确");
      if (!/^\d{6}$/.test(code))
        return (error.value = "请输入 6 位数字验证码");
      busy.value = true;
      try {
        await loginWithEmailCode(addr, code);
        emailCode.value = "";
        emit("ready");
      } catch (reason) {
        error.value = messageOf(reason);
      } finally {
        busy.value = false;
      }
      return;
    }
    const acct = account.value.trim();
    if (!acct) return (error.value = "请输入邮箱或用户名");
    if (!pwd) return (error.value = "请输入密码");
    if (pwd.length < 8 || pwd.length > 128)
      return (error.value = "密码需为 8–128 个字符");
    busy.value = true;
    try {
      await loginWithPassword(acct, pwd);
      password.value = "";
      emit("ready");
    } catch (reason) {
      error.value = messageOf(reason);
    } finally {
      busy.value = false;
    }
    return;
  }

  if (useEmailRegister.value) {
    const addr = email.value.trim().toLowerCase();
    const code = emailCode.value.trim();
    if (!addr) return (error.value = "请输入邮箱地址");
    if (addr.length > 160 || !EMAIL_RE.test(addr))
      return (error.value = "邮箱格式不正确");
    if (!/^\d{6}$/.test(code))
      return (error.value = "请输入 6 位数字验证码");
    if (pwd.length < 8 || pwd.length > 128)
      return (error.value = "密码需为 8–128 个字符");
    if (pwd !== confirmPassword.value)
      return (error.value = "两次输入的密码不一致");
    busy.value = true;
    try {
      await registerWithEmail(addr, code, pwd, displayName.value.trim());
      password.value = "";
      confirmPassword.value = "";
      emailCode.value = "";
      emit("ready");
    } catch (reason) {
      error.value = messageOf(reason);
    } finally {
      busy.value = false;
    }
    return;
  }

  const name = username.value.trim();
  if (!name) return (error.value = "请输入用户名");
  if (!pwd) return (error.value = "请输入密码");
  if (name.length < 3 || name.length > 32)
    return (error.value = "用户名需为 3–32 个字符");
  if (!/^[A-Za-z0-9_\u4e00-\u9fff-]+$/.test(name))
    return (error.value = "用户名只能包含中英文、数字、下划线或连字符");
  if (pwd.length < 8 || pwd.length > 128)
    return (error.value = "密码需为 8–128 个字符");
  if (pwd !== confirmPassword.value)
    return (error.value = "两次输入的密码不一致");
  busy.value = true;
  try {
    await register(name, pwd, displayName.value.trim());
    password.value = "";
    confirmPassword.value = "";
    emit("ready");
  } catch (reason) {
    error.value = messageOf(reason);
  } finally {
    busy.value = false;
  }
}

async function signIn(kind: "wechat" | "dev" | "google") {
  clearNotice();
  busy.value = true;
  try {
    if (kind === "wechat") {
      await loginWithWechat();
      emit("ready");
    } else if (kind === "google") {
      await startGoogleOAuth("login");
      return;
    } else {
      await loginAsDev("");
      emit("ready");
    }
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
  <!-- 真正的首次访问（无任何本地快照）才走到这里：用骨架屏占位，
      与页面数据加载态视觉一致，避免孤零零的转圈。 -->
  <PageSkeleton v-if="session.connecting && !session.config" variant="list" />
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
        >

        <template v-if="mode === 'login' && !useEmailLogin">
          <view class="form-field"
            ><text class="field-label">邮箱或用户名</text
            ><input
              v-model="account"
              class="input"
              maxlength="160"
              placeholder="输入注册邮箱或用户名"
              :disabled="busy"
              @confirm="submitPassword" /></view
          >
        </template>

        <template v-else-if="showEmailFields">
          <view class="form-field"
            ><text class="field-label">邮箱</text
            ><input
              v-model="email"
              class="input"
              maxlength="160"
              placeholder="name@example.com"
              :disabled="busy || sendingCode" /></view
          ><view class="form-field"
            ><text class="field-label">邮箱验证码</text
            ><view class="otp-input-row"
              ><input
                v-model="emailCode"
                class="input"
                type="number"
                maxlength="6"
                placeholder="6 位数字验证码"
                :disabled="busy"
                @confirm="submitPassword" /><button
                class="btn secondary otp-send-btn"
                :loading="sendingCode"
                :disabled="busy || sendingCode || cooldown > 0"
                @click="requestEmailCode"
              >
                {{ cooldown > 0 ? `${cooldown}s 后重试` : "获取验证码" }}
              </button></view
            ></view
          >
        </template>

        <template v-else>
          <view class="form-field"
            ><text class="field-label">用户名</text
            ><input
              v-model="username"
              class="input"
              maxlength="32"
              placeholder="输入用户名"
              :disabled="busy"
              @confirm="submitPassword" /></view
          >
        </template>

        <view v-if="mode === 'register'" class="form-field"
          ><text class="field-label">昵称</text
          ><input
            v-model="displayName"
            class="input"
            maxlength="24"
            placeholder="选填"
            :disabled="busy" /></view
        >
        <view v-if="mode === 'register' || !useEmailLogin" class="form-field"
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
        ><view v-if="mode === 'login' && emailAuthEnabled" class="auth-subswitch"
          ><button class="text-button" :disabled="busy" @click="toggleLoginKind">
            {{ useEmailLogin ? "改用密码登录" : "改用邮箱验证码登录" }}
          </button></view
        ><view v-if="mode === 'register' && emailAuthEnabled" class="auth-subswitch"
          ><button class="text-button" :disabled="busy" @click="toggleRegisterKind">
            {{ useEmailRegister ? "改用用户名注册" : "改用邮箱验证码注册" }}
          </button></view
        ><button
          class="btn primary full"
          :loading="busy"
          :disabled="busy"
          @click="submitPassword"
        >
          {{ mode === "register" ? "注册并登录" : "登录" }}</button
        ></view
      >

      <!-- #ifdef H5 -->
      <template v-if="googleLoginEnabled">
        <view class="auth-divider"><text>或</text></view>
        <button
          class="btn secondary full"
          :loading="busy"
          :disabled="busy"
          @click="signIn('google')"
        >
          <image class="oauth-btn-icon" src="/static/google.svg" />
          <text>使用 Google 账号登录</text>
        </button>
      </template>
      <!-- #endif -->

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

      <text v-if="displayError" class="error-text">{{ displayError }}</text></view
    ></view
  >
  <slot v-else />
</template>
