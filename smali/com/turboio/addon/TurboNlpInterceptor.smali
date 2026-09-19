# Turbo IO 扩展：自有 NLP 意图拦截器。
#
# ── 挂载方式 ─────────────────────────────────────────────────────
# 官方在 H7/c.i() 初始化 AI 控制器时这样注册：
#     assistantController.setNlpInterceptor(D7.V0.a);
# 我们不改 H7/c，而是让官方继续注册 D7/V0，然后由 AyaSuperAddon 在合适的
# 时机把本类再 set 一次顶上去。原因是官方那次注册发生在 AI 控制器初始化
# 流程里，时机不确定；而 setNlpInterceptor 是覆盖式赋值，后写者胜。
# 只要本类主动委托给 D7/V0，官方行为就一条都不会丢。
#
# ── 为什么必须委托 ───────────────────────────────────────────────
# D7/V0 负责三类官方改写（支付/音筒口令 -> system_ctrl、navigate -> chat、
# 日程/待办 -> hasNextRound=true）。如果只是"顶掉"它，这三类就会失效。
# 所以 intercept() 的流程是：
#     ① 先把 NlpResult 原样交给 D7/V0，让它完成官方改写并记下它的结论；
#     ② 再问 AyaSuperAddon.onNlpIntercept 要不要接管；
#     ③ 两者取或：官方要接管 OR 扩展要接管 -> 返回 true。
# 这样"官方规则"与"扩展规则"是叠加关系，不是替代关系。
#
# ── 线程安全 ─────────────────────────────────────────────────────
# intercept() 由 native 回调，不保证在主线程。本类只做取值/调用，
# 不碰 View 与 Dialog，所有决策与异常兜底都在 AyaSuperAddon 里。
.class public final Lcom/turboio/addon/TurboNlpInterceptor;
.super Lcom/rayneo/airuntime/controller/INlpInterceptor;
.source "TurboNlpInterceptor.smali"


# direct methods
.method public constructor <init>()V
    .locals 0

    invoke-direct {p0}, Lcom/rayneo/airuntime/controller/INlpInterceptor;-><init>()V

    return-void
.end method


# virtual methods
.method public intercept(Lcom/rayneo/airuntime/controller/NlpResult;)Z
    .locals 8

    const/4 v0, 0x0

    # 空指针保护：native 理论上不会传 null，但防御性返回 false。
    if-nez p1, :cond_body

    return v0

    :cond_body
    # ── ① 先让官方 D7/V0 跑一遍，保留它全部的改写规则 ──────────────
    # v1 = officialTaken（官方是否决定接管）
    #
    # ★ 调用形式说明（这里踩过坑）：
    #   D7/V0 是 `public final class`，且它的 intercept 是 `public final`。
    #   对 final 类的 final 方法，必须用它能被去虚化的形式调用。
    #   早先写成 invoke-virtual {v2,p1}, Lcom/rayneo/airuntime/controller/
    #   INlpInterceptor;->intercept(...)Z —— 把接收者声明成【抽象基类】，
    #   虽然 ART 一般也能分发，但 verifier 对"final 类实例 + 抽象方法声明"
    #   这种组合最严格，一旦被拒就是进程级失败（native 调进来，Java 的
    #   catch 接不住）。改为【按 D7/V0 自身类型 + invoke-virtual】最稳，
    #   final 方法会被 ART 直接去虚化，与官方自己调用 D7/V0 的路径一致。
    :try_start_0
    sget-object v2, LD7/V0;->a:LD7/V0;

    invoke-virtual {v2, p1}, LD7/V0;->intercept(Lcom/rayneo/airuntime/controller/NlpResult;)Z

    move-result v1
    :try_end_0
    .catch Ljava/lang/Throwable; {:try_start_0 .. :try_end_0} :catch_official

    goto :goto_collect

    # 官方实现抛异常也不能让整条链路挂掉，退化成"官方没接管"。
    :catch_official
    move-exception v2

    const/4 v1, 0x0

    :goto_collect
    # 官方已经接管的话，没必要再问扩展，直接放行。
    if-eqz v1, :cond_ask

    return v1

    :cond_ask
    # ── ② 取原始字段，交给扩展判定 ────────────────────────────────
    :try_start_1
    invoke-virtual {p1}, Lcom/rayneo/airuntime/controller/NlpResult;->getDomain()Ljava/lang/String;

    move-result-object v2

    invoke-virtual {p1}, Lcom/rayneo/airuntime/controller/NlpResult;->getIntent()Ljava/lang/String;

    move-result-object v3

    invoke-virtual {p1}, Lcom/rayneo/airuntime/controller/NlpResult;->getSub()Ljava/lang/String;

    move-result-object v4

    invoke-virtual {p1}, Lcom/rayneo/airuntime/controller/NlpResult;->getQuery()Ljava/lang/String;

    move-result-object v5

    invoke-virtual {p1}, Lcom/rayneo/airuntime/controller/NlpResult;->getOffline()Z

    move-result v6

    invoke-virtual {p1}, Lcom/rayneo/airuntime/controller/NlpResult;->getCommand()Lcom/rayneo/airuntime/controller/NlpCommand;

    move-result-object v7

    if-eqz v7, :cond_nocmd

    const/4 v7, 0x1

    goto :goto_call

    :cond_nocmd
    const/4 v7, 0x0

    :goto_call
    # 6 个参数超过非 range 形式上限（5 个），必须用 invoke-static/range，
    # 且要求参数寄存器连续：这里刚好是 v2..v7。
    invoke-static/range {v2 .. v7}, Lcom/turboio/addon/AyaSuperAddon;->onNlpIntercept(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZZ)Z

    move-result v2

    if-nez v2, :cond_take

    return v0

    :cond_take
    # ── 扩展接管：把 hasNextRound 关掉（★ v3r19 关键修复）──────────────
    # 用户实测反馈（连续两版都在报）：
    #   「让他去导航 语音回复不支持 但后台却在规划路线」
    #
    # 根因就在这一行。navigate 在官方 D7/V0 里会被改写成 chat 且
    # hasNextRound=true，意思是"这一轮交给大模型接着回答"。
    # 我们接管之后**并不知道**要改写这个语义，之前反而跟着官方一起把
    # hasNextRound 设成 true —— 于是同一个回合被回答两次：
    #   ① 扩展这边：起高德、推眼镜（正确干活，但不出声）；
    #   ② 官方那边：模型照常回答，而它压根不知道本机扩展能导航，
    #      于是用语音念了一句"不支持"。
    # 用户听到的是 ②，看到的是 ①，体感就是"嘴上说不支持、后台却在规划"。
    #
    # 设成 false = 明确告诉 native「这一轮到此结束，不要再问模型」。
    # 出声的任务交给本机 TTS（Speak.say，见 AyaSuperAddon.reportTakeover），
    # 这样"听到的"和"发生的"终于是同一件事。
    const/4 v3, 0x0

    invoke-virtual {p1, v3}, Lcom/rayneo/airuntime/controller/NlpResult;->setHasNextRound(Z)V
    :try_end_1
    .catch Ljava/lang/Throwable; {:try_start_1 .. :try_end_1} :catch_ext

    return v2

    :catch_ext
    move-exception v2

    return v0
.end method
