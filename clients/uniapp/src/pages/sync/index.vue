<script setup lang="ts">
import { computed, ref } from "vue";
import { themeColors, theme } from "../../lib/theme";
import { onLoad } from "@dcloudio/uni-app";
import AppShell from "../../components/AppShell.vue";
import SessionGate from "../../components/SessionGate.vue";
import LoadState from "../../components/LoadState.vue";
import { api, confirm, go, messageOf, session, toast } from "../../lib/api";
import { useLoad } from "../../lib/page";
import { date, platformName, platformInitials, statusLabel } from "../../lib/format";
import type { Account, PlatformCode, Rule } from "../../lib/types";
const accounts = ref<Account[]>([]),
  rules = ref<Rule[]>([]),
  connecting = ref(false),
  showConnect = ref(false),
  connectPlatform = ref<PlatformCode>("COROS"),
  username = ref(""),
  password = ref(""),
  storeCredentials = ref(false),
  connectError = ref(""),
  pendingAccount = ref("");
const source = ref<PlatformCode>("LOCAL"),
  destinationIds = ref<string[]>([]),
  historyDays = ref("90"),
  syncing = ref(false),
  syncError = ref(""),
  syncMessage = ref("");
const activityIds = ref<string[]>([]);
const selectedMode = ref(false);
onLoad((query) => {
  if (!query?.activityIds) return;
  selectedMode.value = true;
  try {
    activityIds.value = decodeURIComponent(String(query.activityIds))
      .split(",")
      .filter((id) => /^[a-f0-9-]{36}$/i.test(id))
      .slice(0, 100);
  } catch {
    syncError.value = "活动选择信息已失效，请返回活动列表重新选择";
  }
});
const ruleSource = ref<PlatformCode>("LOCAL"),
  ruleDestination = ref(""),
  includeHistory = ref(false),
  savingRule = ref(false),
  ruleBusy = ref(""),
  ruleError = ref("");
const supportedPlatforms = computed(
  () =>
    session.config?.platforms.filter((p) => p.authMode === "PASSWORD") || [],
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
const ruleDestinations = computed(() =>
  accounts.value.filter((a) => a.platform !== ruleSource.value),
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
const { loading, error, reload } = useLoad(async () => {
  const [a, r] = await Promise.all([
    api<Account[]>("/accounts"),
    api<Rule[]>("/rules"),
  ]);
  accounts.value = a;
  rules.value = r;
  destinationIds.value = destinationIds.value.filter((id) =>
    a.some((x) => x.id === id && x.platform !== source.value),
  );
  if (!ruleDestinations.value.some((x) => x.id === ruleDestination.value))
    ruleDestination.value = ruleDestinations.value[0]?.id || "";
});
function chooseSource(index: number) {
  source.value = sourceOptions.value[index].code;
  destinationIds.value = destinationIds.value.filter((id) =>
    destinationAccounts.value.some((a) => a.id === id),
  );
}
function chooseRuleSource(index: number) {
  ruleSource.value = sourceOptions.value[index].code;
  ruleDestination.value = ruleDestinations.value[0]?.id || "";
}
function toggleDestination(id: string) {
  destinationIds.value = destinationIds.value.includes(id)
    ? destinationIds.value.filter((x) => x !== id)
    : [...destinationIds.value, id];
}
async function connect() {
  connectError.value = "";
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
    await api<Account>("/accounts", "POST", {
      platform: connectPlatform.value,
      username: username.value.trim(),
      password: password.value,
      storeCredentials: storeCredentials.value,
    });
    username.value = "";
    showConnect.value = false;
    toast("平台已连接");
    await reload();
  } catch (reason) {
    connectError.value = messageOf(reason);
  } finally {
    password.value = "";
    connecting.value = false;
  }
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
    await reload();
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
  syncing.value = true;
  try {
    const result = await api<{ jobIds: string[] }>("/sync", "POST", {
      sourcePlatform: source.value,
      destinationAccountIds: destinationIds.value,
      historyDays: days,
      ...(activityIds.value.length ? { activityIds: activityIds.value } : {}),
    });
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
  savingRule.value = true;
  try {
    await api<Rule>("/rules", "POST", {
      sourcePlatform: ruleSource.value,
      destinationAccountId: ruleDestination.value,
      enabled: true,
      includeHistory: includeHistory.value,
    });
    toast("自动同步规则已保存");
    await reload();
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
    await reload();
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
    await reload();
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
      ><button class="btn secondary small" :disabled="loading" @click="reload">
        刷新状态
      </button></template
    ><SessionGate @ready="reload"
      ><LoadState :loading="loading" :error="error" @retry="reload"
        ><view class="card"
          ><view class="card-heading"
            ><view
              ><text class="section-title">连接运动平台</text
              ><text class="muted"
                >只有在你授权后，云服务才会访问运动账号。</text
              ></view
            ><button
              class="btn primary small"
              :disabled="!supportedPlatforms.length"
              @click="showConnect = !showConnect"
            >
              {{ showConnect ? "收起" : "连接平台" }}
            </button></view
          ><view class="platform-grid"
            ><view
              v-for="platform in session.config?.platforms"
              :key="platform.code"
              class="platform-tile"
              ><view
                class="platform-monogram"
                :class="platform.code.toLowerCase()"
                >{{ platformInitials[platform.code] || '本' }}</view
              ><view
                ><text class="platform-title">{{ platform.name }}</text
                ><text class="tiny muted">{{
                  accounts.some((a) => a.platform === platform.code)
                    ? "已连接"
                    : platform.authMode === "PASSWORD"
                      ? "支持云端授权 · 实验性"
                      : "云端授权尚未支持"
                }}</text></view
              ><view
                v-if="accounts.some((a) => a.platform === platform.code)"
                class="status-dot" /></view></view
          ><text class="tiny muted block"
            >目前 COROS、iGPSPORT 支持实验性云端连接；其他平台可先导入
            FIT，未提供模拟登录。</text
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
              >平台协议可能变化，遇到验证码或风控会明确提示。</text
            ><view class="form-grid"
              ><view class="form-field"
                ><text class="field-label">运动平台</text
                ><picker
                  :range="supportedPlatforms"
                  range-key="name"
                  :value="
                    Math.max(
                      0,
                      supportedPlatforms.findIndex(
                        (p) => p.code === connectPlatform,
                      ),
                    )
                  "
                  @change="
                    connectPlatform =
                      supportedPlatforms[Number($event.detail.value)].code
                  "
                  ><view class="input select-input"
                    >{{ platformName(connectPlatform) }}<text>⌄</text></view
                  ></picker
                ></view
              ><view class="form-field"
                ><text class="field-label">平台账号</text
                ><input
                  v-model="username"
                  class="input"
                  placeholder="平台手机号、邮箱或用户名"
                  :disabled="connecting"
                  autocomplete="off" /></view
              ><view class="form-field"
                ><text class="field-label">平台密码</text
                ><input
                  v-model="password"
                  class="input"
                  password
                  placeholder="仅本次提交，客户端不保存"
                  :disabled="connecting"
                  autocomplete="off" /></view></view
            ><checkbox-group
              @change="storeCredentials = $event.detail.value.includes('store')"
              ><label class="check-line"
                ><checkbox
                  value="store"
                  :checked="storeCredentials"
                  :color="themeColors.primary"
                /><text
                  >允许云服务加密托管登录凭据，以便会话过期后恢复自动同步</text
                ></label
              ></checkbox-group
            ><text class="tiny muted"
              >不勾选仅保留平台登录会话，过期后需重新连接。密码提交后会从本页清空。</text
            ><text v-if="connectError" class="error-text">{{
              connectError
            }}</text
            ><button
              class="btn primary"
              :loading="connecting"
              :disabled="connecting"
              @click="connect"
            >
              授权并连接
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
                ><text class="tiny muted">{{
                  rule.includeHistory ? "包含已有活动" : "仅规则创建后的新活动"
                }}</text></view
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
                :range="ruleDestinations"
                range-key="displayName"
                :value="Math.max(0, selectedRuleDestination)"
                :disabled="!ruleDestinations.length"
                @change="
                  ruleDestination =
                    ruleDestinations[Number($event.detail.value)].id
                "
                ><view class="input select-input"
                  >{{
                    ruleDestinations.length
                      ? platformName(accountPlatform(ruleDestination))
                      : "请先连接目标平台"
                  }}<text>⌄</text></view
                ></picker
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
