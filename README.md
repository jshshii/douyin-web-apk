# douyin-web-apk

抖音网页版（`https://www.douyin.com`）的轻量 WebView 套壳 Android App。

## 构建

### 通过 GitHub Actions（推荐）

推送到 `main` 分支，或手动触发 `Build APK` workflow (`workflow_dispatch`)：

- 产物：`douyin-web-debug-apk`（Actions Artifacts，保留 30 天）
- 环境：JDK 17 + Gradle 8.7 + AGP 8.2.2

### 本地构建

需要 JDK 17 与 Android SDK（`compileSdk 34`）。

```bash
# 首次：若仓库未包含 wrapper 脚本，用已安装的 Gradle 生成
gradle wrapper --gradle-version 8.7

./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> 注意：本仓库当前只提交了 `gradle/wrapper/gradle-wrapper.properties`，
> 未包含 `gradlew` / `gradlew.bat` / `gradle-wrapper.jar`。
> 请执行上面的 `gradle wrapper` 生成，或直接用系统 Gradle 构建。
> CI 已做了兼容：存在 `./gradlew` 就用，否则回退到 PATH 上的 `gradle`。

## 架构

| 文件 | 作用 |
|---|---|
| `MainActivity.java` | 单 Activity；WebView 配置、Cookie、外链分流、全屏播放、返回栈 |
| `activity_main.xml` | `WebView` + 全屏播放容器 `fullscreen_container` |
| `themes.xml` | `AppTheme`（黑底、无 ActionBar，避免启动白屏） |
| `AndroidManifest.xml` | 权限、`allowBackup=false`、`configChanges` |

## 已实现的行为

- **UA**：桌面 Chrome UA + wide viewport（刻意为之，见下方风险说明）
- **外链分流**：`http(s)` 站内消化；`intent://` 与 `snssdk1128://` / `weixin://` / `alipays://` 等
  自定义 scheme 交给系统，无应用可处理时 Toast 提示
- **Cookie 持久化**：接受三方 Cookie，页面加载后与退出时 `flush()`
- **全屏视频**：`onShowCustomView` / `onHideCustomView` + 沉浸式系统栏
- **返回栈**：有历史则回退；首页连按两次退出
- **生命周期**：`onPause` 暂停计时器省电，`onDestroy` 安全销毁 WebView，`onSaveInstanceState` 保存浏览状态
- **安全**：`allowBackup=false`（避免 WebView 登录态被云备份）、`usesCleartextTraffic=false`、`exported` Activity 无参数入口

## 已知风险

1. **抖音对第三方 WebView 套壳有风控**。可能出现「浏览器环境异常」、强制跳登录页、
   验证码或直接不可用。这是产品层面的限制，无法通过代码完全绕过。
   本项目的 UA/视口配置已针对该问题做过取舍，但不保证长期有效。
2. **桌面 UA 在手机上的排版**。桌面版布局需要横向滑动或缩放适配，交互体验不如原生移动端。
   若抖音后续对桌面 UA 收紧，可改回移动 UA（删掉 `setUserAgentString` 一行即可）。
3. **release 包未签名**。如需发布，请在 `app/build.gradle` 中补充 `signingConfigs`，
   密钥走 CI Secrets 或本地 `keystore.properties`（勿提交到仓库）。