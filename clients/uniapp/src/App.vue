<script setup lang="ts">
import { onLaunch } from "@dcloudio/uni-app";
import { initialize } from "./lib/api";
import { initializeTheme } from "./lib/theme";
onLaunch(() => {
  initializeTheme();
  void initialize();
});
</script>
<style lang="scss">
@use './styles/app.scss';
</style>
