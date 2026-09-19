import com.turboio.addon.WebSearch;
import java.util.*;
public class WebSearchTest {
    static int n;static void check(boolean b){n++;if(!b)throw new AssertionError("case "+n);}
    public static void main(String[] args){
        // ① 请求体：转义、条数夹取、Key 绝不进 body
        String b=WebSearch.bodyJson("量子计算 \"最新\" 进展",3);
        check(b.contains("\"max_results\":3"));
        check(b.contains("\\\"最新\\\""));          // 引号被 JSON 转义
        check(!b.contains("api_key"));              // Key 走 Authorization 头，不进 body
        check(WebSearch.bodyJson("x",99).contains("\"max_results\":20"));
        check(WebSearch.bodyJson("x",0).contains("\"max_results\":1"));
        check(WebSearch.bodyJson("x").contains("\"max_results\":5"));   // 默认 5 条
        check(WebSearch.bodyJson("a\nb").contains("\\n"));              // 换行被转义
        check(WebSearch.jstr(null).equals("\"\""));

        // ② 解析 results + answer
        String json="{\"query\":\"q\",\"answer\":\"这是摘要\",\"results\":["
            +"{\"title\":\"标题1\",\"url\":\"https://a.com\",\"content\":\"内容1\",\"score\":0.9},"
            +"{\"title\":\"标题2\",\"url\":\"https://b.com\",\"content\":\"内容2\"}],\"response_time\":1.2}";
        check(WebSearch.answer(json).equals("这是摘要"));
        List<WebSearch.Result> rs=WebSearch.parse(json);
        check(rs.size()==2);
        check(rs.get(0).title.equals("标题1"));
        check(rs.get(0).url.equals("https://a.com"));
        check(rs.get(0).content.equals("内容1"));
        check(rs.get(1).title.equals("标题2"));

        // ③ 坏数据不炸
        check(WebSearch.parse(null).isEmpty());
        check(WebSearch.parse("").isEmpty());
        check(WebSearch.parse("{}").isEmpty());
        check(WebSearch.parse("{\"results\":[]}").isEmpty());
        check(WebSearch.answer(null).equals(""));
        check(WebSearch.answer("{}").equals(""));

        // ④ 给模型的文本：必须带来源 URL 与条数，否则模型会当自己的知识编
        String t=WebSearch.toModelText(rs,"这是摘要");
        check(t.contains("这是摘要"));
        check(t.contains("https://a.com"));
        check(t.contains("标题1"));
        check(t.contains("共 2 条"));
        check(t.contains("联网检索"));
        check(WebSearch.toModelText(null,null).contains("没有检索到"));
        check(WebSearch.toModelText(new ArrayList<WebSearch.Result>(),"").contains("没有检索到"));

        // ⑤ 截断
        StringBuilder longOne=new StringBuilder();
        for(int i=0;i<300;i++) longOne.append("字");
        check(WebSearch.brief(longOne.toString(),220).length()==221);   // 220 字 + 省略号
        check(WebSearch.brief("短",220).equals("短"));
        check(WebSearch.brief(null,10).equals(""));

        // ⑥ 眼镜排版 ≤5 行
        List<WebSearch.Result> many=new ArrayList<>();
        for(int i=1;i<=9;i++) many.add(new WebSearch.Result("结果标题"+i,"https://x/"+i,"内容"));
        String c=WebSearch.compose(many,0);
        check(c.split("\n").length==5);
        check(c.contains("1/3"));
        check(WebSearch.compose(many,99).contains("3/3"));
        check(WebSearch.compose(null,0).contains("没结果"));
        check(WebSearch.compose(new ArrayList<WebSearch.Result>(),0).contains("没结果"));

        // ⑦ 问句识别与检索词提取
        check(WebSearch.isSearchQuery("帮我搜一下量子计算"));
        check(WebSearch.isSearchQuery("联网搜索一下太原天气"));
        check(!WebSearch.isSearchQuery("今天天气怎么样"));       // 别抢天气
        check(!WebSearch.isSearchQuery("导航到太原南站"));       // 别抢导航
        check(WebSearch.query("搜一下量子计算最新进展").equals("量子计算最新进展"));
        check(WebSearch.query("帮我搜一下太原天气").equals("太原天气"));
        check(WebSearch.query("联网搜索高铁票").equals("高铁票"));
        check(WebSearch.query("查").equals(""));                 // 剥完太短：不当检索词
        check(WebSearch.query(null).equals(""));

        System.out.println("WebSearch: "+n+" checks PASS（请求体 · 转义 · 解析 · 坏数据 · 模型文本 · 眼镜分页 · 检索词提取）");
    }
}
