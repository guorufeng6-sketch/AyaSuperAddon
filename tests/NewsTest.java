import com.turboio.addon.News;
import java.util.*;
public class NewsTest {
    static int n;static void check(boolean b){n++;if(!b)throw new AssertionError("case "+n);}
    public static void main(String[] args){
        // ① 微博：data 是对象数组，热度在 hot_value
        String wb="{\"code\":200,\"message\":\"ok\",\"data\":["
            +"{\"title\":\"梅毒越来越困扰年轻人\",\"hot_value\":\"1425355\",\"link\":\"https://s.weibo.com/x\"},"
            +"{\"title\":\"第二个热搜\",\"hot_value\":\"900\",\"link\":\"https://s.weibo.com/y\"}]}";
        List<News.Item> w=News.parse(News.WEIBO,wb);
        check(w.size()==2);
        check(w.get(0).title.equals("梅毒越来越困扰年轻人"));
        check(w.get(0).hot.equals("142万"));          // 1425355 → 142万
        check(w.get(1).title.equals("第二个热搜"));
        check(w.get(1).hot.equals("900"));            // 不满一万：原样
        check(w.get(0).link.equals("https://s.weibo.com/x"));

        // ② 知乎：热度在 hot_value_desc（"589 万热度"），摘要在 detail
        String zh="{\"code\":200,\"data\":[{\"title\":\"智谱Zcode被质疑\",\"detail\":\"官方回应\",\"hot_value_desc\":\"589 万热度\",\"link\":\"https://zhihu.com/q\"}]}";
        List<News.Item> z=News.parse(News.ZHIHU,zh);
        check(z.size()==1);
        check(z.get(0).title.equals("智谱Zcode被质疑"));
        check(z.get(0).hot.equals("589万"));          // 带「万」的不再除一次（不能变 589）
        check(z.get(0).desc.equals("官方回应"));

        // ③ 今日新闻：data.news 是字符串数组，没有热度
        String dy="{\"code\":200,\"data\":{\"date\":\"2026-09-19\",\"news\":[\"十部门出台促进房车消费新措施\",\"国庆假期部分列车票价\"],\"tip\":\"早安\"}}";
        List<News.Item> d=News.parse(News.DAILY,dy);
        check(d.size()==2);
        check(d.get(0).title.equals("十部门出台促进房车消费新措施"));
        check(d.get(0).hot.isEmpty());
        check(d.get(1).title.equals("国庆假期部分列车票价"));

        // ④ 转义：引号、Unicode 转义、标题里的大括号都不能把切分搞乱
        String esc="{\"data\":[{\"title\":\"他说\\\"你好\\\"\",\"hot_value\":\"10\"},"
            +"{\"title\":\"\\u4e2d\\u6587\",\"hot_value\":\"20\"},"
            +"{\"title\":\"a{b}c\",\"hot_value\":\"30\"}]}";
        List<News.Item> e=News.parseList(esc);
        check(e.size()==3);                                   // 标题里的 {} 不影响切分
        check(e.get(0).title.equals("他说\"你好\""));
        check(e.get(1).title.equals("中文"));                  // Unicode 转义解码
        check(e.get(2).title.equals("a{b}c"));

        // ⑤ 坏数据：null / 空串 / 没有 data / 截断的 JSON 都不能炸，只返回空
        check(News.parse(News.WEIBO,null).isEmpty());
        check(News.parse(News.WEIBO,"").isEmpty());
        check(News.parse(News.WEIBO,"{\"code\":404,\"data\":null}").isEmpty());
        check(News.parse(News.WEIBO,"{\"data\":[{\"title\":\"断\"").isEmpty()); // 截断：宁可报"没拿到"，不吐半条
        check(News.parse(null,null).isEmpty());                 // 榜单也认不出：仍不炸
        check(News.parse(News.DAILY,"{\"data\":{}}").isEmpty());

        // ⑥ hotText 边界
        check(News.hotText("1425355").equals("142万"));
        check(News.hotText("21253965").equals("2125万"));
        check(News.hotText("999").equals("999"));
        check(News.hotText("0").equals(""));
        check(News.hotText(null).equals(""));
        check(News.hotText("").equals(""));
        check(News.hotText("589 万热度").equals("589万"));
        check(News.hotText("1.2亿热度").equals("1.2亿")||News.hotText("1.2亿热度").equals("12亿"));

        // ⑦ 眼镜排版：固定 ≤5 行，带页码
        List<News.Item> many=new ArrayList<>();
        for(int i=1;i<=13;i++) many.add(new News.Item("第"+i+"条热搜标题内容","1000","",""));
        check(News.pages(many)==4);                            // 13 条 / 每页 4 条 = 4 页
        String p0=News.compose(News.WEIBO,many,0);
        check(p0.split("\n").length==5);                       // 表头 + 4 条 = 5 行，绝不多
        check(p0.contains("1/4"));
        check(p0.contains("微博热搜"));
        String pLast=News.compose(News.WEIBO,many,99);         // 越界夹到最后一页
        check(pLast.contains("4/4"));
        check(News.compose(News.WEIBO,null,0).contains("没拿到"));
        check(News.compose(News.WEIBO,new ArrayList<News.Item>(),0).contains("没拿到"));

        // ⑧ itemLine：热度占位后标题仍被 clip 在行宽内
        String line=News.itemLine(1,new News.Item("一个相当长的热搜标题需要被压缩掉一部分","142万","",""));
        check(line.startsWith("1. "));
        check(line.contains("142万"));
        check(News.width(line)<=News.LINE_MAX+0.5);

        // ⑨ 榜单元信息
        check(News.name(News.WEIBO).equals("微博热搜"));
        check(News.name(News.TOUTIAO).equals("头条热搜"));
        check(News.name(News.ZHIHU).equals("知乎热榜"));
        check(News.name(News.DAILY).equals("今日新闻"));
        check(News.path(News.DAILY).equals("60s"));
        check(News.path(News.ZHIHU).equals("zhihu"));
        check(News.path(null).equals("weibo"));                 // 认不出按微博
        check(News.boards().size()==4);
        check(News.boards().get(0).equals(News.WEIBO));

        // ⑩ 语音：认得出 / 选得对榜单
        check(News.isNewsQuery("看看热搜"));
        check(News.isNewsQuery("今天有什么新闻"));
        check(News.isNewsQuery("知乎热榜"));
        check(!News.isNewsQuery("今天天气怎么样"));              // 别抢天气
        check(!News.isNewsQuery("导航到太原南站"));              // 别抢导航
        check(News.board("知乎热榜").equals(News.ZHIHU));
        check(News.board("看看头条热搜").equals(News.TOUTIAO));
        check(News.board("今天有什么新闻").equals(News.DAILY));
        check(News.board("看看热搜").equals(News.WEIBO));        // 默认微博
        check(News.board(null).equals(News.WEIBO));

        System.out.println("News: "+n+" checks PASS（热榜解析 · 转义 · 坏数据 · 热度压缩 · 眼镜分页 · 语音选榜）");
    }
}
