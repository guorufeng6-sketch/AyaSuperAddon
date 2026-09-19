# Android 验证范围

日期：2026-09-15。兼容官方 Android 1.0.4（195）。前期验收来自 ARM64 / Android 12 Root 研究设备的原签名宿主运行时；用户随后明确确认“未root我已经测试了，可以，没问题”，补充为**非 Root 设备已由用户实机验证可用**。该确认不包含新提供的逐功能日志、具体机型或所有签名配置，不能外推无限后台或所有模式。没有发布测试账号、标识或原始日志。

| 层次 | 结果 |
| --- | --- |
| 自动测试 | ChatPolicy 18、NavCore 20、NavSessionPolicy 15、NavSimulation 20，共73项 |
| 原创源码构建 | JDK 17 + Android SDK36 javac / D8通过 |
| 本地包合并 | 指定 SHA 官方输入的 apktool 合并与 ZIP 完整性通过；未签名 |
| 真实问答 | 官方 ASR → 自有模型 HTTP成功 → 镜片回答；用户确认 |
| 搜索/插话/自动关闭/录音导出 | Root 运行时用户确认；不是本次公开重签包逐项回归 |
| 地图 | 实际地图、搜索、定位；三种出行方式在线规划均返回路线 |
| 眼镜导航 | 固定测试通过；真实路线文字随后也由用户确认，不只是SDK回执 |
| 新增模拟导航 | 73项检查包含回放核心，尚未在新版本真机验证暂停/倍速/镜片 |
| 非 Root 设备使用 | 用户已实机验证可用；自己的签名、服务授权与其他设备仍需复核 |
| 户外跟随 / 到达 / 偏航 / 长期后台 | 未完成，当前前台显示有4分钟保护 |
| 知识库 / 其他 Agent | Android 端到端未验收；不是下拉名称就代表可用 |

研究过程曾出现冷启动附加运行时的 native crash；等待宿主稳定后可继续测试，但未证明根因已经根治。公开合并路线不依赖 Frida，仍需独立评估重签、厂商服务和长期稳定性。

## 自己复核

```sh
node scripts/check-source.mjs
bash android-addon/build.sh
node android-addon/package.mjs /path/to/RayNeo_AI_1.0.4.apk
unzip -t android-addon/build/TurboIO-RayNeo-1.0.4-unsigned.apk
```

构建需先设置自己的 `ANDROID_SDK_ROOT`。发布检查不能替代真机验收，也不能证明所有历史提交永远没有秘密。发布前应检查实际 Git 暂存区，禁止上传 `build/`、私人配置、签名、日志与生成APK。当前公开源文件默认无模型/搜索/地图密钥。
