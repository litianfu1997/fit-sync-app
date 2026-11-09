<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from "vue";
import { api, messageOf, setSession, toast } from "../lib/api";
import type { User } from "../lib/types";
const emit = defineEmits<{ ready: [] }>();
interface Challenge {
  id: string;
  code: string;
  pollToken: string;
  expiresAtEpochMs: number;
}
const challenge = ref<Challenge | null>(null),
  busy = ref(false),
  error = ref(""),
  remaining = ref(300),
  expired = ref(false);
let timer: ReturnType<typeof setInterval> | undefined,
  generation = 0,
  polling = false;
const formattedCode = computed(
  () => challenge.value?.code.match(/.{1,5}/g)?.join(" ") || "",
);
function stop() {
  if (timer) clearInterval(timer);
  timer = undefined;
  generation++;
}
async function start() {
  if (busy.value) return;
  stop();
  challenge.value = null;
  busy.value = true;
  error.value = "";
  expired.value = false;
  try {
    challenge.value = await api<Challenge>(
      "/auth/web-challenges",
      "POST",
      {},
      false,
    );
    remaining.value = Math.max(
      0,
      Math.ceil((challenge.value.expiresAtEpochMs - Date.now()) / 1000),
    );
    const current = generation;
    timer = setInterval(() => {
      void poll(current);
    }, 3000);
  } catch (reason) {
    error.value = messageOf(reason);
  } finally {
    busy.value = false;
  }
}
async function poll(current = generation) {
  if (!challenge.value || polling || current !== generation) return;
  remaining.value = Math.max(
    0,
    Math.ceil((challenge.value.expiresAtEpochMs - Date.now()) / 1000),
  );
  if (!remaining.value) {
    expired.value = true;
    stop();
    return;
  }
  polling = true;
  try {
    const result = await api<{ status: string; token?: string; user?: User }>(
      `/auth/web-challenges/${challenge.value.id}`,
      "GET",
      undefined,
      false,
      { "X-Login-Poll-Token": challenge.value.pollToken },
    );
    if (current !== generation) return;
    if (result.status === "CONFIRMED" && result.token && result.user) {
      stop();
      setSession({ token: result.token, user: result.user });
      emit("ready");
    } else if (["EXPIRED", "CONSUMED"].includes(result.status)) {
      expired.value = true;
      stop();
    }
    error.value = "";
  } catch (reason) {
    if (current === generation) error.value = messageOf(reason);
  } finally {
    polling = false;
  }
}
function copy() {
  if (challenge.value)
    uni.setClipboardData({
      data: challenge.value.code,
      success: () => toast("登录确认码已复制"),
    });
}
onBeforeUnmount(stop);
</script>
<template>
  <view class="web-login"
    ><text class="section-title">通过小程序确认登录</text
    ><text class="muted">没有账号密码时，可用已登录的 FitSync 小程序授权此网页。</text
    ><view v-if="challenge && !expired" class="web-code-box"
      ><text class="tiny muted">在 FitSync 小程序中确认此网页登录</text
      ><button class="web-login-code" @click="copy">{{ formattedCode }}</button
      ><text class="tiny muted"
        >{{ Math.floor(remaining / 60) }}分{{ remaining % 60 }}秒后过期 ·
        点击复制</text
      ></view
    ><view v-if="challenge && !expired" class="web-login-steps"
      ><text>1. 打开 FitSync 微信小程序并登录</text
      ><text>2. 进入「设置」中的「网页授权登录」</text
      ><text>3. 输入以上确认码，确认登录此网页</text></view
    ><text v-if="expired" class="notice">确认码已失效，请重新生成。</text
    ><button
      v-if="!challenge || expired"
      class="btn primary full"
      :loading="busy"
      :disabled="busy"
      @click="start"
    >
      {{ expired ? "重新生成登录确认码" : "通过微信小程序登录" }}</button
    ><view v-else class="web-login-wait"
      ><view class="spinner" /><text class="muted">等待你在小程序确认…</text
      ><button class="text-button tiny" @click="start">
        换一个确认码
      </button></view
    ><text v-if="error" class="error-text">{{ error }}</text></view
  >
</template>
