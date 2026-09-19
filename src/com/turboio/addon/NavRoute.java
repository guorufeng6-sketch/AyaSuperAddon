package com.turboio.addon;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.*;

/**
 * 高德 Web 服务的**纯逻辑层**：URL 拼装 + 折线几何处理。
 *
 * ── 为什么 URL 拼装要单独放、还要单测 ─────────────────────────────
 * 用户实测「导航不可用，和倒计时一样的错误：不刷新，卡在第一屏不动」。
 * 根因（2026-09-18 实测确认）就在一条 URL 上：
 *
 *   v3/direction/driving 的 steps **默认带** polyline；
 *   v5/direction/driving 的 steps **默认不带**，必须显式写进 show_fields。
 *   实测：show_fields=cost,tmcs
 *           → step keys = [instruction, orientation, step_distance, cost, tmcs]   ← 没有几何！
 *         show_fields=cost,tmcs,polyline
 *           → step keys = [..., polyline]
 *
 * 几何全空 → 代码用"起点"占位 → 所有分段几何都等于起点 →
 * NavCore.match 永远匹配到第 0 段、剩余里程恒等于全程、进度条不动
 * → **画面每 3 秒推的还是同一屏**，看上去就是"卡在第一屏"。
 *
 * 这种 bug 靠"逻辑推理"是想不到的，只能靠一条断言钉住 URL：
 *   assert drivingUrl(...).contains("show_fields=cost,tmcs,polyline")
 * 所以 URL 拼装必须能被单测直接调用 —— 这也是本类存在的唯一理由。
 *
 * 纯逻辑，无 android 依赖（URLEncoder 是 JDK 类，桌面 JVM 也有）。
 */
public final class NavRoute {

    private NavRoute() {}

    // ══════════════════════════════════════════════════════════════
    //  URL 拼装
    // ══════════════════════════════════════════════════════════════

    /**
     * ★ `show_fields` 必须带 polyline（见类注释）。
     *
     * strategy（v5 驾车策略）—— v3r24 按用户实测「高德推荐的路线并不是最快路线」改：
     *   · 32 = 高德推荐：**综合权重**（时间 / 距离 / 收费 / 拥堵 / 大路偏好），
     *         与高德 App 默认一致，但**不保证时间最短** —— 这正是"不是最快"的来源；
     *   · 38 = 速度最快：静态最快，**不看实时拥堵**，堵起来反而更慢；
     *   · 45 = 躲避拥堵 + 速度最快：结合实时路况求最快，导航该用这个。
     * 所以改成 45；`show_fields` 里带 tmcs（路况）正好供它用。
     */
    public static String drivingUrl(String key, String originLonLat, String destLonLat) {
        return "https://restapi.amap.com/v5/direction/driving?key=" + enc(key)
            + "&origin=" + enc(originLonLat) + "&destination=" + enc(destLonLat)
            + "&show_fields=cost,tmcs,polyline&strategy=45";
    }

    /** 逆地理编码：拿"我在哪座城市"（城市锚的原料）。 */
    public static String regeoUrl(String key, String lonLat) {
        return "https://restapi.amap.com/v3/geocode/regeo?key=" + enc(key) + "&location=" + enc(lonLat);
    }

    /** 地址解析。city 为空 = 全国范围（**只在跨城或拿不到城市锚时才允许**）。 */
    public static String geocodeUrl(String key, String address, String city) {
        String url = "https://restapi.amap.com/v3/geocode/geo?key=" + enc(key)
            + "&address=" + enc(address);
        if (city != null && !city.isEmpty()) url += "&city=" + enc(city);
        return url;
    }

    /**
     * POI 关键字搜索。city 非空时**必须同时带 citylimit=true** ——
     * 只给 city 不给 citylimit，高德会"优先但不限于"该城市，边界上仍可能跑到邻市。
     */
    public static String poiUrl(String key, String keywords, String city) {
        String url = "https://restapi.amap.com/v3/place/text?key=" + enc(key)
            + "&keywords=" + enc(keywords) + "&offset=1&page=1";
        if (city != null && !city.isEmpty()) url += "&city=" + enc(city) + "&citylimit=true";
        return url;
    }

    private static String enc(String value) {
        if (value == null) return "";
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  折线几何
    // ══════════════════════════════════════════════════════════════

    /** 高德 polyline 字符串（`lon,lat;lon,lat;...`）→ 点列。非法对直接丢掉。 */
    public static List<NavCore.Point> line(String polyline) {
        List<NavCore.Point> out = new ArrayList<>();
        if (polyline == null || polyline.isEmpty()) return out;
        for (String pair : polyline.split(";")) {
            int comma = pair.indexOf(',');
            if (comma <= 0) continue;
            try {
                double lon = Double.parseDouble(pair.substring(0, comma).trim());
                double lat = Double.parseDouble(pair.substring(comma + 1).trim());
                NavCore.Point p = new NavCore.Point(lat, lon);
                // 相邻重复点没有意义，还会让 match 的 t=0 分支退化
                if (!out.isEmpty()) {
                    NavCore.Point prev = out.get(out.size() - 1);
                    if (Math.abs(prev.lat - p.lat) < 1e-7 && Math.abs(prev.lon - p.lon) < 1e-7) continue;
                }
                out.add(p);
            } catch (Exception ignored) { }
        }
        return out;
    }

    /** 折线能不能用来做定位匹配：至少两点，且不是同一个点。 */
    public static boolean usable(List<NavCore.Point> points) {
        if (points == null || points.size() < 2) return false;
        NavCore.Point a = points.get(0);
        for (int i = 1; i < points.size(); i++) {
            NavCore.Point b = points.get(i);
            if (Math.abs(a.lat - b.lat) > 1e-7 || Math.abs(a.lon - b.lon) > 1e-7) return true;
        }
        return false;
    }

    /**
     * 缺几何的段用相邻端点补出近似线段。
     *
     * ── 为什么不能拿"起点"当占位 ──────────────────────────────────
     * 旧实现给缺几何的段填 `[origin, origin]`。这在**所有**段都缺几何时
     * （正是漏写 show_fields 的情形）会让 NavCore.match 对每一段算出
     * 相同的距离、永远选中第 0 段 —— 画面就此定死。
     * 改成用「上一段终点 → 下一段起点」至少是一条真实位置的线段，
     * match 能顺着它往前走；实在没有邻居才退化成单点（usable=false，
     * 调用方会改用"按已行驶里程推进"的兜底）。
     *
     * @param rawLines 每段解析出来的折线，可能为空
     * @param origin   起点（第一段缺几何且没有后继时用它）
     */
    public static List<List<NavCore.Point>> patch(List<List<NavCore.Point>> rawLines, NavCore.Point origin) {
        List<List<NavCore.Point>> out = new ArrayList<>();
        if (rawLines == null || rawLines.isEmpty()) return out;
        int n = rawLines.size();
        for (int i = 0; i < n; i++) {
            List<NavCore.Point> raw = rawLines.get(i);
            if (usable(raw)) { out.add(new ArrayList<>(raw)); continue; }

            NavCore.Point from = origin;
            for (int k = i - 1; k >= 0; k--) {
                List<NavCore.Point> prev = out.get(k);
                if (usable(prev)) { from = prev.get(prev.size() - 1); break; }
                if (!prev.isEmpty()) { from = prev.get(prev.size() - 1); break; }
            }
            NavCore.Point to = null;
            for (int k = i + 1; k < n; k++) {
                List<NavCore.Point> next = rawLines.get(k);
                if (next != null && !next.isEmpty()) { to = next.get(0); break; }
            }
            List<NavCore.Point> patched = new ArrayList<>();
            if (from != null) patched.add(from);
            if (to != null && (from == null || Math.abs(to.lat - from.lat) > 1e-7 || Math.abs(to.lon - from.lon) > 1e-7)) {
                patched.add(to);
            } else if (from != null) {
                patched.add(from);
            }
            out.add(patched);
        }
        return out;
    }

    /** 所有段的几何都不可用 → 定位匹配这条路走不通，调用方改用里程兜底。 */
    public static boolean allUnusable(List<NavCore.Step> steps) {
        if (steps == null || steps.isEmpty()) return true;
        for (NavCore.Step s : steps) if (usable(s.points)) return false;
        return true;
    }

    /**
     * 整条路线的几何是不是"全都等于起点"（漏 polyline 的典型指纹）。
     * 只用于诊断文案：这种情况必须告诉用户"高德没返回路线几何"，
     * 而不是让他看一屏永远不动的画面。
     */
    public static boolean collapsedToOrigin(List<NavCore.Step> steps) {
        if (steps == null || steps.isEmpty()) return false;
        NavCore.Point first = null;
        for (NavCore.Step s : steps) {
            if (s.points.isEmpty()) continue;
            if (first == null) { first = s.points.get(0); continue; }
            NavCore.Point p = s.points.get(0);
            if (Math.abs(first.lat - p.lat) > 1e-7 || Math.abs(first.lon - p.lon) > 1e-7) return false;
        }
        return first != null;
    }
}
