# Step Studio for Android

将 [studio.stepx.com](https://studio.stepx.com/) 封装为 Android 客户端的极简工程：原生 WebView 壳，不引入 AndroidX 与任何第三方 SDK，构建产物约 45 KB。

> 非官方封装：仅做 WebView 加载与基础交互适配，界面与功能均来自站点本身。

## 下载

在 [Releases](https://github.com/ra1nzzz/stepstudio-apk/releases) 下载 `StepStudio.apk`。

## 应用信息

| 项 | 值 |
| --- | --- |
| 应用名 | Step Studio |
| 包名 | com.stepstudio.app |
| 版本 | 1.1.6（versionCode 8） |
| minSdk / targetSdk | 21 / 34（Android 5.0 起） |
| 体积 | 约 53 KB |
| 签名 | RSA 2048，v1 + v2 |

## 功能

- 直接加载 `https://studio.stepx.com/`
- 沉浸式显示：状态栏 / 导航栏透明，页面背景延伸到系统栏区域；WebView 视口按安全区整体内缩，固定头部不被系统栏遮挡
- 系统栏图标随网页背景自动切换深浅色（浅色界面用深色图标，深色界面用浅色图标）
- 右下角版本胶囊，点击查看版本号、包名、更新源，并可手动检查更新
- 启动闪屏：首帧前展示，网络差时作为过渡，加载失败切重试页
- 内置更新机制：启动时与后台定期检查 GitHub Release，发现新版本自动静默下载，签名校验通过后提示一键安装
- 版本弹窗内「下载并安装最新版」：不等版本比较，随时拉取最新包一键下载，便于真机回归测试
- 安装权限引导：Android 8+ 未授权「安装未知应用」时，自动弹出引导直达系统开关
- 下载容错：GitHub 直连失败自动切换加速镜像重试；安装改由应用内 Provider 授权读取，避免部分机型 DownloadManager uri 失效
- 长按版本胶囊打开「直装入口」：正式版（最新 Release）/ 测试包（含预发布，取列表首个）/ 自定义地址（粘贴任意 APK 链接）三条通道，下载后统一走签名校验再拉起安装
- 测试包浮窗：页面里嗅探到 `.apk` 链接（或剪贴板里存在构建器「一键复制链接」的地址）时，右下角浮现独立「装测试包」按钮，一点即下载安装；测试包按独立应用处理，只校验是否为合法安装包，不做同签名限制
- 返回键在网页内回退，退到底才退出应用
- 站外链接交由系统浏览器打开
- 支持网页内 `<input type="file">` 选择文件上传
- 登录态（Cookie）持久化，重启应用免重复登录
- 网页触发的下载交由系统下载器处理（携带 Cookie）
- 语音输入时按需申请麦克风权限
- 断网 / 加载失败时展示内置重试页

## 更新机制

- 更新源：本仓库的 `releases/latest`（GitHub REST API）
- 版本比较：以 release tag（如 `v1.1.0`）与本机 `versionName` 逐段比较
- 检查策略：启动后延时检查一次，距上次检查 6 小时内不重复检查；也可在版本弹窗中手动检查
- 下载：命中新版本后由 `DownloadManager` 静默下载到应用私有目录，完成后弹窗「立即安装」
- 安装：跳转系统安装器，仍需用户在系统弹窗中确认（Android 安全限制，无法静默安装）
- 权限：已声明 `REQUEST_INSTALL_PACKAGES`；首次更新时系统可能要求允许「安装未知应用」

## 项目结构

```
.
├── AndroidManifest.xml          # 单 Activity，无第三方依赖
├── build.sh                     # 免 Gradle 构建脚本（aapt2 + javac + d8 + apksigner）
├── res/
│   ├── values/strings.xml       # 应用名
│   ├── values/styles.xml        # 沉浸式深色无标题栏主题
│   └── mipmap-*/ic_launcher.png # 多密度启动图标
└── src/com/stepstudio/app/
    └── MainActivity.java        # WebView 容器、沉浸式适配、更新模块
```

工程刻意不用 Gradle：只有 1 个 Java 文件、9 个资源文件，直接用 SDK 自带工具链即可出包，便于快速回归。

## 构建

环境要求：JDK 17、Android SDK（`build-tools;34.0.0` + `platforms;android-34`），不需要 Android Studio。

```bash
# 未签名调试包
ANDROID_HOME=/path/to/android-sdk bash build.sh

# 正式签名包
ANDROID_HOME=/path/to/android-sdk \
KEYSTORE=/path/to/keystore.jks \
KS_ALIAS=stepstudio \
KS_PASS=你的口令 \
VERSION_CODE=2 \
VERSION_NAME=1.1.0 \
bash build.sh
```

产物位于 `out/`。脚本可用以下方式准备 SDK：

```bash
sdkmanager "platforms;android-34" "build-tools;34.0.0"
```

注意：发版时 `VERSION_NAME` 必须与 release tag 一致（tag 去掉前缀 `v`），否则更新检测会判断错误。

## 签名与升级

- 已发布版本使用固定 keystore 签名（不入库）。
- 同一 keystore 才能覆盖升级；换签名的包无法覆盖安装，只能卸载重装。
- 每次发版请递增 `VERSION_CODE` / `VERSION_NAME`。
- 自行构建分发时，请使用自己生成的 keystore：

```bash
keytool -genkeypair -v -keystore release.jks -alias stepstudio \
  -keyalg RSA -keysize 2048 -validity 10950
```

## 安装

1. 手机浏览器打开 Release 链接下载 APK
2. 首次安装需允许「安装未知来源应用」
3. 点击安装，打开后登录自己的 studio.stepx.com 账号

登录态按设备独立保存，多个设备之间互不影响。

## 说明

站点为面向桌面的 IDE 界面，手机端为等比缩放完整页面，横向操作会偏挤。如需移动端优化，可考虑切换移动 UA 由站点走响应式布局，或在壳内增加桌面/移动视图切换。

## 更新记录

### 1.1.2
- 新增启动闪屏：浅色底 + 图标 + 载入提示，首帧可见后淡出，网络较差时作为过渡；加载失败自动切到重试页
- OTA 模块重构：单线程执行器 + 并发去重、仅在成功时记录检查时间、下载任务持久化（重启后继续安装）、失败状态清理
- 更新包安装前校验包名与签名指纹，与本应用不一致直接拒绝并删除
- 下载完成广播改用 `RECEIVER_NOT_EXPORTED`，防第三方伪造广播触发安装
- 统一 dp 换算与窗口边距逻辑，内存清理（销毁时移除回调、关闭线程池）

### 1.1.1
- 修复沉浸式下页面顶部固定头部（左上角菜单）落到状态栏下方、无法点按的问题：改用真实布局边距内缩，WebView 视口本身从安全区内开始

### 1.1.0
- 沉浸式：状态栏 / 导航栏透明，页面延伸至系统栏区域，按安全区留白
- 系统栏图标跟随网页主题自动切换深浅色
- 新增右下角版本胶囊与「关于」弹窗（版本号、包名、更新源、检查更新）
- 新增更新模块：GitHub Release 检测、静默下载、一键跳转安装

### 1.0.0
- 首个版本：WebView 容器、返回键回退、外链跳转、文件选择、Cookie 持久化、系统下载、语音权限、断网重试页
