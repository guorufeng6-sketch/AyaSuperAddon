# AyaSuperAddon

雷鸟（RayNeo）AI 眼镜 **Android 宿主扩展**的非商业研究源码。在官方 App 内接入自有大模型、联网搜索、实时导航投屏到眼镜、热搜新闻、RAG 知识库等能力。

> ⚠️ 这是给开发者使用的**源码，不是现成可用的 APK，也不是独立 SDK**。需要你自己准备官方 App 安装包、配置构建环境与签名。不熟悉 Android 构建 / 签名 / 排错请勿使用。

## 功能

- 自有模型对话（OpenAI 兼容 Chat Completions，SSE 流式）
- Tavily 联网搜索（自备 API Key，App 端填写）
- 实时热搜新闻（语音翻页、推送显示到眼镜）
- 高德地图导航：路线规划、模拟 / 真实导航、路段文字持续显示到眼镜（高德 Key 需 App 内手动填入，无内置 Key）
- RAG 本地知识库配置
- 录音导出、Markdown 对话、自定义提示词等

## 环境要求

- JDK 17
- Node.js 20+
- Android SDK platform 36 / build-tools 36.0.0
- apktool 2.12.1、`zip` / `unzip`

## 构建与合并

```sh
export ANDROID_SDK_ROOT="/path/to/android-sdk"
sdkmanager "platforms;android-36" "build-tools;36.0.0"

bash build.sh                                  # 编译原创 DEX（含回归检查）
node package.mjs /path/to/RayNeo_AI_1.0.4.apk  # 合并到官方包，输出 unsigned.apk
```

> 请校验输入官方包的 SHA-256，避免被替换的安装包。不要跳过校验直接合并。

自己的签名（用你自己的 keystore，勿把密码写进命令或仓库）：

```sh
"$ANDROID_SDK_ROOT/build-tools/36.0.0/zipalign" -p -f 4 \
  build/TurboIO-RayNeo-1.0.4-unsigned.apk build/TurboIO-RayNeo-1.0.4-aligned.apk
"$ANDROID_SDK_ROOT/build-tools/36.0.0/apksigner" sign \
  --ks /path/to/your-release.jks --out build/TurboIO-RayNeo-1.0.4-signed.apk \
  build/TurboIO-RayNeo-1.0.4-aligned.apk
```

构建产物都留在本机 `build/`，**请勿上传 APK 到仓库 / Issue / Release**。

## 使用

1. 官方 App 主界面点「Turbo IO」入口。
2. 「模型与对话」填入你的 HTTPS Chat Completions URL、模型 ID、API Key。
3. 联网搜索填 Tavily Key；高德在 App 内手动填入 Key。
4. 导航、新闻、知识库按需开启。

## 许可与免责

源码以 **PolyForm Noncommercial 1.0.0** 许可（见 [LICENSE](LICENSE)）：仅限非商业学习研究，禁止商用或收费分发。

- 本项目**不提供**官方 APK、修改版 APK、预签名包、厂商反编译源码、账号或密钥。
- 不绕过登录、签名校验、证书校验或服务授权；第三方 App / SDK / 服务的条款不因本项目改变。
- 使用风险自负，请在自己可恢复的测试设备上操作。
