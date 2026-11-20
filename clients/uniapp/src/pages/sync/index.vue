<script setup lang="ts">
import { computed, onUnmounted, ref } from "vue";
import { themeColors, theme } from "../../lib/theme";
import { onHide, onLoad, onShow } from "@dcloudio/uni-app";
import AppShell from "../../components/AppShell.vue";
import SessionGate from "../../components/SessionGate.vue";
import LoadState from "../../components/LoadState.vue";
import { api, apiBase, confirm, go, messageOf, session, toast } from "../../lib/api";
import { useLoad } from "../../lib/page";
import { cachedQuery } from "../../lib/query-cache";
import { fetchAccounts, invalidate } from "../../lib/data-cache";
import { useTabIntent } from "../../lib/tab-intent";
import {
  date,
  platformName,
  platformInitials,
  sportCategoryOptions,
  sportTypeLabels,
  sportTypesSummary,
  statusLabel,
} from "../../lib/format";
import { platformLoginHints } from "../../lib/web-session";
import type { Account, PlatformCode, Rule, SportCategory } from "../../lib/types";
const accounts = ref<Account[]>([]),
  rules = ref<Rule[]>([]),
  connecting = ref(false),
  showConnect = ref(false),
  connectPlatform = ref<PlatformCode>("COROS"),
  username = ref(""),
  password = ref(""),
  stravaClientId = ref(""),
  stravaClientSecret = ref(""),
  oauthChallengeId = ref(""),
  oauthAuthorizeUrl = ref(""),
  oauthPolling = ref(false),
  storeCredentials = ref(true),
  connectError = ref(""),
  pendingAccount = ref("");
let oauthPollTimer: ReturnType<typeof setInterval> | null = null;
const authMode = (code: PlatformCode) =>
  session.config?.platforms.find((p) => p.code === code)?.authMode || "UNAVAILABLE";
const isStravaOAuth = computed(() => authMode(connectPlatform.value) === "OAUTH");
const stravaOAuthReady = computed(() => !!session.config?.stravaOAuthConfigured);
const loginHint = computed(() => platformLoginHints[connectPlatform.value]);
const connectablePlatforms = computed(
  () =>
    session.config?.platforms.filter(
      (p) => p.authMode === "PASSWORD" || p.authMode === "WEB_SESSION" || p.authMode === "OAUTH",
    ) || [],
);
const source = ref<PlatformCode>("LOCAL"),
  destinationIds = ref<string[]>([]),
  syncSportTypes = ref<SportCategory[]>(["CYCLING", "RUNNING", "OTHER"]),
  syncSportTouched = ref(false),
  historyDays = ref("90"),
  syncing = ref(false),
  syncError = ref(""),
  syncMessage = ref("");
const activityIds = ref<string[]>([]);
const selectedMode = ref(false);
const CONNECT_PLATFORMS = ["COROS", "IGPSPORT", "ONELAP", "XINGZHE", "GARMIN", "STRAVA"];

function applyConnect(platform?: string) {
  showConnect.value = true;
  const requested = String(platform || "").toUpperCase() as PlatformCode;
  if (CONNECT_PLATFORMS.includes(requested)) connectPlatform.value = requested;
}
function applyActivityIds(raw: string) {
  selectedMode.value = true;
  try {
    activityIds.value = decodeURIComponent(raw)
      .split(",")
      .filter((id) => /^[a-f0-9-]{36}$/i.test(id))
      .slice(0, 100);
  } catch {
    syncError.value = "活动选择信息已失效，请返回活动列表重新选择";
  }
}
onLoad((query) => {
  if (query?.connect) applyConnect(String(query.connect));
  if (query?.stravaOAuth === "success") {
    toast("Strava 已连接");
  } else if (query?.stravaOAuth === "error") {
    showConnect.value = true;
    connectPlatform.value = "STRAVA";
    try {
      connectError.value = query.stravaOAuthMsg
        ? decodeURIComponent(String(query.stravaOAuthMsg))
        : "Strava 授权未完成，请重试";
    } catch {
      connectError.value = "Strava 授权未完成，请重试";
    }
  }
  if (query?.activityIds) applyActivityIds(String(query.activityIds));
});
useTabIntent("sync", (intent) => {
  if (intent.kind === "connect") applyConnect(intent.platform);
  else if (intent.kind === "activityIds") applyActivityIds(intent.ids.join(","));
});
onShow(() => {
  if (oauthChallengeId.value && !oauthPollTimer)
    startOAuthPoll(oauthChallengeId.value);
});
onHide(() => {
  clearOAuthPoll();
});
onUnmounted(() => {
  clearOAuthPoll();
});
const ruleSource = ref<PlatformCode>("LOCAL"),
  ruleDestination = ref(""),
  ruleSportTypes = ref<SportCategory[]>(["CYCLING", "RUNNING", "OTHER"]),
  ruleSportTouched = ref(false),
  includeHistory = ref(false),
  savingRule = ref(false),
  ruleBusy = ref(""),
  ruleError = ref("");
const supportedPlatforms = computed(
  () =>
    session.config?.platforms.filter(
      (p) => p.authMode === "PASSWORD" || p.authMode === "WEB_SESSION" || p.authMode === "OAUTH",
    ) || [],
);
const sourceOptions = computed(() => [
  { code: "LOCAL" as PlatformCode, name: "本地活动库（导入的 FIT）" },
  ...accounts.value.map((a) => ({
    code: a.platform,
    name: platformName(a.platform),
  })),
]);
const destinationAccounts = computed(() =>
  accounts.value.filter((a) => a.platform !== source.value),
);
const selectedDestinationAccounts = computed(() =>
  accounts.value.filter((a) => destinationIds.value.includes(a.id)),
);
const hasOnelapDestination = computed(() =>
  selectedDestinationAccounts.value.some((a) => a.platform === "ONELAP"),
);
const onlyOnelapDestination = computed(
  () =>
    selectedDestinationAccounts.value.length > 0 &&
    selectedDestinationAccounts.value.every((a) => a.platform === "ONELAP"),
);
const ruleDestinations = computed(() =>
  accounts.value.filter((a) => a.platform !== ruleSource.value),
);
const ruleDestinationIsOnelap = computed(
  () => accountPlatform(ruleDestination.value) === "ONELAP",
);
const selectedSource = computed(() =>
  sourceOptions.value.findIndex((p) => p.code === source.value),
);
const selectedRuleSource = computed(() =>
  sourceOptions.value.findIndex((p) => p.code === ruleSource.value),
);
const selectedRuleDestination = computed(() =>
  ruleDestinations.value.findIndex((a) => a.id === ruleDestination.value),
);
const ruleDestinationOptions = computed(() =>
  ruleDestinations.value.map((a) => ({
    id: a.id,
    label: `${platformName(a.platform)} · ${a.displayName}`,
  })),
);
const ruleDestinationLabel = computed(() => {
  const account = accounts.value.find((a) => a.id === ruleDestination.value);
  return account
    ? `${platformName(account.platform)} · ${account.displayName}`
    : "";
});
const ruleSportSummary = computed(() => {
  if (!ruleSportTypes.value.length) return "";
  const labels = sportTypeLabels(ruleSportTypes.value);
  return labels.length ? labels.join("、") : "全部类型";
});
function applyDefaultSyncSportTypes() {
  if (syncSportTouched.value) return;
  syncSportTypes.value = onlyOnelapDestination.value
    ? ["CYCLING"]
    : ["CYCLING", "RUNNING", "OTHER"];
}
function applyDefaultRuleSportTypes() {
  if (ruleSportTouched.value) return;
  ruleSportTypes.value = ruleDestinationIsOnelap.value
    ? ["CYCLING"]
    : ["CYCLING", "RUNNING", "OTHER"];
}
const { loading, refreshing, fatalError, staleError, reload } = useLoad(async (force) => {
  const [a, r] = await Promise.all([
    fetchAccounts(force),
    cachedQuery("rules", () => api<Rule[]>("/rules"), 30_000, force),
  ]);
  accounts.value = a;
  rules.value = r;
  destinationIds.value = destinationIds.value.filter((id) =>
    a.some((x) => x.id === id && x.platform !== source.value),
  );
  if (!ruleDestinations.value.some((x) => x.id === ruleDestination.value))
    ruleDestination.value = ruleDestinations.value[0]?.id || "";
  applyDefaultSyncSportTypes();
  applyDefaultRuleSportTypes();
}, { ttl: 60_000, scope: "accounts" });
function chooseSource(index: number) {
  source.value = sourceOptions.value[index].code;
  destinationIds.value = destinationIds.value.filter((id) =>
    destinationAccounts.value.some((a) => a.id === id),
  );
  applyDefaultSyncSportTypes();
}
function chooseRuleSource(index: number) {
  ruleSource.value = sourceOptions.value[index].code;
  ruleDestination.value = ruleDestinations.value[0]?.id || "";
  applyDefaultRuleSportTypes();
}
function chooseRuleDestination(index: number) {
  const next = ruleDestinations.value[index];
  if (!next) return;
  ruleDestination.value = next.id;
  applyDefaultRuleSportTypes();
}
function toggleDestination(id: string) {
  destinationIds.value = destinationIds.value.includes(id)
    ? destinationIds.value.filter((x) => x !== id)
    : [...destinationIds.value, id];
  applyDefaultSyncSportTypes();
}
function toggleSyncSportType(code: SportCategory) {
  syncSportTouched.value = true;
  syncSportTypes.value = syncSportTypes.value.includes(code)
    ? syncSportTypes.value.filter((x) => x !== code)
    : [...syncSportTypes.value, code];
}
function toggleRuleSportType(code: SportCategory) {
  ruleSportTouched.value = true;
  ruleSportTypes.value = ruleSportTypes.value.includes(code)
    ? ruleSportTypes.value.filter((x) => x !== code)
    : [...ruleSportTypes.value, code];
}
function clearOAuthPoll() {
  if (oauthPollTimer) {
    clearInterval(oauthPollTimer);
    oauthPollTimer = null;
  }
  oauthPolling.value = false;
}
function startOAuthPoll(challengeId: string) {
  clearOAuthPoll();
  oauthPolling.value = true;
  oauthPollTimer = setInterval(async () => {
    try {
      const result = await api<{ status: string; message?: string }>(
        `/accounts/oauth/strava/${challengeId}`,
      );
      if (result.status === "SUCCEEDED") {
        clearOAuthPoll();
        oauthChallengeId.value = "";
        oauthAuthorizeUrl.value = "";
        showConnect.value = false;
        toast("Strava 已连接");
        invalidate("accounts");
        invalidate("activity");
        await reload(true);
      } else if (result.status === "FAILED" || result.status === "EXPIRED") {
        clearOAuthPoll();
        connectError.value = result.message || "Strava 授权已过期，请重新发起";
      }
    } catch {
      // 单次轮询网络波动忽略，下一轮继续查询。
    }
  }, 2500);
}
function copyOAuthUrl() {
  if (!oauthAuthorizeUrl.value) return;
  uni.setClipboardData({
    data: oauthAuthorizeUrl.value,
    success: () => toast("已复制授权链接，请在浏览器中打开并完成授权"),
  });
}
async function startStravaOAuth() {
  connectError.value = "";
  if (
    !stravaOAuthReady.value &&
    (!stravaClientId.value.trim() || !stravaClientSecret.value.trim())
  ) {
    connectError.value = "请先填写 Strava Client ID 与客户端密钥";
    return;
  }
  connecting.value = true;
  try {
    const isH5 = typeof window !== "undefined";
    const base = apiBase();
    const absApi = /^https?:\/\//i.test(base)
      ? base
      : isH5
        ? new URL(base, window.location.origin).toString().replace(/\/+$/, "")
        : base;
    const redirectUri = `${absApi}/v1/accounts/oauth/strava/callback`;
    const returnUrl = isH5 ? `${window.location.origin}/#/pages/sync/index` : undefined;
    const result = await api<{ challengeId: string; authorizeUrl: string }>(
      "/accounts/oauth/strava/start",
      "POST",
      {
        ...(stravaOAuthReady.value
          ? {}
          : {
              clientId: stravaClientId.value.trim(),
              clientSecret: stravaClientSecret.value.trim(),
            }),
        redirectUri,
        ...(returnUrl ? { returnUrl } : {}),
      },
    );
    stravaClientSecret.value = "";
    if (isH5) {
      window.location.assign(result.authorizeUrl);
      return;
    }
    oauthChallengeId.value = result.challengeId;
    oauthAuthorizeUrl.value = result.authorizeUrl;
    startOAuthPoll(result.challengeId);
  } catch (reason) {
    connectError.value = messageOf(reason);
  } finally {
    connecting.value = false;
  }
}
async function connect() {
  connectError.value = "";
  if (isStravaOAuth.value) {
    await startStravaOAuth();
    return;
  }
  if (!username.value.trim() || !password.value) {
    connectError.value = "请填写平台账号与密码";
    return;
  }
  if (!supportedPlatforms.value.some((p) => p.code === connectPlatform.value)) {
    connectError.value = "该平台暂不支持云端账号授权";
    return;
  }
  connecting.value = true;
  try {
    await api<Account>(
      "/accounts",
      "POST",
      {
        platform: connectPlatform.value,
        username: username.value.trim(),
        password: password.value,
        storeCredentials: storeCredentials.value,
      },
      true,
      {},
      90000,
    );
    username.value = "";
    password.value = "";
    showConnect.value = false;
    toast("平台已连接");
    invalidate("accounts");
    invalidate("activity");
    await reload(true);
  } catch (reason) {
    connectError.value = messageOf(reason);
  } finally {
    password.value = "";
    connecting.value = false;
  }
}
function selectConnectPlatform(code: PlatformCode) {
  if (!connectablePlatforms.value.some((p) => p.code === code)) return;
  showConnect.value = true;
  if (connectPlatform.value === code) return;
  connectPlatform.value = code;
  connectError.value = "";
  username.value = "";
  password.value = "";
  oauthChallengeId.value = "";
  oauthAuthorizeUrl.value = "";
  clearOAuthPoll();
  storeCredentials.value = true;
}
function changeConnectPlatform(event: any) {
  const next = connectablePlatforms.value[Number(event.detail.value)]?.code;
  if (next) selectConnectPlatform(next);
}
async function disconnect(account: Account) {
  if (
    !(await confirm(
      "解除平台连接",
      `确认解除 ${platformName(account.platform)} 的云端授权？解除后相关自动同步将停止。`,
    ))
  )
    return;
  pendingAccount.value = account.id;
  try {
    await api(`/accounts/${account.id}`, "DELETE");
    toast("连接已解除");
    invalidate("accounts");
    invalidate("activity");
    await reload(true);
  } catch (reason) {
    toast(messageOf(reason));
  } finally {
    pendingAccount.value = "";
  }
}
async function sync() {
  syncError.value = "";
  syncMessage.value = "";
  if (selectedMode.value && !activityIds.value.length) {
    syncError.value = "活动选择信息已失效，请返回活动列表重新选择";
    return;
  }
  const days = Number(historyDays.value);
  if (!Number.isInteger(days) || days < 1 || days > 365) {
    syncError.value = "历史范围请填写 1—365 天";
    return;
  }
  if (source.value === "LOCAL" && !destinationIds.value.length) {
    syncError.value = "从本地活动库投递时，请至少选择一个目标平台";
    return;
  }
  if (!selectedMode.value && !syncSportTypes.value.length) {
    syncError.value = "请至少选择一种活动类型";
    return;
  }
  syncing.value = true;
  try {
    const result = await api<{ jobIds: string[] }>(
      "/sync",
      "POST",
      {
        sourcePlatform: source.value,
        destinationAccountIds: destinationIds.value,
        historyDays: days,
        ...(!selectedMode.value ? { sportTypes: syncSportTypes.value } : {}),
        ...(activityIds.value.length ? { activityIds: activityIds.value } : {}),
      },
      true,
      {},
      60000,
    );
    syncMessage.value = result.jobIds.length
      ? `已提交 ${result.jobIds.length} 个同步任务，可在任务记录查看进度。`
      : "本次没有需要新增的同步任务。";
    toast("同步请求已处理");
  } catch (reason) {
    syncError.value = messageOf(reason);
  } finally {
    syncing.value = false;
  }
}
async function saveRule() {
  ruleError.value = "";
  if (!ruleDestination.value) {
    ruleError.value = "请先连接目标平台账号";
    return;
  }
  if (!ruleSportTypes.value.length) {
    ruleError.value = "请至少选择一种活动类型";
    return;
  }
  savingRule.value = true;
  try {
    await api<Rule>("/rules", "POST", {
      sourcePlatform: ruleSource.value,
      destinationAccountId: ruleDestination.value,
      enabled: true,
      includeHistory: includeHistory.value,
      sportTypes: ruleSportTypes.value,
    });
    toast("自动同步规则已保存");
    await reload(true);
  } catch (reason) {
    ruleError.value = messageOf(reason);
  } finally {
    savingRule.value = false;
  }
}
function onRuleSwitch(rule: Rule, event: any) {
  void toggleRule(rule, !!event.detail?.value);
}
async function toggleRule(rule: Rule, enabled: boolean) {
  ruleBusy.value = rule.id || "";
  try {
    await api("/rules", "POST", { ...rule, enabled });
    await reload(true);
  } catch (reason) {
    toast(messageOf(reason));
  } finally {
    ruleBusy.value = "";
  }
}
async function deleteRule(rule: Rule) {
  if (
    !(await confirm(
      "删除同步规则",
      "删除后不会再按此方向自动投递活动。确认删除？",
    ))
  )
    return;
  ruleBusy.value = rule.id || "";
  try {
    await api(`/rules/${rule.id}`, "DELETE");
    await reload(true);
  } catch (reason) {
    toast(messageOf(reason));
  } finally {
    ruleBusy.value = "";
  }
}
const accountPlatform = (id: string) =>
  accounts.value.find((a) => a.id === id)?.platform;
</script>
<template>
  <AppShell
    active="sync"
    title="同步中心"
    subtitle="管理平台账号与自动同步规则"
    ><template #action
      ><button
        class="btn secondary small"
        :disabled="loading || refreshing"
        @click="reload(true)"
      >
        {{ refreshing ? "刷新中…" : "刷新状态" }}
      </button></template
    ><SessionGate @ready="reload(true)"
      ><LoadState
        :loading="loading"
        :refreshing="refreshing"
        :error="fatalError"
        :stale-error="staleError"
        skeleton="list"
        @retry="reload(true)"
        ><view class="card"
          ><view class="card-heading"
            ><view
              ><text class="section-title">连接运动平台</text
              ><text class="muted"
                >只有在你授权后，云服务才会访问运动账号。</text
              ></view
            ><button
              class="btn primary small"
              :disabled="!connectablePlatforms.length"
              @click="showConnect = !showConnect"
            >
              {{ showConnect ? "收起" : "连接平台" }}
            </button></view
          ><view class="platform-grid"
            ><view
              v-for="platform in session.config?.platforms"
              :key="platform.code"
              class="platform-tile"
              :class="{
                active: showConnect && connectPlatform === platform.code,
                connected: accounts.some((a) => a.platform === platform.code),
              }"
              @click="selectConnectPlatform(platform.code)"
              ><view
                class="platform-monogram"
                :class="platform.code.toLowerCase()"
                >{{ platformInitials[platform.code] || '本' }}</view
              ><view
                ><text class="platform-title">{{ platform.name }}</text
                ><text class="tiny muted">{{
                  accounts.some((a) => a.platform === platform.code)
                    ? "已连接"
                    : authMode(platform.code) === "OAUTH"
                      ? "官方 OAuth 跳转授权"
                      : authMode(platform.code) === "PASSWORD" ||
                          authMode(platform.code) === "WEB_SESSION"
                        ? "系统内直接登录 · 实验性"
                        : "云端授权尚未支持"
                }}</text></view
              ><view
                v-if="accounts.some((a) => a.platform === platform.code)"
                class="status-dot" /></view></view
          ><text class="tiny muted block"
            >高驰、iGPSPORT、迈金、行者、Garmin 中国站支持系统内直接登录；Strava
            通过官方 OAuth 2.0 授权并自动刷新令牌，支持拉取活动（重建 FIT）与上传，无需手动复制 Cookie 或 Token。</text
          ><view v-if="accounts.length" class="connected-hint-row"
            ><text class="tiny muted">已连接账号可直接前往活动列表一键去重拉取。</text
            ><button class="text-button" @click="go('activities')">
              前往活动列表拉取 ›
            </button></view
          ><view
            v-for="account in accounts"
            :key="account.id"
            class="account-row"
            ><view
              ><text class="item-title"
                >{{ platformName(account.platform) }} ·
                {{ account.displayName }}</text
              ><text class="muted"
                >{{ statusLabel(account.status) }} · 最近同步
                {{ date(account.lastSyncAt) }}</text
              ><text v-if="account.lastError" class="error-text">{{
                account.lastError
              }}</text></view
            ><button
              class="text-button danger"
              :disabled="pendingAccount === account.id"
              @click="disconnect(account)"
            >
              {{ pendingAccount === account.id ? "处理中…" : "解除连接" }}
            </button></view
          >
          <view v-if="showConnect" class="form-panel"
            ><text class="section-title">授权云端连接</text
            ><text class="muted"
              >{{ loginHint?.note || "输入平台账号密码，由云服务完成登录并加密保存登录态，支持会话过期自动续期。" }}</text
            ><view class="form-grid"
              ><view class="form-field"
                ><text class="field-label">运动平台</text
                ><picker
                  :range="connectablePlatforms"
                  range-key="name"
                  :value="
                    Math.max(
                      0,
                      connectablePlatforms.findIndex(
                        (p) => p.code === connectPlatform,
                      ),
                    )
                  "
                  @change="changeConnectPlatform"
                  ><view class="input select-input"
                    >{{ platformName(connectPlatform) }}<text>⌄</text></view
                  ></picker
                ></view
              >
              <template v-if="isStravaOAuth">
                <view v-if="!stravaOAuthReady" class="form-field"
                  ><text class="field-label">Strava Client ID</text
                  ><input
                    v-model="stravaClientId"
                    class="input"
                    placeholder="例如 183007"
                    :disabled="connecting"
                    autocomplete="off" /></view
                ><view v-if="!stravaOAuthReady" class="form-field"
                  ><text class="field-label">Strava 客户端密钥（Client Secret）</text
                  ><input
                    v-model="stravaClientSecret"
                    class="input"
                    password
                    placeholder="仅用于云端换取与刷新授权令牌"
                    :disabled="connecting"
                    autocomplete="off" /></view
                >
                <view
                  v-if="oauthAuthorizeUrl"
                  class="notice info-notice web-session-guide"
                >
                  <text class="item-title">请在浏览器中打开以下链接完成 Strava 官方授权</text>
                  <text class="tiny muted" selectable>{{ oauthAuthorizeUrl }}</text>
                  <view class="extension-actions">
                    <button class="btn primary small" @click="copyOAuthUrl">
                      复制授权链接
                    </button>
                    <text v-if="oauthPolling" class="tiny muted"
                      >正在等待你在浏览器完成授权…</text
                    >
                  </view>
                </view>
                <text v-else class="tiny muted session-field-wide"
                  >点击下方按钮将跳转至 Strava 官方授权页完成登录并授予活动读写权限，授权完成后自动返回并由云端加密存储与续期令牌。</text
                >
              </template>
              <template v-else>
                <view class="form-field"
                  ><text class="field-label">{{
                    loginHint?.accountLabel || "平台账号"
                  }}</text
                  ><input
                    v-model="username"
                    class="input"
                    :placeholder="
                      loginHint?.accountPlaceholder || '平台手机号、邮箱或用户名'
                    "
                    :disabled="connecting"
                    autocomplete="off" /></view
                >
                <view class="form-field"
                  ><text class="field-label">平台密码</text
                  ><input
                    v-model="password"
                    class="input"
                    password
                    placeholder="仅本次提交，客户端不保存"
                    :disabled="connecting"
                    autocomplete="off" /></view
                >
                <checkbox-group
                  @change="storeCredentials = $event.detail.value.includes('store')"
                  ><label class="check-line"
                    ><checkbox
                      value="store"
                      :checked="storeCredentials"
                      :color="themeColors.primary"
                    /><text
                      >允许云服务加密托管登录凭据，以便会话过期后自动续期</text
                    ></label
                  ></checkbox-group
                ><text class="tiny muted"
                  >默认保存加密登录态（Token / Cookie / SSO 凭据）；勾选托管凭据后，若长会话彻底过期可由云端静默重登。密码提交后会立即从本页清空。</text
                >
              </template>
            </view>
            <text v-if="connectError" class="error-text">{{
              connectError
            }}</text>
            <button
              class="btn primary"
              :loading="connecting"
              :disabled="connecting"
              @click="connect"
            >
              {{
                isStravaOAuth
                  ? "前往 Strava 官方授权登录"
                  : "授权并保存登录态"
              }}
            </button></view
          ></view
        >
        <view class="sync-columns"
          ><view class="card"
            ><view class="card-heading"
              ><view
                ><text class="section-title">手动同步</text
                ><text class="muted"
                  >按需拉取，或把活动投递到其他平台。</text
                ></view
              ><image class="section-icon" :src="`/static/sync${theme.dark ? '-dark' : ''}.svg`" /></view
            ><view v-if="selectedMode" class="notice info-notice"
              ><text
                >将投递从活动列表选中的
                {{
                  activityIds.length
                }}
                条活动。已存在于目标平台的活动会跳过。</text
              ></view
            ><view v-else class="form-field"
              ><text class="field-label">活动来源</text
              ><picker
                :range="sourceOptions"
                range-key="name"
                :value="Math.max(0, selectedSource)"
                @change="chooseSource(Number($event.detail.value))"
                ><view class="input select-input"
                  >{{ sourceOptions[Math.max(0, selectedSource)]?.name
                  }}<text>⌄</text></view
                ></picker
              ></view
            ><view class="form-field"
              ><text class="field-label"
                >同步到<text class="tiny muted">{{
                  source === "LOCAL" ? "（至少选一个）" : "（可选，不选仅拉取）"
                }}</text></text
              ><view
                v-if="destinationAccounts.length"
                class="destination-options"
                ><button
                  v-for="account in destinationAccounts"
                  :key="account.id"
                  class="destination-chip"
                  :class="{ checked: destinationIds.includes(account.id) }"
                  @click="toggleDestination(account.id)"
                >
                  <text class="check-square">{{
                    destinationIds.includes(account.id) ? "✓" : ""
                  }}</text
                  >{{ platformName(account.platform) }}
                </button></view
              ><text v-else class="muted"
                >请先连接一个不同的目标平台。</text
              ></view
            ><view v-if="!selectedMode" class="form-field"
              ><text class="field-label"
                >活动类型<text class="tiny muted">（可多选）</text></text
              ><view class="destination-options"
                ><button
                  v-for="opt in sportCategoryOptions"
                  :key="opt.code"
                  class="destination-chip"
                  :class="{ checked: syncSportTypes.includes(opt.code) }"
                  :disabled="syncing"
                  @click="toggleSyncSportType(opt.code)"
                >
                  <text class="check-square">{{
                    syncSportTypes.includes(opt.code) ? "✓" : ""
                  }}</text
                  >{{ opt.label }}
                </button></view
              ><text v-if="!syncSportTypes.length" class="tiny danger block"
                >请至少选择一种活动类型。</text
              ><text
                v-else-if="
                  hasOnelapDestination &&
                  syncSportTypes.some((t) => t !== 'CYCLING')
                "
                class="tiny muted"
                >提示：迈金一般仅支持骑行类活动，非骑行记录可能会被忽略或上传失败。</text
              ><text v-else-if="hasOnelapDestination" class="tiny muted"
                >迈金一般仅支持骑行类活动，当前仅同步骑行记录。</text
              ><text v-else class="tiny muted"
                >默认包含全部类型，可按需只勾选骑行或跑步。</text
              ></view
            ><view class="form-field"
              ><text class="field-label">拉取历史范围（天）</text
              ><input
                v-model="historyDays"
                class="input short-input"
                type="number"
                placeholder="90"
                :disabled="syncing"
              /><text class="tiny muted"
                >1—365 天。相同任务会自动去重，远端处理可能需要时间。</text
              ></view
            ><text v-if="syncError" class="error-text">{{ syncError }}</text
            ><view v-if="syncMessage" class="notice success-notice"
              ><text>{{ syncMessage }}</text
              ><button class="text-button" @click="go('jobs')">
                查看任务 ›
              </button></view
            ><button
              class="btn primary full"
              :disabled="syncing"
              :loading="syncing"
              @click="sync"
            >
              {{ syncing ? "正在提交…" : "开始同步" }}
            </button></view
          >
          <view class="card"
            ><view class="card-heading"
              ><view
                ><text class="section-title">自动同步规则</text
                ><text class="muted"
                  >规则开启后，即授权云端持续自动投递。</text
                ></view
              ><text class="tag">后台执行</text></view
            ><view v-if="!rules.length" class="rule-empty"
              ><text class="muted"
                >还没有规则。创建一个同步方向，让新活动自动归位。</text
              ></view
            ><view v-for="rule in rules" :key="rule.id" class="rule-row"
              ><view class="rule-info"
                ><text class="item-title"
                  >{{ platformName(rule.sourcePlatform) }}
                  <text class="rule-arrow">→</text>
                  {{
                    platformName(accountPlatform(rule.destinationAccountId))
                  }}</text
                ><view class="rule-meta"
                  ><text class="tag rule-type-tag">{{
                    sportTypesSummary(rule.sportTypes)
                  }}</text
                  ><text class="tiny muted">{{
                    rule.includeHistory
                      ? "包含已有活动"
                      : "仅规则创建后的新活动"
                  }}</text></view
                ></view
              ><switch
                :color="themeColors.primary"
                :checked="rule.enabled"
                :disabled="ruleBusy === rule.id"
                @change="onRuleSwitch(rule, $event)"
              /><button
                class="text-button danger tiny"
                :disabled="ruleBusy === rule.id"
                @click="deleteRule(rule)"
              >
                删除
              </button></view
            ><view class="rule-form"
              ><text class="field-label">新增同步方向</text
              ><picker
                :range="sourceOptions"
                range-key="name"
                :value="Math.max(0, selectedRuleSource)"
                @change="chooseRuleSource(Number($event.detail.value))"
                ><view class="input select-input"
                  >{{ sourceOptions[Math.max(0, selectedRuleSource)]?.name
                  }}<text>⌄</text></view
                ></picker
              ><text class="flow-arrow">↓</text
              ><picker
                :range="ruleDestinationOptions"
                range-key="label"
                :value="Math.max(0, selectedRuleDestination)"
                :disabled="!ruleDestinationOptions.length"
                @change="chooseRuleDestination(Number($event.detail.value))"
                ><view class="input select-input"
                  >{{
                    ruleDestinationLabel || "请先连接目标平台"
                  }}<text>⌄</text></view
                ></picker
              ><view class="rule-sport-field"
                ><text class="field-label"
                  >活动类型<text class="tiny muted">（可多选）</text></text
                ><view class="destination-options"
                  ><button
                    v-for="opt in sportCategoryOptions"
                    :key="opt.code"
                    class="destination-chip"
                    :class="{ checked: ruleSportTypes.includes(opt.code) }"
                    :disabled="savingRule"
                    @click="toggleRuleSportType(opt.code)"
                  >
                    <text class="check-square">{{
                      ruleSportTypes.includes(opt.code) ? "✓" : ""
                    }}</text
                    >{{ opt.label }}
                  </button></view
                ><text v-if="!ruleSportTypes.length" class="tiny danger block"
                  >请至少选择一种活动类型。</text
                ><text
                  v-else-if="
                    ruleDestinationIsOnelap &&
                    ruleSportTypes.some((t) => t !== 'CYCLING')
                  "
                  class="tiny muted"
                  >提示：迈金一般仅支持骑行类活动。</text
                ><text v-else-if="ruleDestinationIsOnelap" class="tiny muted"
                  >迈金一般仅支持骑行类活动，已默认仅同步骑行。</text
                ><text v-else class="tiny muted"
                  >将自动同步：{{ ruleSportSummary }}活动。</text
                ></view
              ><checkbox-group
                @change="
                  includeHistory = $event.detail.value.includes('history')
                "
                ><label class="check-line"
                  ><checkbox
                    value="history"
                    :checked="includeHistory"
                    :color="themeColors.primary"
                  /><text>同时补齐活动库中的历史记录</text></label
                ></checkbox-group
              ><text v-if="ruleError" class="error-text">{{ ruleError }}</text
              ><button
                class="btn secondary full"
                :loading="savingRule"
                :disabled="savingRule || !ruleDestination"
                @click="saveRule"
              >
                保存并开启自动同步
              </button></view
            ></view
          ></view
        ></LoadState
      ></SessionGate
    ></AppShell
  >
</template>
