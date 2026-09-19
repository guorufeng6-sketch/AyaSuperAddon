package com.turboio.addon;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Platform-independent rules ported from the iOS addon, not vendor source. */
public final class ChatPolicy {
    private ChatPolicy() {}
    public static boolean eligible(String domain, String intent, String sub, boolean offline, boolean command) {
        return "chat".equals(domain) && "chat".equals(intent) && "workflow".equals(sub) && !offline && !command;
    }
    public static boolean endpoint(String value) {
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                && uri.getPath().endsWith("/chat/completions");
        } catch (Exception ignored) { return false; }
    }
    public static String delta(String previous, String current) {
        return current.startsWith(previous) ? current.substring(previous.length()) : null;
    }
    /**
     * 历史回放前清洗：剥掉任何工具调用/工具结果的痕迹。
     * 历史上如果混入 tool_calls 的 JSON，模型会误判"这轮已经执行过"，
     * 表现为"第一次能控制、后来说话就不灵了"。
     */
    public static String sanitizeHistory(String text) {
        if (text == null) return "";
        String out = text;
        // 掐掉常见 JSON 片段起点后的内容，保留前面自然语言部分
        int cut = out.indexOf("\"tool_calls\"");
        if (cut < 0) cut = out.indexOf("\"status\":\"failed\"");
        if (cut < 0) cut = out.indexOf("{\"status\":");
        if (cut >= 0) out = out.substring(0, cut);
        out = out.replaceAll("(?s)\\{[^{}]*\\}", "");   // 残余的浅层 JSON 片段
        out = out.replaceAll("[\\p{Cntrl}&&[^\n\t]]", ""); // 去掉控制字符
        out = out.trim();
        if (out.isEmpty()) return "";
        return out.length() > 2000 ? out.substring(0, 2000) : out;
    }
    public static final class History {
        private final List<String[]> messages = new ArrayList<>();
        private int dropped;   // 因 50 条上限被挤掉的轮次数，用于告知用户"这只是近期记忆"

        public synchronized void append(String question, String answer) {
            if (question == null || answer == null || question.isEmpty() || answer.isEmpty()
                || question.length() > 16000 || answer.length() > 64000) return;
            messages.add(new String[]{"user", question});
            messages.add(new String[]{"assistant", answer});
            while (messages.size() > 50) { messages.remove(0); dropped++; }
            persist();
        }
        public synchronized List<String[]> snapshot() {
            List<String[]> result = new ArrayList<>();
            for (String[] row : messages) result.add(row.clone());
            return result;
        }
        public synchronized void clear() { messages.clear(); dropped = 0; persist(); }
        /** 完整对话轮次（含被截断的历史），仅用于本机存档展示。 */
        public synchronized List<String[]> all() { return snapshot(); }
        public synchronized int dropped() { return dropped; }

        /**
         * 本机持久化。只留在 App 私有目录，不上传。
         * 用最简单的行格式（type\tbase64(text)），避免引号/换行/中文被转义搞坏。
         */
        private static final String FILE = "turboio_android/history.tsv";
        private static java.io.File store;

        /** 由 AyaSuperAddon 在拿到 Context 后调用一次。 */
        public static synchronized void bind(android.content.Context context) {
            if (context == null) return;
            java.io.File dir = new java.io.File(context.getFilesDir(), "turboio_android");
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            store = new java.io.File(dir, "history.tsv");
        }

        private void persist() {
            java.io.File file = store;
            if (file == null) return;
            try (java.io.Writer w = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(file, false), java.nio.charset.StandardCharsets.UTF_8)) {
                w.write("#turboio-history-v1\tdropped=" + dropped + "\n");
                for (String[] row : messages) {
                    w.write(row[0] + "\t" + android.util.Base64.encodeToString(
                        row[1].getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP) + "\n");
                }
            } catch (Exception ignored) { }
        }

        /** 启动时恢复上次的会话。损坏的行直接跳过，不影响启动。 */
        public synchronized void restore() {
            java.io.File file = store;
            if (file == null || !file.isFile() || file.length() > 4 * 1024 * 1024) return;
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                messages.clear(); dropped = 0;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("#turboio-history-v1")) {
                        int i = line.indexOf("dropped=");
                        if (i >= 0) try { dropped = Integer.parseInt(line.substring(i + 8).trim()); } catch (Exception ignored) { }
                        continue;
                    }
                    int tab = line.indexOf('\t');
                    if (tab <= 0) continue;
                    String role = line.substring(0, tab);
                    if (!"user".equals(role) && !"assistant".equals(role)) continue;
                    try {
                        String text = new String(android.util.Base64.decode(line.substring(tab + 1),
                            android.util.Base64.NO_WRAP), java.nio.charset.StandardCharsets.UTF_8);
                        messages.add(new String[]{role, text});
                    } catch (Exception ignored) { }
                }
                while (messages.size() > 50) { messages.remove(0); dropped++; }
            } catch (Exception ignored) { }
        }
    }

    /** 供 UI 层访问当前进程的历史实例，避免把 HISTORY 字段暴露出去。 */
    public static final class HistoryAccessor {
        private static volatile History instance;
        public static void attach(History history) { instance = history; }
        private History target() { return instance; }
        public List<String[]> snapshot() { History h = target(); return h == null ? new ArrayList<>() : h.all(); }
        public int dropped() { History h = target(); return h == null ? 0 : h.dropped(); }
        public void clear() { History h = target(); if (h != null) h.clear(); }
    }
}
