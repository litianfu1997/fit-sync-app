<script setup lang="ts">
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import AppShell from "../../components/AppShell.vue";
import RewardSupport from "../../components/RewardSupport.vue";
import { theme, setThemeMode, syncPageTheme, type ThemeMode } from "../../lib/theme";
import {
  api,
  bindWechat,
  changePassword as changePasswordApi,
  clearSession,
  confirm,
  go,
  initialize,
  messageOf,
  patchSessionUser,
  session,
  setPassword as setPasswordApi,
  toast,
  updateProfile,
} from "../../lib/api";
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
const bindingWechat = ref(false),
  bindError = ref(""),
  bindSuccess = ref("");

const hasPassword = computed(() => !!session.user?.hasPassword);
const hasWechat = computed(() => !!session.user?.hasWechat);
const wechatConfigured = computed(() => !!session.config?.wechatLoginEnabled);
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
    if (name.length < 3 || name.length > 32)
      return (pwdError.value = "用户名需为 3–32 个字符");
    if (!/^[A-Za-z0-9_\u4e00-\u9fff-]+$/.test(name))
      return (pwdError.value = "用户名只能包含中英文、数字、下划线或连字符");
  } else if (!pwdOld.value) return (pwdError.value = "请输入原密码");
  savingPassword.value = true;
  try {
    if (hasPassword.value) {
      await changePasswordApi(pwdOld.value, pwd);
      pwdSuccess.value = "密码已修改，其他设备上的会话不受影响。";
    } else {
      applyUser(await setPasswordApi(pwdUsername.value.trim(), pwd));
      pwdSuccess.value =
        "已设置用户名与密码，现在可在网页和小程序用此账号登录。";
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
onShow(() => {
  syncPageTheme();
  void initialize();
  profileName.value = session.user?.displayName || "";
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
            ><text class="muted">{{
              session.token
                ? session.user?.username
                  ? "账号 @" + session.user.username
                  : "尚未设置登录用户名"
                : "前往概览登录后管理账号与同步"
            }}</text></view
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
                >用户名密码账号在网页和小程序通用；绑定微信后可一键登录同一账号。</text
              ></view
            ><text class="tag" :class="hasPassword ? 'success' : 'amber'">{{
              hasPassword ? "已设密码" : "仅微信登录"
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

          <view class="form-panel"
            ><text class="section-title">{{
              hasPassword ? "修改密码" : "设置登录用户名与密码"
            }}</text
            ><text class="muted">{{
              hasPassword
                ? "修改后，其他已登录设备仍保持在线；请牢记新密码。"
                : "设置后即可在网页和小程序用同一账号登录，不依赖微信。"
            }}</text
            ><button class="btn primary" @click="openPasswordModal">
              {{ hasPassword ? "修改密码" : "设置用户名与密码" }}
            </button
            ><text v-if="pwdSuccess" class="success-text">{{
              pwdSuccess
            }}</text
            ></view
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
              hasPassword ? "修改密码" : "设置登录用户名与密码"
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
              : "设置后即可在网页和小程序用同一账号登录，不依赖微信。"
          }}</text
          ><view v-if="!hasPassword" class="form-field"
            ><text class="field-label">用户名</text
            ><input
              v-model="pwdUsername"
              class="input"
              maxlength="32"
              placeholder="3–32 位中英文、数字、_ 或 -"
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
    ></AppShell
  >
</template>
