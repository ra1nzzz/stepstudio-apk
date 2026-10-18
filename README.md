# Step Studio for Android

将 [studio.stepx.com](https://studio.stepx.com/) 封装为 Android 客户端的极简工程：原生 WebView 壳，不引入 AndroidX 与任何第三方 SDK，构建产物约 37 KB。

> 非官方封装：仅做 WebView 加载与基础交互适配，界面与功能均来自站点本身。

## 下载

在 [Releases](https://github.com/ra1nzzz/stepstudio-apk/releases) 下载 `StepStudio.apk`。

## 应用信息

| 项 | 值 |
| --- | --- |
| 应用名 | Step Studio |
| 包名 | com.stepstudio.app |
| 版本 | 1.0.0（versionCode 1） |
| minSdk / targetSdk | 21 / 34（Android 5.0 起） |
| 体积 | 约 37 KB |
| 签名 | RSA 2048，v1 + v2 |

## 功能

- 直接加载 `https://studio.stepx.com/`
- 返回键在网页内回退，退到底才退出应用
- 站外链接交由系统浏览器打开
- 支持网页内 `<input type="file">` 选择文件上传
- 登录态（Cookie）持久化，重启应用免重复登录
- 网页触发的下载交由系统下载器处理（携带 Cookie）
- 语音输入时按需申请麦克风权限
- 断网 / 加载失败时展示内置重试页

## 项目结构

```
.
├── AndroidManifest.xml          # 单 Activity，无第三方依赖
├── build.sh                     # 免 Gradle 构建脚本（aapt2 + javac + d8 + apksigner）
├── res/
│   ├── values/strings.xml       # 应用名
│   ├── values/styles.xml        # 深色无标题栏主题
│   └── mipmap-*/ic_launcher.png # 多密度启动图标
└── src/com/stepstudio/app/
    └── MainActivity.java        # WebView 容器与全部交互逻辑
```

工程刻意不用 Gradle：只有 1 个 Java 文件、9 个资源文件，直接用 SDK 自带工具链即可出包，便于快速回归。

## 构建

环境要求：JDK 17、Android SDK（`build-tools;34.0.0` + `platforms;android-34`），不需要 Android Studio。

```bash
# 未签名调试包
ANDROID_HOME=/path/to/android-sdk ./build.sh

# 正式签名包
ANDROID_HOME=/path/to/android-sdk \
KEYSTORE=/path/to/keystore.jks \
KS_ALIAS=stepstudio \
KS_PASS=你的口令 \
VERSION_CODE=2 \
VERSION_NAME=1.0.1 \
./build.sh
```

产物位于 `out/`。脚本可用以下方式准备 SDK：

```bash
sdkmanager "platforms;android-34" "build-tools;34.0.0"
```

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

### 1.0.0
- 首个版本：WebView 容器、返回键回退、外链跳转、文件选择、Cookie 持久化、系统下载、语音权限、断网重试页
