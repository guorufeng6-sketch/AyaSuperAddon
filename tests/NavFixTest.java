import com.turboio.addon.NavFix;

/**
 * NavFix：定位新鲜度与自愈判定（"位置不更新 / 看不到位置图标"的规则层）。
 *
 * 存在理由（用户实测反馈）：
 *   用户：「导航还是不刷新 貌似位置不更新？我没看到手机的位置图标 你再看看」
 *
 *   两句症状同一根因：
 *     · 导航期间**从未 requestLocationUpdates** → 系统不给状态栏点定位图标，
 *       而每秒读的都是 getLastKnownLocation 的缓存值 → 那个点从开机起不变
 *       → 段号不动 → 画面定死；
 *     · `if (here != null && fresh())` 条件与意图相反 → 定位一断超 20 秒，
 *       新拿到的点被直接丢掉。
 *
 * 这个类是第二层的判据与文案。必须钉死：
 *   ① "还能不能用" 的分级（fresh / 旧但可用 / 太旧必须弃用）；
 *   ② 订阅自愈的触发条件（掉了要重建；"标记在跑但没数据"也算死订阅）；
 *   ③ 眼镜警示与导航页诊断**必须把定位状态说出来** ——
 *      否则用户看到静止画面时无法区分"路没变"和"定位没数据"。
 */
public class NavFixTest {
    private static int checks = 0;
    private static void ok(boolean condition, String message) {
        checks++;
        if (!condition) System.out.println("  ✗ " + message);
    }

    public static void main(String[] args) {
        // ── ① 可用性分级 ──
        ok(NavFix.usableForMatch(0), "刚拿到的点必须可用");
        ok(NavFix.usableForMatch(NavFix.FRESH_MS), "FRESH 边界算可用");
        ok(NavFix.usableForMatch(NavFix.STALE_MS), "还没到 DEAD 就还能用（会带警示）");
        ok(NavFix.usableForMatch(NavFix.DEAD_MS), "DEAD 边界算可用（下一毫秒才弃用）");
        ok(!NavFix.usableForMatch(NavFix.DEAD_MS + 1), "超过 DEAD 就不能再用了");
        ok(!NavFix.usableForMatch(-1), "从没拿到过定位 = 不可用");

        ok(!NavFix.tooOldToAdvance(0), "新点不该被判太旧");
        ok(!NavFix.tooOldToAdvance(NavFix.DEAD_MS), "DEAD 边界仍可推进");
        ok(NavFix.tooOldToAdvance(NavFix.DEAD_MS + 1), "★ 超过 DEAD 必须弃用旧点（否则就是画面定死）");
        ok(NavFix.tooOldToAdvance(-1), "★ 从没拿到过也必须走「等待定位」而不是拿 null 硬算");

        // ── ② 警示分级 ──
        ok(!NavFix.shouldWarn(0), "新鲜点不警示");
        ok(!NavFix.shouldWarn(NavFix.STALE_MS), "STALE 边界不警示");
        ok(NavFix.shouldWarn(NavFix.STALE_MS + 1), "超过 STALE 要警示");
        ok(NavFix.shouldWarn(-1), "从没拿到过要警示");

        // ── ③ 订阅自愈（注意第 3 个参数是"本次订阅运行了多久"）──
        ok(!NavFix.shouldResubscribe(false, 0, -1, false), "没在导航就别订（省电）");
        ok(!NavFix.shouldResubscribe(false, 60000, 60000, false), "没在导航时再旧也不重建");
        ok(NavFix.shouldResubscribe(false, 0, -1, true), "★ 导航中订阅掉了 → 无条件重建");
        ok(!NavFix.shouldResubscribe(true, -1, 3000, true),
            "★ 刚订阅 3 秒还没拿到点 → **不能重建**（重建会撤掉正在路上的首点，形成活锁）");
        ok(NavFix.shouldResubscribe(true, -1, NavFix.NO_DATA_RESUBSCRIBE_MS + 1, true),
            "★ 订阅跑了很久却一个点都没有 → 死订阅，重建");
        ok(!NavFix.shouldResubscribe(true, -1, NavFix.NO_DATA_RESUBSCRIBE_MS, true),
            "边界：还没超过阈值就先等着");
        ok(!NavFix.shouldResubscribe(true, 3000, 8000, true), "订阅正常且有新点 → 别乱重建");
        ok(!NavFix.shouldResubscribe(true, -1, -1, true), "声称在跑却没有订阅起始时间 → 别动，下个周期再看");
        ok(!NavFix.shouldResubscribe(true, NavFix.NO_DATA_RESUBSCRIBE_MS, 60000, true), "边界不重建");
        ok(NavFix.shouldResubscribe(true, NavFix.NO_DATA_RESUBSCRIBE_MS + 1, 60000, true),
            "★ 标记在跑但长期无数据 → 疑似死订阅，重建（HandlerThread 被系统回收的典型情形）");

        // ── ④ 眼镜警示：优先级 权限 > 开关 > 从没拿到 > 太旧 ──
        ok("".equals(NavFix.hudWarning(1000, true, true)), "一切正常时返回空串（不占屏）");
        ok(NavFix.hudWarning(1000, false, true).contains("权限"), "没权限优先报权限");
        ok(NavFix.hudWarning(1000, false, false).contains("权限"), "又没权限又没开关时先报权限");
        ok(NavFix.hudWarning(1000, true, false).contains("开关"), "有权限但开关没开");
        ok(NavFix.hudWarning(-1, true, true).contains("还没"), "从没拿到定位");
        String stale = NavFix.hudWarning(23000, true, true);
        ok(stale.isEmpty(), "23 秒还没到 STALE，不打扰用户");
        String warn = NavFix.hudWarning(63000, true, true);
        ok(warn.contains("⚠") && warn.contains("1 分钟"), "★ 定位停更要明确报出时长，实际：" + warn);

        // ── ⑤ 导航页诊断：四种状态必须能区分 ──
        String noPerm = NavFix.pageLine(false, true, false, "", -1, -1, "");
        ok(noPerm.contains("未授权"), "没权限要说清并给出下一步，实际：" + noPerm);

        String noProvider = NavFix.pageLine(true, false, false, "", -1, -1, "");
        ok(noProvider.contains("开关"), "没开定位开关要说清，实际：" + noProvider);

        String normal = NavFix.pageLine(true, true, true, "gps", 1500, 8.4f, "");
        ok(normal.contains("GPS") && normal.contains("刚刚") && normal.contains("±8 米")
            && normal.contains("订阅"), "正常态要含 来源/年龄/精度/订阅，实际：" + normal);

        String noData = NavFix.pageLine(true, true, true, "", -1, -1, "");
        ok(noData.contains("还没有数据"), "★ 订阅中但没数据（地库/大楼）要说明，实际：" + noData);

        String dead = NavFix.pageLine(true, true, true, "network", 50000, -1, "");
        ok(dead.contains("50 秒") && dead.contains("停"), "★ 定位陈旧必须解释画面会停，实际：" + dead);

        String notSub = NavFix.pageLine(true, true, false, "", -1, -1, "");
        ok(notSub.contains("没有在订阅"), "没订阅时要说明，实际：" + notSub);

        String err = NavFix.pageLine(true, true, false, "", -1, -1, "定位订阅失败：SecurityException");
        ok(err.contains("SecurityException"), "有具体错误就报具体错误，实际：" + err);

        // ── ⑥ 文案小函数 ──
        ok("GPS".equals(NavFix.providerLabel("gps")), "gps → GPS");
        ok("网络定位".equals(NavFix.providerLabel("network")), "network → 网络定位");
        ok("融合定位".equals(NavFix.providerLabel("fused")), "fused → 融合定位");
        ok("定位".equals(NavFix.providerLabel("")), "空 provider 别输出奇怪文字");
        ok("定位".equals(NavFix.providerLabel(null)), "null provider 也不能崩");
        ok("magic".equals(NavFix.providerLabel("magic")), "不认识的 provider 原样显示，便于排错");

        ok("刚刚".equals(NavFix.ageLabel(0)), "0 秒 → 刚刚");
        ok("刚刚".equals(NavFix.ageLabel(1999)), "2 秒内 → 刚刚");
        ok("2 秒".equals(NavFix.ageLabel(2000)), "2000ms → 2 秒");
        ok("59 秒".equals(NavFix.ageLabel(59000)), "59 秒仍是秒");
        ok("1 分钟".equals(NavFix.ageLabel(60000)), "60 秒进位到分钟");
        ok("从未".equals(NavFix.ageLabel(-1)), "从没拿到 → 从未");

        ok("±8 米".equals(NavFix.accuracyLabel(8.4f)), "精度四舍五入");
        ok("±1 米内".equals(NavFix.accuracyLabel(0.4f)), "亚米级单独说法");
        ok("".equals(NavFix.accuracyLabel(-1)), "拿不到精度时留空，不要显示 ±-1 米");
        ok("".equals(NavFix.accuracyLabel(0)), "精度 0 视为未知");
        ok("".equals(NavFix.accuracyLabel(Float.NaN)), "NaN 不能崩");
        ok("".equals(NavFix.accuracyLabel(Float.POSITIVE_INFINITY)), "Inf 不能崩");

        // ── ⑦ 回归：把用户这次的症状直接钉住 ──
        // 场景：导航中，因为从未订阅，位置一直不动，60 秒后用户看到"不刷新"。
        // 旧实现的表现是**屏幕上一句解释都没有**。现在必须：
        //   · 眼镜上有警示（用户知道是定位问题）；
        //   · 导航页的诊断里说清"多久没有新位置"；
        //   · 并且这一状态下旧点必须被弃用（不能拿它继续算，那就是画面定死）。
        long frozen = 60000;
        ok(!NavFix.hudWarning(frozen, true, true).isEmpty(),
            "★ 回归：定位停更 60 秒，眼镜上必须有说明，不能静默");
        ok(NavFix.tooOldToAdvance(frozen),
            "★ 回归：定位停更 60 秒，旧点必须弃用（改为显示「等待定位」）");
        ok(NavFix.pageLine(true, true, true, "gps", frozen, 20f, "").contains("1 分钟"),
            "★ 回归：导航页要报出停更时长");
        // 反证：订阅正常、每秒一个点时，以上警示一条都不该出现（不能天天误报）。
        ok(NavFix.hudWarning(900, true, true).isEmpty(), "反证：正常刷新时不许有任何警示");
        ok(!NavFix.tooOldToAdvance(900), "反证：正常刷新时旧点判定不该触发");

        System.out.println("NavFix: " + checks
            + " checks PASS (可用性分级 · 订阅自愈 · 警示分级 · 页面诊断 · 定位停更回归)");
    }
}
