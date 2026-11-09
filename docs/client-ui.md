# 小程序、Web 与 Android 的统一视觉规范

样式基线是 Android 当前的 `ui/theme` 与 `ui/components` 实现。旧的 `design-system/fitsync/MASTER.md` 只作为历史参考，不覆盖原生端的实际样式。

## 实现入口

- [跨端主题](../clients/uniapp/src/lib/theme.ts)：Android 浅色与深色色值的语义映射，默认跟随系统；设置页可切换浅色、深色或跟随系统，偏好仅保存在本机。
- [共用样式](../clients/uniapp/src/styles/app.scss)：布局、字号、间距、描边、卡片、按钮、输入框、状态与导航。
- [页面框架](../clients/uniapp/src/components/AppShell.vue)：桌面侧栏、手机三项主导航与同步二级入口。
- [系统主题配置](../clients/uniapp/src/theme.json)：H5 与微信的窗口背景和导航文字颜色。

## 对照标准

| 语义 | 浅色 | 深色 |
| --- | --- | --- |
| 主操作橙色 | `#C2410C` | `#FF8A3D` |
| 主操作文字 | `#FFFFFF` | `#4B1D00` |
| 页面背景 | `#F1F3F5` | `#0A0E13` |
| 卡片表面 | `#FFFFFF` | `#121820` |
| 正文 | `#0F141A` | `#E8ECF1` |
| 次要文字 | `#5A6673` | `#97A3B1` |
| 细描边 | `#E3E7EC` | `#212A34` |

来源：[Color.kt](../app/src/main/java/com/fitsync/app/ui/theme/Color.kt)。橙色用于品牌、主操作和待处理；蓝色用于距离与信息；绿色用于成功；红色用于失败。平台识别色只在平台头像中出现。

卡片 8px、控件 4px、状态徽标 2px、Hero 与手机底栏 12px；卡片为 1px 描边、无阴影。页面水平间距 16px、区块间距 24px，主要交互目标至少 48px。来源：[Shape.kt](../app/src/main/java/com/fitsync/app/ui/theme/Shape.kt)、[Spacing.kt](../app/src/main/java/com/fitsync/app/ui/theme/Spacing.kt)。

正文采用 14–15px、辅助信息 11–12px，主要数据 30–42px，数字启用等宽排版。字体栈优先使用设备已有的 MiSans，其后使用系统中文字体；本次不把 Android 约 17MB 的三份字库放入小程序包。字号、字重和行高对齐 [Type.kt](../app/src/main/java/com/fitsync/app/ui/theme/Type.kt)。

首页及活动详情使用原生端相同的深炭至暗橙 Hero 渐变；轨迹预览使用中性底和 1.5px 路线。Logo 复用 Android [ic_launcher.xml](../app/src/main/res/drawable/ic_launcher.xml) 的路径。

手机主导航为「概览 / 活动 / 设置」。概览、同步和任务之间通过二级导航切换；桌面保留直接侧栏入口。小程序与 H5 使用同一套组件、样式和主题，不改变原有同步流程。

系统主题监听依据 [uni-app DarkMode 指南](https://uniapp.dcloud.net.cn/tutorial/darkmode.html) 实现；微信运行时的状态栏、选择器与真机渲染仍需在实际小程序中验收。

## 本轮验收

TypeScript 检查、H5 与微信小程序构建均通过。浏览器已检查 375、768、1024、1440px 视口，无横向溢出；浅深主题切换、跳转后保留偏好、活动列表与详情、同步页面和轨迹绘制正常，控制台无错误。实际渲染测得浅色背景 `#F1F3F5`、深色背景 `#0A0E13`、卡片圆角 8px、按钮圆角 4px、按钮高度 48px。

截图使用合成骑行数据：[桌面浅色](assets/cloud/overview-unified-light.png)、[桌面深色](assets/cloud/overview-unified-dark.png)、[手机深色](assets/cloud/overview-unified-mobile.png)。微信构建不等于真机验收，尤其需检查系统主题、胶囊避让与原生选择器。
