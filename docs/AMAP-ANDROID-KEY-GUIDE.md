# 高德地图 Android SDK Key 申请指引

> 目标：拿到一个 **Android 平台** 的高德 Key，填进 `com.rayneo.venus.pub` 的
> `com.amap.api.v2.apikey` meta-data，让导航页能出地图。
>
> 依据官方 FAQ：
> - 43112《Android 如何获取 SHA1 值？》 https://lbs.amap.com/faq/android/map-sdk/create-project/43112
> - 46529《Android 如何获取 Package？》 https://lbs.amap.com/faq/android/map-sdk/create-project/46529

---

## 0. 为什么之前那三个 Key 都用不了

高德的 Key **按平台严格隔离**，互不通用：

| Key 类型 | 能用于 | 我们有的 | 能否给 Android SDK 用 |
| --- | --- | --- | --- |
| `Web服务 (REST)` | 后台 HTTP 调 REST API | `3e2a782f29aa3b2a36e8741de53282f6` | ❌ `USERKEY_PLAT_NOMATCH` |
| `Web端 (JS API)` | 浏览器网页 JS SDK | `154b493de0579d7e4c1002679dbc90f1` | ❌ |
| `iOS 平台` | iOS 应用 | — | ❌ |
| **`Android 平台`** | **Android 应用** | **无 ← 就是缺这个** | ✅ |

**Android 平台 Key 必须绑定「包名 + 签名 SHA-1」，所以必须先有我们自签的 keystore。**

---

## 1. 我们这边已经准备好的两个值（直接用，不用自己查）

### 包名（Package Name）— 来自 FAQ 46529 的定义

> AndroidManifest.xml 的 `package` 属性

```
com.rayneo.venus.pub
```

> 实测来源：从 APK 的 AXML 字符串池提取，同时见于 `RayNeo_AI_1.0.4.apk` 与重签包。

### 签名 SHA-1 — 来自 FAQ 43112 的 `keytool -list -v`

**重签用的 release keystore（发布包用这个）**

```
keystore : D:\android-toolchain\turboio-release.jks
alias    : turboio
storepass/keypass : turboio123
```

SHA-1：

```
AD:16:A6:17:95:12:02:F6:E3:BA:9A:48:2D:A5:9C:A7:49:FD:76:BD
```

**debug keystore（如果跑 debug 构建，另外登记这个）**

```
keystore : C:\Users\aya\.android\debug.keystore
alias    : androiddebugkey
pass     : android
```

SHA-1：

```
19:59:CA:CC:11:1D:18:DA:22:CB:C7:D4:76:A8:75:A5:AB:20:C0:BF
```

> 官方原文提醒：**「开发模式（debug）和发布模式（release）下的 SHA1 值是不同的，
> 发布 apk 时需要根据发布 apk 对应的 keystore 重新配置 Key。」**
> 两个都登记上，省得来回切。

复现命令（FAQ 43112 第 3 种方式，最直接）：

```bash
"D:\android-toolchain\jdk-17.0.20.1+1\bin\keytool.exe" ^
  -list -v ^
  -keystore "D:\android-toolchain\turboio-release.jks" ^
  -alias turboio ^
  -storepass turboio123 -keypass turboio123
```

---

## 2. 申请步骤（控制台）

1. 打开 https://console.amap.com/dev/key/app ，登录高德开放平台账号
   （没有就注册，个人开发者即可，实名认证走一下）

2. **创建新应用**
   - 应用名称：随便，建议 `TurboIO-RayNeo`（方便以后认）
   - 应用类型：选 **`出行`**（或任一，不影响 Key 能力）

3. **为该应用添加 Key**
   - Key 名称：`RayNeo-Android`
   - **服务平台：务必选 `Android 平台`** ← 最关键的一步
   - **发布版安全码 SHA1**：粘贴
     ```
     AD:16:A6:17:95:12:02:F6:E3:BA:9A:48:2D:A5:9C:A7:49:FD:76:BD
     ```
   - **调试版安全码 SHA1**：粘贴
     ```
     19:59:CA:CC:11:1D:18:DA:22:CB:C7:D4:76:A8:75:A5:AB:20:C0:BF
     ```
   - **PackageName**：粘贴
     ```
     com.rayneo.venus.pub
     ```
   - 勾选需要的服务（至少 **地图 SDK**；要定位再加 **定位 SDK**）

4. 提交 → 拿到一串 32 位十六进制的 **Android Key**

5. **把 Key 发我**，我写进 APK 的 `com.amap.api.v2.apikey` meta-data，
   重新打包签名。

---

## 3. 关于「一个 SHA1 + 包名只能配一个 Key」

高德允许同一个应用下建多个 Key。但**同一个 `包名 + SHA1` 组合只能绑定一个 Key**。
如果报 `INVALID_USER_KEY` 或 `USERKEY_PLAT_NOMATCH`，99% 是：

- Key 类型选错（选了 Web 服务 / JS API）
- SHA1 填的是别人的（比如原厂雷鸟的签名）
- 包名填错（大小写、少了 `.pub`）

---

## 4. 备选：不换 Key 也能用（如果懒得申请）

我们重签的 APK 里已经加过 **「高德 Key 设置」按钮**（见 `BUILD-NOTES-WINDOWS.md`），
支持运行时填 Key、用 Android Keystore 加密保存。

但**运行时填 Key 解决不了平台隔离问题** —— 填进去的仍然必须是
**Android 平台 + 我们包名/SHA1** 的 Key。所以**申请这一步绕不开**，
运行时按钮只是省掉「重打包」的麻烦。

---

## 5. 结论

| 项 | 值 | 状态 |
| --- | --- | --- |
| 包名 | `com.rayneo.venus.pub` | ✅ 已确认 |
| release SHA-1 | `AD:16:…:BD` | ✅ 已提取 |
| debug SHA-1 | `19:59:…:BF` | ✅ 已提取 |
| Android 平台 Key | — | ⬜ **待你在控制台申请** |

你申请完把 Key 给我，剩下的（写入 meta-data + 重签）我来做。
