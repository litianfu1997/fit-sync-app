import { computed, reactive } from "vue";

export type ThemeMode = "system" | "light" | "dark";
const THEME_KEY = "fitsync.theme.v1";
const storedMode = uni.getStorageSync(THEME_KEY);
export const theme = reactive({
  mode: (["system", "light", "dark"].includes(storedMode)
    ? storedMode
    : "system") as ThemeMode,
  dark: false,
});

// 与 Android ui/theme/Color.kt 保持同名语义及色值。
const light = {
  primary: "#C2410C",
  "on-primary": "#FFFFFF",
  "primary-container": "#FFEDD5",
  "on-primary-container": "#7C2D12",
  "primary-hover": "#9A3412",
  background: "#F1F3F5",
  surface: "#FFFFFF",
  "surface-variant": "#EEF1F4",
  "surface-low": "#F7F8FA",
  "surface-high": "#E6EAEF",
  "on-surface": "#0F141A",
  muted: "#5A6673",
  border: "#E3E7EC",
  outline: "#C6CDD6",
  error: "#B91C1C",
  "error-container": "#FEE2E2",
  "on-error-container": "#7F1D1D",
  success: "#15803D",
  "success-container": "#DCFCE7",
  "on-success-container": "#14532D",
  warning: "#C2410C",
  "warning-container": "#FFEDD5",
  "on-warning-container": "#7C2D12",
  info: "#1D4ED8",
  "info-container": "#DBEAFE",
  "on-info-container": "#1E3A8A",
};
const dark: typeof light = {
  primary: "#FF8A3D",
  "on-primary": "#4B1D00",
  "primary-container": "#713400",
  "on-primary-container": "#FFDCC2",
  "primary-hover": "#FFB067",
  background: "#0A0E13",
  surface: "#121820",
  "surface-variant": "#1C242E",
  "surface-low": "#171E27",
  "surface-high": "#232C37",
  "on-surface": "#E8ECF1",
  muted: "#97A3B1",
  border: "#212A34",
  outline: "#38424F",
  error: "#F87171",
  "error-container": "#7F1D1D",
  "on-error-container": "#FEE2E2",
  success: "#4ADE80",
  "success-container": "#14532D",
  "on-success-container": "#BBF7D0",
  warning: "#FF8A3D",
  "warning-container": "#713400",
  "on-warning-container": "#FFDCC2",
  info: "#60A5FA",
  "info-container": "#1E3A8A",
  "on-info-container": "#DBEAFE",
};
export const themeColors = computed(() => (theme.dark ? dark : light));
export const themeStyle = computed(() => {
  const variables: Record<string, string> = {
    "--fit-brand": "#F97316",
    "--fit-hero-gradient": "linear-gradient(120deg, #0E141B, #1A1712, #3A1D07)",
    "--fit-on-hero": "#FFFFFF",
    "--fit-hero-muted": "rgba(255,255,255,0.62)",
  };
  for (const [name, value] of Object.entries(themeColors.value))
    variables[`--fit-${name}`] = value;
  return variables;
});

let initialized = false;
let systemDark = false;
let pageReady = false;
function applyTheme() {
  theme.dark = theme.mode === "dark" || (theme.mode === "system" && systemDark);
  // #ifdef H5
  document.documentElement.style.colorScheme = theme.dark ? "dark" : "light";
  document.documentElement.style.backgroundColor = themeColors.value.background;
  // #endif
  // #ifdef MP-WEIXIN
  if (!pageReady) return;
  uni.setNavigationBarColor({
    frontColor: theme.dark ? "#ffffff" : "#000000",
    backgroundColor: themeColors.value.background,
  });
  uni.setBackgroundColor({
    backgroundColor: themeColors.value.background,
    backgroundColorTop: themeColors.value.background,
    backgroundColorBottom: themeColors.value.background,
  });
  // #endif
}
export function initializeTheme() {
  if (initialized) return;
  initialized = true;
  // #ifdef H5
  const media = window.matchMedia("(prefers-color-scheme: dark)");
  systemDark = media.matches;
  media.addEventListener("change", (event) => {
    systemDark = event.matches;
    applyTheme();
  });
  // #endif
  // #ifdef MP-WEIXIN
  systemDark = uni.getSystemInfoSync().theme === "dark";
  uni.onThemeChange((event) => {
    systemDark = event.theme === "dark";
    applyTheme();
  });
  // #endif
  applyTheme();
}
// 页面跳转会新建原生窗口，需要在每页 onShow 重应用用户选定的主题。
export function syncPageTheme() {
  pageReady = true;
  // #ifdef MP-WEIXIN
  systemDark = uni.getSystemInfoSync().theme === "dark";
  // #endif
  applyTheme();
}
export function setThemeMode(mode: ThemeMode) {
  theme.mode = mode;
  uni.setStorageSync(THEME_KEY, mode);
  applyTheme();
}
