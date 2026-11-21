<script setup lang="ts">
import { computed, onUnmounted, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import AppShell from "../../components/AppShell.vue";
import RewardSupport from "../../components/RewardSupport.vue";
import { theme, setThemeMode, syncPageTheme, type ThemeMode } from "../../lib/theme";
import {
  api,
  bindEmail as bindEmailApi,
  bindWechat,
  changePassword as changePasswordApi,
  clearSession,
  confirm,
  deleteAccount,
  go,
  initialize,
  messageOf,
  patchSessionUser,
  sendEmailCode,
  session,
  setPassword as setPasswordApi,
  startGoogleOAuth,
  toast,
  updateProfile,
} from "../../lib/api";
import { invalidateAll } from "../../lib/data-cache";
const EMAIL_RE = /^[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}$/i;
const themeOptions: { value: ThemeMode; label: string }[] = [
  { value: "system", label: "跟随系统" },
  { value: "light", label: "浅色" },
  { value: "dark", label: "深色" },
];
const profileName = ref(""),
  savingProfile = ref(false),
  profileError = ref(""),
  profileSuccess = ref("");
const pwdUsername = ref(""),
  pwdOld = ref(""),
  pwdNew = ref(""),
  pwdConfirm = ref(""),
  savingPassword = ref(false),
  pwdError = ref(""),
  pwdSuccess = ref(""),
  pwdModal = ref(false);
const emailModal = ref(false),
  bindEmailAddr = ref(""),
  bindEmailCode = ref(""),
  sendingEmailCode = ref(false),
  emailCooldown = ref(0),
  bindingEmail = ref(false),
  emailError = ref(""),
  emailSuccess = ref("");
const bindingWechat = ref(false),
  bindError = ref(""),
  bindSuccess = ref("");
const bindingGoogle = ref(false),
  googleBindError = ref(""),
  googleBindSuccess = ref("");
const deleteModal = ref(false),
  deletingAccount = ref(false),
  deleteError = ref("");
let emailCooldownTimer: ReturnType<typeof setInterval> | null = null;

const hasPassword = computed(() => !!session.user?.hasPassword);
const hasEmail = computed(() => !!session.user?.hasEmail);
const hasGoogle = computed(() => !!session.user?.hasGoogle);
const hasWechat = computed(() => !!session.user?.hasWechat);
const emailAuthEnabled = computed(() => !!session.config?.emailAuthEnabled);
const googleLoginEnabled = computed(() => !!session.config?.googleLoginEnabled);
const wechatConfigured = computed(() => !!session.config?.wechatLoginEnabled);
const accountIdentityText = computed(() => {
  if (!session.token) return "前往概览登录后管理账号与同步";
  const parts: string[] = [];
  if (session.user?.email) parts.push(session.user.email);
  if (session.user?.username) parts.push("@" + session.user.username);
  return parts.length ? parts.join(" · ") : "尚未绑定邮箱或用户名";
});
const authStatusBadge = computed(() => {
  if (hasPassword.value) return "已设密码";
  if (hasGoogle.value) return "Google 登录";
  if (hasEmail.value) return "已绑邮箱";
  return "仅微信登录";
});

onUnmounted(() => {
  if (emailCooldownTimer) {
    clearInterval(emailCooldownTimer);
    emailCooldownTimer = null;
  }
});

function applyUser(user: typeof session.user) {
  if (!user) return;
  patchSessionUser(user);
  profileName.value = user.displayName || "";
}
async function saveProfile() {
  const name = profileName.value.trim();
  if (!name) return (profileError.value = "请输入昵称");
  savingProfile.value = true;
  profileError.value = "";
  profileSuccess.value = "";
  try {
    applyUser(await updateProfile(name));
    profileSuccess.value = "昵称已更新。";
    toast("资料已保存");
  } catch (reason) {
    profileError.value = messageOf(reason);
  } finally {
    savingProfile.value = false;
  }
}
function openEmailModal() {
  emailError.value = "";
  emailSuccess.value = "";
  bindEmailAddr.value = "";
  bindEmailCode.value = "";
  emailModal.value = true;
}
function closeEmailModal() {
  if (bindingEmail.value) return;
  emailError.value = "";
  emailModal.value = false;
}
function startEmailCooldown() {
  if (emailCooldownTimer) clearInterval(emailCooldownTimer);
  emailCooldown.value = 60;
  emailCooldownTimer = setInterval(() => {
    if (emailCooldown.value <= 1) {
      emailCooldown.value = 0;
      if (emailCooldownTimer) {
        clearInterval(emailCooldownTimer);
        emailCooldownTimer = null;
      }
      return;
    }
    emailCooldown.value -= 1;
  }, 1000);
}
async function requestBindEmailCode() {
  emailError.value = "";
  const addr = bindEmailAddr.value.trim().toLowerCase();
  if (!addr) return (emailError.value = "请输入邮箱地址");
  if (addr.length > 160 || !EMAIL_RE.test(addr))
    return (emailError.value = "邮箱格式不正确");
  sendingEmailCode.value = true;
  try {
    await sendEmailCode(addr, "BIND");
    startEmailCooldown();
    toast("验证码已发送至邮箱，请查收");
  } catch (reason) {
    emailError.value = messageOf(reason);
  } finally {
    sendingEmailCode.value = false;
  }
}
async function submitBindEmail() {
  emailError.value = "";
  const addr = bindEmailAddr.value.trim().toLowerCase();
  const code = bindEmailCode.value.trim();
  if (!addr) return (emailError.value = "请输入邮箱地址");
  if (addr.length > 160 || !EMAIL_RE.test(addr))
    return (emailError.value = "邮箱格式不正确");
  if (!/^\d{6}$/.test(code))
    return (emailError.value = "请输入 6 位数字验证码");
  bindingEmail.value = true;
  try {
    applyUser(await bindEmailApi(addr, code));
    emailModal.value = false;
    bindEmailAddr.value = "";
    bindEmailCode.value = "";
    emailSuccess.value = "邮箱已绑定，现在可用邮箱登录并自动关联同邮箱 Google 账号。";
    toast("邮箱绑定成功");
  } catch (reason) {
    emailError.value = messageOf(reason);
  } finally {
    bindingEmail.value = false;
  }
}
async function bindGoogle() {
  googleBindError.value = "";
  googleBindSuccess.value = "";
  bindingGoogle.value = true;
  try {
    await startGoogleOAuth("bind", "/pages/settings/index");
  } catch (reason) {
    googleBindError.value = messageOf(reason);
    bindingGoogle.value = false;
  }
}
function openPasswordModal() {
  pwdError.value = "";
  pwdSuccess.value = "";
  pwdUsername.value = "";
  pwdOld.value = "";
  pwdNew.value = "";
  pwdConfirm.value = "";
  pwdModal.value = true;
}
function closePasswordModal() {
  if (savingPassword.value) return;
  pwdError.value = "";
  pwdModal.value = false;
}
async function savePassword() {
  pwdError.value = "";
  pwdSuccess.value = "";
  const pwd = pwdNew.value;
  if (pwd.length < 8 || pwd.length > 128)
    return (pwdError.value = "密码需为 8–128 个字符");
  if (pwd !== pwdConfirm.value) return (pwdError.value = "两次输入的密码不一致");
  if (!hasPassword.value) {
    const name = pwdUsername.value.trim();
    if (name || !hasEmail.value) {
      if (name.length < 3 || name.length > 32)
        return (pwdError.value = "用户名需为 3–32 个字符");
      if (!/^[A-Za-z0-9_\u4e00-\u9fff-]+$/.test(name))
        return (pwdError.value = "用户名只能包含中英文、数字、下划线或连字符");
    }
  } else if (!pwdOld.value) return (pwdError.value = "请输入原密码");
  savingPassword.value = true;
  try {
    if (hasPassword.value) {
      await changePasswordApi(pwdOld.value, pwd);
      pwdSuccess.value = "密码已修改，其他设备上的会话不受影响。";
    } else {
      applyUser(await setPasswordApi(pwdUsername.value.trim(), pwd));
      pwdSuccess.value = hasEmail.value
        ? "已设置登录密码，现在可使用邮箱或用户名加密码登录。"
        : "已设置用户名与密码，现在可在网页和小程序用此账号登录。";
    }
    pwdUsername.value = "";
    pwdOld.value = "";
    pwdNew.value = "";
    pwdConfirm.value = "";
    pwdModal.value = false;
    toast("账号安全信息已保存");
  } catch (reason) {
    pwdError.value = messageOf(reason);
  } finally {
    savingPassword.value = false;
  }
}
async function bind() {
  bindError.value = "";
  bindSuccess.value = "";
  bindingWechat.value = true;
  try {
    applyUser(await bindWechat());
    bindSuccess.value = "微信已绑定，可用微信一键登录此账号。";
    toast("微信绑定成功");
  } catch (reason) {
    bindError.value = messageOf(reason);
  } finally {
    bindingWechat.value = false;
  }
}
onShow(async () => {
  syncPageTheme();
  await initialize();
  profileName.value = session.user?.displayName || "";
  if (session.googleBindSuccess) {
    session.googleBindSuccess = false;
    googleBindSuccess.value = "Google 账号已绑定，可在网页端一键登录。";
    toast("Google 账号绑定成功");
  }
  if (session.authNotice && session.token) {
    googleBindError.value = session.authNotice;
    session.authNotice = "";
  }
});
async function logout() {
  if (
    !(await confirm(
      "退出登录",
      "退出将清除本机登录凭证，云端已授权的同步规则会继续执行。需要停止同步时，请先关闭规则。",
    ))
  )
    return;
  try {
    await api("/auth/logout", "POST");
  } catch {
    toast("本机登录已清除；网络恢复后云端会话会按有效期失效。");
  } finally {
    clearSession();
    go("overview");
  }
}
function openDeleteModal() {
  deleteError.value = "";
  deleteModal.value = true;
}
function closeDeleteModal() {
  if (deletingAccount.value) return;
  deleteError.value = "";
  deleteModal.value = false;
}
async function submitDeleteAccount() {
  if (deletingAccount.value) return;
  deletingAccount.value = true;
  deleteError.value = "";
  try {
    await deleteAccount();
    invalidateAll();
    deleteModal.value = false;
    toast("账号及云端数据已永久删除");
    go("overview");
  } catch (reason) {
    deleteError.value = messageOf(reason);
  } finally {
    deletingAccount.value = false;
  }
}
</script>
<template>
  <AppShell
    active="settings"
    title="设置"
    subtitle="管理外观与账号"
    ><view class="settings-columns"
      ><view
        ><view class="card profile-card"
          ><view class="avatar">{{
            (session.user?.displayName || "骑").slice(0, 1)
          }}</view
          ><view
            ><text class="section-title">{{
              session.user?.displayName ||
              (session.token ? "我的骑行空间" : "尚未登录")
            }}</text
            ><text class="muted">{{ accountIdentityText }}</text></view
          ><button
            v-if="session.token"
            class="text-button danger"
            @click="logout"
          >
            退出登录</button
          ><button v-else class="btn secondary small" @click="go('overview')">
            前往登录
          </button></view
        >

        <view v-if="session.token" class="card account-card"
          ><view class="card-heading"
            ><view
              ><text class="section-title">账号与登录方式</text
              ><text class="muted"
                >支持邮箱/用户名密码、Google（网页端）与微信（小程序）登录同一 FitSync 账号。</text
              ></view
            ><text class="tag" :class="hasPassword ? 'success' : 'amber'">{{
              authStatusBadge
            }}</text></view
          >
          <view class="form-field"
            ><text class="field-label">昵称</text
            ><input
              v-model="profileName"
              class="input"
              maxlength="24"
              placeholder="展示给你的称呼"
              :disabled="savingProfile"
              @confirm="saveProfile" /><text class="tiny muted"
              >昵称仅用于展示，不用于登录。</text
            ></view
          ><text v-if="profileError" class="error-text">{{
            profileError
          }}</text
          ><text v-if="profileSuccess" class="success-text">{{
            profileSuccess
          }}</text
          ><button
            class="btn secondary"
            :loading="savingProfile"
            :disabled="savingProfile"
            @click="saveProfile"
          >
            保存昵称
          </button>

          <!-- #ifdef MP-WEIXIN -->
          <view v-if="wechatConfigured && !hasWechat" class="form-panel"
            ><text class="section-title">绑定微信</text
            ><text class="muted"
              >绑定后可用微信一键登录当前账号，活动与规则保持不变。</text
            ><text v-if="bindError" class="error-text">{{ bindError }}</text
            ><text v-if="bindSuccess" class="success-text">{{
              bindSuccess
            }}</text
            ><button
              class="btn primary"
              :loading="bindingWechat"
              :disabled="bindingWechat"
              @click="bind"
            >
              绑定当前微信
            </button></view
          >
          <view v-else-if="hasWechat" class="info-notice notice"
            ><text>当前账号已绑定微信，可在小程序使用一键登录。</text></view
          >
          <!-- #endif -->

          <view v-if="hasEmail" class="info-notice notice"
            ><text
              >已绑定邮箱：{{ session.user?.email }}，可用于验证码或密码登录，并关联同邮箱 Google 账号。</text
            ></view
          >
          <view v-else-if="emailAuthEnabled" class="form-panel"
            ><text class="section-title">绑定邮箱</text
            ><text class="muted"
              >绑定后可用邮箱与密码登录，并与同邮箱 Google 登录自动关联。</text
            ><button class="btn primary" @click="openEmailModal">
              绑定邮箱
            </button></view
          >
          <text v-if="emailSuccess" class="success-text">{{ emailSuccess }}</text>

          <!-- #ifdef H5 -->
          <view v-if="googleLoginEnabled && !hasGoogle" class="form-panel"
            ><text class="section-title">绑定 Google 账号</text
            ><text class="muted"
              >绑定后可在网页端使用 Google 一键登录当前账号，活动与同步规则保持不变。</text
            ><text v-if="googleBindError" class="error-text">{{
              googleBindError
            }}</text
            ><text v-if="googleBindSuccess" class="success-text">{{
              googleBindSuccess
            }}</text
            ><button
              class="btn secondary"
              :loading="bindingGoogle"
              :disabled="bindingGoogle"
              @click="bindGoogle"
            >
              <image class="oauth-btn-icon" src="/static/google.svg" />
              <text>绑定 Google 账号</text>
            </button></view
          >
          <view v-else-if="hasGoogle" class="info-notice notice"
            ><text>{{
              googleBindSuccess || "当前账号已绑定 Google，可在网页端一键登录。"
            }}</text></view
          >
          <!-- #endif -->

          <view class="form-panel"
            ><text class="section-title">{{
              hasPassword
                ? "修改密码"
                : hasEmail
                  ? "设置登录密码"
                  : "设置登录用户名与密码"
            }}</text
            ><text class="muted">{{
              hasPassword
                ? "修改后，其他已登录设备仍保持在线；请牢记新密码。"
                : hasEmail
                  ? "设置密码后即可直接使用已绑定邮箱与密码登录（用户名可选填）。"
                  : "设置后即可在网页和小程序用同一账号登录，不依赖微信。"
            }}</text
            ><button class="btn primary" @click="openPasswordModal">
              {{
                hasPassword
                  ? "修改密码"
                  : hasEmail
                    ? "设置登录密码"
                    : "设置用户名与密码"
              }}
            </button
            ><text v-if="pwdSuccess" class="success-text">{{
              pwdSuccess
            }}</text
            ></view
          >

          <view class="form-panel"
            ><text class="section-title danger">注销并删除账号</text
            ><text class="muted"
              >永久删除当前 FitSync 账号，以及云端保存的活动记录、原始 FIT 文件、平台连接、同步规则与会话。此操作不可恢复。</text
            ><button class="btn secondary danger" @click="openDeleteModal">
              删除我的账号与数据
            </button></view
          >
        </view>

        <view class="card appearance-card"
          ><text class="section-title">外观</text
          ><text class="muted">默认跟随系统，使用与安卓端一致的明暗配色。</text><view class="appearance-options"
            ><button
              v-for="option in themeOptions"
              :key="option.value"
              class="appearance-option"
              :class="{ checked: theme.mode === option.value }"
              :aria-pressed="theme.mode === option.value"
              @click="setThemeMode(option.value)"
            >{{ option.label }}</button></view></view
        >
        <RewardSupport /></view
      ><view class="card about-card"
        ><text class="section-title">关于 FitSync</text
        ><text class="muted"
          >让分散在运动平台的骑行记录，汇聚成属于你的完整旅程。</text
        ><view class="about-item"
          ><text class="item-title">免费使用</text
          ><text class="muted"
            >通过广告支持服务运营。未开通广告位时不会展示占位广告。</text
          ></view
        ><view class="about-item"
          ><text class="item-title">明确授权</text
          ><text class="muted"
            >仅在你连接账号或开启规则后执行同步。可随时解除连接或关闭规则。</text
          ></view
        ><view class="about-item"
          ><text class="item-title">凭据保护</text
          ><text class="muted"
            >FitSync 账号密码只保存加盐散列；平台凭据由服务端密钥加密存储，前端与日志均不获得明文。</text
          ></view
        ><view class="version-line"
          ><text>FitSync Cloud</text><text>0.1.0 · 预览版</text></view
        ></view
      ></view
      ><view
        v-if="pwdModal"
        class="modal-mask"
        @click="closePasswordModal"
        @touchmove.stop
        ><view class="modal-card" @click.stop
          ><view class="modal-head"
            ><text class="section-title">{{
              hasPassword
                ? "修改密码"
                : hasEmail
                  ? "设置登录密码"
                  : "设置登录用户名与密码"
            }}</text
            ><button
              class="text-button"
              :disabled="savingPassword"
              @click="closePasswordModal"
            >
              关闭
            </button></view
          ><text class="muted">{{
            hasPassword
              ? "输入原密码确认身份；修改后其他已登录设备仍保持在线。"
              : hasEmail
                ? "设置密码后即可用已绑定的邮箱登录，也可顺便补充用户名。"
                : "设置后即可在网页和小程序用同一账号登录，不依赖微信。"
          }}</text
          ><view v-if="!hasPassword" class="form-field"
            ><text class="field-label">{{
              hasEmail ? "用户名（选填）" : "用户名"
            }}</text
            ><input
              v-model="pwdUsername"
              class="input"
              maxlength="32"
              :placeholder="
                hasEmail
                  ? '留空则直接使用绑定邮箱登录'
                  : '3–32 位中英文、数字、_ 或 -'
              "
              :disabled="savingPassword" /></view
          ><view v-if="hasPassword" class="form-field"
            ><text class="field-label">原密码</text
            ><input
              v-model="pwdOld"
              class="input"
              type="password"
              password
              maxlength="128"
              placeholder="当前密码"
              :disabled="savingPassword" /></view
          ><view class="form-field"
            ><text class="field-label">{{
              hasPassword ? "新密码" : "密码"
            }}</text
            ><input
              v-model="pwdNew"
              class="input"
              type="password"
              password
              maxlength="128"
              placeholder="至少 8 位"
              :disabled="savingPassword" /></view
          ><view class="form-field"
            ><text class="field-label">确认密码</text
            ><input
              v-model="pwdConfirm"
              class="input"
              type="password"
              password
              maxlength="128"
              placeholder="再次输入"
              :disabled="savingPassword"
              @confirm="savePassword" /></view
          ><text v-if="pwdError" class="error-text">{{ pwdError }}</text
          ><view class="modal-actions"
            ><button
              class="btn secondary"
              :disabled="savingPassword"
              @click="closePasswordModal"
            >
              取消
            </button
            ><button
              class="btn primary"
              :loading="savingPassword"
              :disabled="savingPassword"
              @click="savePassword"
            >
              {{ hasPassword ? "确认修改" : "设置并启用" }}
            </button></view
          ></view
        ></view
      >
      <view
        v-if="emailModal"
        class="modal-mask"
        @click="closeEmailModal"
        @touchmove.stop
        ><view class="modal-card" @click.stop
          ><view class="modal-head"
            ><text class="section-title">绑定邮箱</text
            ><button
              class="text-button"
              :disabled="bindingEmail"
              @click="closeEmailModal"
            >
              关闭
            </button></view
          ><text class="muted"
            >绑定后可用邮箱与密码登录当前账号，并可自动关联同邮箱 Google 账号。</text
          ><view class="form-field"
            ><text class="field-label">邮箱地址</text
            ><input
              v-model="bindEmailAddr"
              class="input"
              maxlength="160"
              placeholder="name@example.com"
              :disabled="bindingEmail || sendingEmailCode" /></view
          ><view class="form-field"
            ><text class="field-label">邮箱验证码</text
            ><view class="otp-input-row"
              ><input
                v-model="bindEmailCode"
                class="input"
                type="number"
                maxlength="6"
                placeholder="6 位数字验证码"
                :disabled="bindingEmail"
                @confirm="submitBindEmail" /><button
                class="btn secondary otp-send-btn"
                :loading="sendingEmailCode"
                :disabled="bindingEmail || sendingEmailCode || emailCooldown > 0"
                @click="requestBindEmailCode"
              >
                {{
                  emailCooldown > 0 ? `${emailCooldown}s 后重试` : "获取验证码"
                }}
              </button></view
            ></view
          ><text v-if="emailError" class="error-text">{{ emailError }}</text
          ><view class="modal-actions"
            ><button
              class="btn secondary"
              :disabled="bindingEmail"
              @click="closeEmailModal"
            >
              取消
            </button>
            ><button
              class="btn primary"
              :loading="bindingEmail"
              :disabled="bindingEmail"
              @click="submitBindEmail"
            >
              确认绑定
            </button></view
          ></view
        ></view
      >
      <view
        v-if="deleteModal"
        class="modal-mask"
        @click="closeDeleteModal"
        @touchmove.stop
        ><view class="modal-card" @click.stop
          ><view class="modal-head"
            ><text class="section-title danger">确认永久删除账号</text
            ><button
              class="text-button"
              :disabled="deletingAccount"
              @click="closeDeleteModal"
            >
              关闭
            </button></view
          ><text class="muted"
            >删除后，你在 FitSync 云端的全部活动、原始 FIT 文件、已连接的平台凭据与自动同步规则都会被立即清除且无法恢复。已同步到第三方平台的活动不受影响。</text
          ><text v-if="deleteError" class="error-text">{{ deleteError }}</text
          ><view class="modal-actions"
            ><button
              class="btn secondary"
              :disabled="deletingAccount"
              @click="closeDeleteModal"
            >
              取消
            </button>
            <button
              class="btn primary"
              :loading="deletingAccount"
              :disabled="deletingAccount"
              @click="submitDeleteAccount"
            >
              确认永久删除
            </button></view
          ></view
        ></view
      >
    </AppShell
  >
</template>
