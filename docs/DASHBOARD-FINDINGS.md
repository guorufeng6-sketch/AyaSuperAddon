# 雷鸟 AI 眼镜 App 逆向 · 看板 / 应用列表 / 发现页 —— 完整结论

调查对象：`RayNeo_AI_1.0.4.apk`
- 包名 `com.rayneo.venus.pub`，versionName `1.0.4`，versionCode `195`
- Flutter 产物：`lib/arm64-v8a/libapp.so`（44.1 MB，Dart AOT）
- 本地配置基线：`assets/flutter_assets/assets/config/*.json`

---

## 0. 三个东西，别再混为一谈

逆向过程中最容易搞混的是**三套互相独立的系统**：

| # | 系统 | 谁渲染 | 内容来源 | 可改性 |
| --- | --- | --- | --- | --- |
| ① | **看板组件（Dashboard）** | Flutter 原生（Dart AOT） | 云端配置 + App 拉数据 | 内容可改，类型不可加 |
| ② | **眼镜端应用列表（Crown 旋钮切）** | 眼镜固件 | 固件内置 | **不可加** |
| ③ | **发现页信息卡（Discover）** | WebView（HTML/JS） | `test.rayneo.cn` 上的 `config.js` | **可完全替换** |

之前说的「页面内容完全由 JS 配置驱动」**只对 ③ 成立**，与 ① 无关。

---

## 1. 看板组件（Dashboard）

### 1.1 结论

**不能新增组件类型；可以任意改写已有组件的显示内容。**

架构 = **服务端下发内容 + 客户端硬编码渲染**。

### 1.2 组件类型是编译期闭集合（实测类名）

从 `libapp.so` 提取到的实际类名：

```
数据模型（*WidgetData）
  DashboardClockWidgetData       时钟
  DashboardWeatherWidgetData     天气
  DashboardStockWidgetData       股票
  DashboardMailWidgetData        邮件

预览卡（*PreviewCard）
  DashboardCalendarPreviewCard   日程预览
  DashboardEmailPreviewCard      邮件预览
  DashboardStockPreviewCard      股票预览
  DashboardTodoPreviewCard       待办预览
  DashboardWorldClockPreviewCard 世界时钟预览

占位
  DashboardEmptyWidget
```

对应文件路径（`package:venus_flutter_app/features/dashboard_page/...`）：

```
domain/dashboard_component.dart
domain/glasses_dashboard_handler.dart
domain/dashboard_sync_service.dart
presentation/presentation/dashboard_page.dart
presentation/pages/{email,schedule,stock,todo,weather,world_clock}_settings_page.dart
presentation/widgets/dashboard_{calendar,email,stock,todo,weather,world_clock}_preview_card.dart
presentation/widgets/dashboard_component_tile.dart
presentation/widgets/dashboard_component_action_button.dart
presentation/widgets/dashboard_preview.dart
presentation/widgets/dashboard_mode_selector.dart
```

Dart AOT 是提前编译的原生代码，**没有运行时代码加载**。加第 10 种类型 =
重写 Flutter 层，成本远超收益。

### 1.3 可改的切入点（数据接口）

`xr-api-p.rayneo.cn` 上实测存在的接口：

| 端点 | 用途 |
| --- | --- |
| `/glasses/dashboard` | 看板主数据 |
| `/glasses/dashboard/settings/email` | 邮件组件设置 |
| `/glasses/dashboard/settings/schedule` | 日程组件设置 |
| `/glasses/dashboard/settings/stock` | 股票组件设置 |
| `/glasses/dashboard/settings/todo` | 待办组件设置 |
| `/glasses/dashboard/settings/weather` | 天气组件设置 |
| `/glasses/dashboard/settings/world_clock` | 世界时钟组件设置 |
| `/cloudapi/cloudConfig/getConfigInfo` | **看板配置下发**（决定有哪些组件、顺序） |
| `/cloudapi/cloudConfig/editConfig` | 改配置 |
| `/cloudapi/cloudConfig/editCustomParam` | 改自定义参数 |
| `/profileapi/stock/getCodeInfo` | 股票行情（← 本网关改写的目标） |
| `/profileapi/stock/searchCode` | 搜股票代码 |
| `/profileapi/stock/searchCode/batch` | 批量搜代码 |
| `/profileapi/stock/delCode` | 删代码 |
| `/profileapi/weather/getWeatherInfo` | 官方天气 |
| `/profileapi/weather/getCityList` | 城市列表 |
| `/profileapi/geo/reverseGeocode` | 逆地理 |

**做法：网关侧拦截并改写这些响应**，把内容换成自己的数据
（天气→家里温湿度、股票→HA 传感器、待办→米家设备状态…）。
不改 App、不改固件，纯网络层改包。

限制：文字/数值长度受布局约束，超出会被截断；不能画自定义图形。

### 1.4 实测：网关改写已跑通

`D:\turboio-proxy\server.py`（监听 `:8788`）已实现并验证：

- `/profileapi/stock/getCodeInfo` → 返回和风天气伪装的行情
- 其余请求 → 原样透传（实测 `getWeatherInfo` 透传得到上游 401，证明代理链路正常）

实测输出：

```json
{"code":"200","data":{"list":[{
  "name":"太原 多云","code":"101100101",
  "price":"25","currentPrice":"25",
  "changeAmount":"-1.0","changePercent":"-1.0",
  "status":"多云","marketStatus":"多云",
  "temp":"25","text":"多云","feelsLike":"24",
  "humidity":"31","windDir":"东南风","windScale":"4","obsTime":"14:37"
}],"total":1}}
```

---

## 2. 眼镜端应用列表（Crown 旋钮切换）

### 2.1 结论

**应用列表是眼镜固件侧的，App 里加不了新条目。**

### 2.2 证据

**(a) 交互文案证明 Crown 切的是「眼镜端 App」，不是手机 App**

`libapp.so` 内多语言字符串：

```
EN  Rotate the Smart Crown to switch between apps, and press it to open them
DE  Die Smart Crown drehen, um zwischen Apps zu wechseln, und drücken...
FR  Faites pivoter la Smart Crown pour changer d'application, et appuyez...
```

**(b) App 里没有「应用列表页」这个 feature**

`package:venus_flutter_app/features/` 下全部 33 个模块：

```
ai_page            lifelog_page       teleprompter_page   session_page
glasses_page       personal           auth                topic_page
dashboard_page     dev_page           pair_page           voice_record_page
facts_persona_page caption_page       feedback_page       discover_page
ota_page           settings           notification_page   suggest_page
guide_page         home_page          audio_trim_page     calendar_page
proactive_ai_page  memory_page        navigation          setup_checklist_page
projects_page      reminder_page      communicate_page
```

→ **没有任何 applist / launcher 页面**。App 侧只有「手机通知」（`notification_page`
+ `dev_notification_applist_page`），那是**通知转发白名单**，与 Crown 应用列表无关。

**(c) 眼镜端 App 的启动靠 BLE「cue」下发**

```
AppCueingStartRequest / AppCueingStartResponse / GlassesCueingStartRequest
GlassesCueingStartResponse / awaitingAppCueingStartResponse
AppCueingStartConflictAcknowledge / onGlassesAppCueingStartResponse
```

即：手机 App 只能**请求眼镜启动某个它已知的 App**（"cue"），不能注册新 App。

### 2.3 但发现了一个「手机 App 清单」接口（不是 Crown 列表）

```
/profileapi/appNotice/queryAppList
```

配套模型：`AppNoticeItemDto`、`AppListData(bundleId: …)`、`AppListVersion(name: …)`、
`AppListUpgradeRequestType`、`syncSupportedAppList`、`QueryAppNoticeListRequest(deviceType: …)`
页面：`DevNotificationApplistPage`、`PhoneNotificationPageImpressionPageTypeAppListSettings`

**这是「手机通知转发」的受支持应用清单**，用途是筛选用哪些 App 的通知转发到眼镜。

### 2.4 Launcher 侧配置（公开、无鉴权、可读）

`libapp.so` 里硬编码了一个 launcher 配置源：

```
https://test.launcher.tcloudfamily.com/xrlauncherapi/v1/data/dict/getContent
  ?parentTag=venusAreaDefaultPushAppList&tag=20260513
```

**实测：HTTP 200，无需登录**，返回 162 KB JSON，结构：

```
{ error_code:1000 OK,
  data:{ name, content, tag, fieldType, sortNum, colorVal, validStartTime, validEndTime } }
   └ content = JSON 字符串，含三个平台：
       android → 13 组（CN/Global/US/UK/DE/FR/IT/ES/PT/AU/HK/MO/TW）
       iOS     → 12 组（同上，无 Global）
       hmos    → 1 组（CN）
```

Android CN 组共 30 项，每项 schema：

```json
{ "bundleId": "com.autonavi.minimap",
  "categoryId": 6003,
  "iconUrl": "https://file-ffalcon-xr.api-cn.leiniao.com/…",
  "appName": { "zh": "高德地图", "en": "高德地圖-導航巴士地鐵出行，高德打車" } }
```

完整 CN 列表已存：`D:\tmp\turboio\dist\launcher\applist_raw.json`

> 注：这份清单是**「哪些手机 App 支持与眼镜联动」的白名单**（用于搜索/通知），
> 不是 Crown 旋钮切换的那份。Crown 列表在固件里。

### 2.5 Launcher API 全景（`libapp.so` 命中）

```
/xrlauncherapi/v1/signApi/data/dict/getContent
/xrlauncherapi/v1/signApi/discovery/selectList
/xrlauncherapi/v1/signApi/gray/upgrade
/xrlauncherhwapi/v1/signApi/data/dict/getContent
/xrlauncherhwapi/v1/signApi/discovery/selectList
/xrlauncherhwapi/v1/signApi/gray/upgrade
```

（`hw` 系列是硬件侧；`signApi` 需要签名，与上面那个公开 `data/dict` 不同。）

---

## 3. 发现页（Discover）—— 唯一真正「可 DIY」的通道

### 3.1 结论

**模块类型和跳转类型是有限枚举，但内容和跳转目标完全由配置决定，
且是 WebView 渲染 —— 可以把自己的页面塞进去。**

### 3.2 完整语法（从 `discover_page_config_baseline.json` 实测提取）

顶层：

```json
{ "version": "01.01.01.0001",
  "language": { "zh": [ /* module 数组 */ ], "en": [...], … } }
```

支持 12 种语言：`zh zh-TW zh-HK en ja ko fr de es it pt pl`

**模块类型（moduleType）—— 只有 3 种：**

| moduleType | 含义 | 实测出现次数 |
| --- | --- | --- |
| `DISPLAY_TYPE_1` | 大卡横排（带 heroImage） | 12 |
| `LIST_TYPE_1` | 列表条目 | 12 |
| `DISPLAY_TYPE_3` | 底部帮助/支持区 | 12 |

**跳转类型（action.jumpType）—— 只有 3 种：**

| jumpType | 含义 | 实测出现次数 |
| --- | --- | --- |
| `webview` | 打开 webview URL | 120 |
| `H5` | 打开外部 H5 / 小程序 scheme | 12 |
| `APP_PAGE` | 跳 App 原生路由（如 `/feedback`） | 12 |

**条目字段（item）：**

```
itemId, iconId, iconUrl, lightBgColor, darkBgColor, hasAdTag,
mainTitle, subTitle, action{jumpType, jumpUrl}, coverUrl, coverLocal, tag
```

### 3.3 中文发现页实际内容

```
### DISPLAY_TYPE_1  「开始使用」sort=2
   - 了解你的眼镜  [webview] https://test.rayneo.cn/commonPage/xr-secondary/index.html?page=know-glasses&tag=zh
   - 设置指南      [webview] https://test.rayneo.cn/commonPage/xr-secondary/index.html?page=settings_guide&tag=zh

### LIST_TYPE_1  「探索更多能力」sort=3
   - 看板与应用列表     [webview] …?page=dashboard-app-list&tag=zh
   - 录音与 AI 总结     [webview] …?page=recording-ai-summary&tag=zh
   - AI 字幕与翻译      [webview] …?page=ai-caption-translation&tag=zh
   - RayNeo AI          [webview] …?page=rayneo-ai&tag=zh
   - 全天智记与 AI 记忆 [webview] …?page=lifelog-ai-memory&tag=zh
   - 手机通知           [webview] …?page=phone-notifications&tag=zh
   - 提词器             [webview] …?page=teleprompter&tag=zh
   - 交互手势           [webview] …?page=interaction-gestures&tag=zh

### DISPLAY_TYPE_3  「帮助和支持」sort=5
   - 雷鸟论坛    [H5]       weixin://dl/business/?appid=wx84d1feacc3b7ceda&path=pages/index
   - 帮助与反馈  [APP_PAGE] /feedback
```

### 3.4 可 DIY 的点

1. **改 `jumpUrl` 指向自己的服务器** → webview 里就是自己的网页
2. **整个 `PAGES` 数据结构由 `config.js` 定义**（`var PAGE_SEQUENCE`、`PAGES{...}`）
   → 已下载留档：`D:\tmp\turboio\dist\discover-page\{idx.html, config.js}`
3. `test.rayneo.cn` **没有硬编码在 `libapp.so`** —— 只出现在本地 asset
   → 说明基线可被云端覆盖，**改包时替换 baseline 即可重定向**

> `PAGES["dashboard-app-list"]` 那一段名字容易误导：它是「**了解看板**」这个
> 帮助页的文案（`action:"richtex"` + `parentTag`），**不是看板本身**。

---

## 4. 可改性总表

| 目标 | 可行 | 手段 |
| --- | --- | --- |
| 改看板组件**内容** | ✅ | 网关改包 `/profileapi/stock/getCodeInfo` 等 |
| 改看板**组件顺序/启用项** | ✅ | `/cloudapi/cloudConfig/editConfig` |
| 加第 10 种看板**组件类型** | ❌ | 需重写 `libapp.so` |
| 自定义看板组件**渲染样式** | ❌ | 同上 |
| 改发现页**条目/文案/图标** | ✅ | 替换 `config.js` / baseline |
| 把发现页**跳到自己网页** | ✅ | 改 `jumpUrl` |
| 在眼镜端**跑自己的代码** | ❌ | — |
| 给 Crown 应用列表**加新 App** | ❌ | 固件侧闭集合 |
| 改「手机通知转发」名单 | ⚠️ | 接口存在，需登录态 |

---

## 5. 一句话总结

- **看板**：盒子形状焊死（6 类预览卡），盒子里装什么可以自己填。
- **应用列表**：在固件里，App 侧加不了；App 只能"cue"已知 App 启动。
- **发现页**：唯一真正开放的通道 —— WebView + JS 配置，可以直接换成自己的页面。
