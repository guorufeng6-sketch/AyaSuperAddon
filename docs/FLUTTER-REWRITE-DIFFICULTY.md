# 「重写 Flutter 层」到底有多难 —— 实测评估

> 问题：看板想加第 10 种组件类型 / 自定义渲染样式，需要「重写 Flutter 层」。
> 这件事**能不能做、有多难**，下面是基于实测的判断。

---

## 1. 先看清对手是什么

| 项 | 实测值 |
| --- | --- |
| 产物 | `lib/arm64-v8a/libapp.so` |
| 大小 | **44,106,672 字节（42 MB）** |
| 格式 | ELF 64-bit AArch64（`0xb7`） |
| 段数 | 11 个 section header |
| 编译方式 | **Dart AOT**（`_kDartIsolateSnapshotData` / `_kDartVmSnapshotData` / `_kDartSnapshotBuildId` 全都在） |
| `kernel_blob` | **不存在**（`find → -1`） |
| `vm_snapshot_data` / `isolate_snapshot_data` | **不存在**（已合进 .so） |
| 可辨认 Dart 源文件 | **1,795 个** `package:venus_flutter_app/**/*.dart` |
| 可辨认类名符号 | **595 个**（形如 `$ClassName@3934136958`） |
| 代码符号（`$XxxRoute`） | **133 个** 路由 |

**关键：没有 `kernel_blob`。**

Flutter 有两种发布模式：

| 模式 | 产物 | 可反编译性 |
| --- | --- | --- |
| Debug / JIT | `kernel_blob.bin` = Dart 中间代码 | ✅ 有现成反编译器（`dart_decompiler`、`flutter-reverse`） |
| **Release / AOT（本 App）** | **直接编成机器码，无中间代码** | ❌ **没有成熟工具** |

这个 App 是 **Release AOT**，`kernel_blob` 不存在 —— 意味着**没有可以直接还原成 Dart 源码的东西**。

---

## 2. 为什么 AOT 反编译这么难

Dart AOT 编译流程：

```
Dart 源码 ──► Kernel (中间语言) ──► 树摇(Tree Shaking) ──► 机器码 (arm64)
                                        ↑
                            未使用的代码全被删掉
                            类名/方法名大部分被优化掉
                            （只保留反射/调试需要的一小部分）
```

后果：

1. **没有中间代码可以还原** —— 只剩 arm64 汇编
2. **符号被大量剥离** —— 42 MB 里只认出 595 个类名，其余都是匿名偏移
3. **树摇（Tree Shaking）** —— 没被调用的构造函数根本不存在，想"加一种类型"连插入点都找不到
4. **Dart 运行时私有布局** —— 对象头、字段偏移、vtable 布局都是编译器内部约定，
   没有公开 ABI，改一处要连带维护整个 GC / 类型系统假设

**对比一下难度：**

| 目标 | 手段 | 难度 |
| --- | --- | --- |
| 改字符串常量（如本网关、manifest 里的 Key） | 等长二进制替换 | ★☆☆☆☆ |
| 改 HTTP 响应内容 | 中间人网关 | ★★☆☆☆ |
| 改 Java 层（smali） | apktool + smali 编辑 | ★★★☆☆ |
| **改 AOT 编译的 Dart 逻辑** | **手写 arm64 汇编 patch** | **★★★★★** |
| **加一种新的 UI 组件类型** | **上面全部 + 重做树摇/类型系统** | **★★★★★★** |

---

## 3. 真要"重写 Flutter 层"，实际有哪些路

### 路线 A：反编译 libapp.so 后修改 —— **不现实**

- 工具现状：Dart AOT 反编译**没有可用的生产级工具**。
  （IDA/Ghidra 能反汇编 arm64，但得到的是汇编，不是 Dart）
- 即使反汇编成功，要读懂 42 MB 的汇编、找到看板组件注册点、插一段新逻辑 ——
  工作量以**人月**计，且每次官方更新全废。
- **结论：不可行。**

### 路线 B：换掉整个 Flutter 层，自己写 —— **可行但等于重做 App**

理论上可以：
1. 把 `libapp.so` 换成一个自己编译的 Flutter 产物
2. 自己实现 Dart 侧全部逻辑（账密、蓝牙、OTA、AI、字幕、看板…）

代价：
- 要重做 **33 个 feature 模块 / 133 个路由 / 1795 个源文件** 的对应功能
- 要对接官方所有后端（`xr-api-p.rayneo.cn` 那几十个接口）
- 要对接 BLE 协议（会话、码流、cue、OTA）
- **等于从零写一个雷鸟 App**，与"给看板加个组件"完全不成比例
- **结论：技术上可能，工程上荒谬。**

### 路线 C：不动 Flutter，改用别的通道 —— **推荐**

这就是目前在做的事：

| 想达到的效果 | 用不改 Flutter 的方式实现 |
| --- | --- |
| 看板显示**自己的数据** | ✅ 网关改包（已验证） |
| 看板显示**新形态内容** | ✅ 复用 6 种已有卡片的布局，换内容 |
| 想要**完全自定义的界面** | ✅ 走**发现页 WebView**（HTML/CSS 随便写） |
| 想在眼镜上**跑自己的逻辑** | ✅ 宿主内扩展（本仓库 `android-addon`，改 Java 层） |

**结论：绝大多数"想 DIY"的需求都能绕开 Flutter 层。**

---

## 4. 一句话回答

> **「重写 Flutter 层」难度：极大，不划算，不建议。**
>
> - 官方用的是 **Release AOT**，`kernel_blob` 不存在 → 没有中间代码可反编译
> - 42 MB arm64 机器码 + 树摇 + 无公开 ABI → 加组件的插入点基本找不到
> - 真要做只能"整个替换 Flutter 产物"，等于从零重写一个 App（33 模块 / 133 路由）
>
> **正确的用力方向**：
> - 改**内容** → 网关改包（已完成，`:8788`）
> - 要**自定义界面** → 发现页 WebView
> - 要**自定义逻辑** → 宿主内 Java 扩展（`android-addon`）

---

## 5. 附：本次实测的原始数据

```text
libapp.so          = 44,106,672 bytes
ELF magic          = 7f 45 4c 46 (ELF)
class              = 64-bit
machine            = 0xb7 (AArch64)
section headers    = 11 @ 44105968
kernel_blob        = 不存在 (-1)
vm_snapshot_data   = 不存在 (-1)
isolate_snapshot_data = 不存在 (-1)
Dart AOT 标记      = _kDartIsolateSnapshotData
                     _kDartIsolateSnapshotInstructions
                     _kDartSnapshotBuildId
                     _kDartVmSnapshotData
                     _kDartVmSnapshotInstructions
dart 源文件引用    = 1,795 个 (package:venus_flutter_app/...)
类名符号           = 595 个 ($ClassName@3934136958)
路由符号           = 133 个 ($XxxRoute)
看板组件类         = 6 个 *PreviewCard + 4 个 *WidgetData + EmptyWidget
```
