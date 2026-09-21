package dev.navix.agent;
import org.json.*;
public final class WebSearchTest {
    static void expect(boolean value,String message){if(!value)throw new AssertionError(message);}
    static JSONObject args(Object query,Object limit)throws Exception{return new JSONObject().put("query",query).put("limit",limit);}
    static void reject(JSONObject q)throws Exception{
        try {Protocol.parseCall(new JSONObject().put("function",new JSONObject().put("name","web_search").put("arguments",q.toString())));throw new AssertionError("Accepted invalid search");}
        catch(IllegalArgumentException|JSONException expected){}
    }
    public static void main(String[] flags)throws Exception {
        WebSearch.validate(args("Android 无障碍 🌏",3));
        for(JSONObject q:new JSONObject[]{args(" ",3),args(42,3),args("a\nb",3),args("x",0),args("x",9),args("x",1.5),args("x","3"),args(new String(new char[501]).replace('\0','a'),3)})reject(q);
        String item="<item><title>中文 &amp; Android</title><link>https://developer.android.com/</link><description><![CDATA[<b>摘要</b> external data]]></description></item>";
        JSONObject parsed=WebSearch.parse("<rss><channel>"+item+item+"<item><title>Bad</title><link>javascript:alert(1)</link></item><item><title>Other</title><link>https://example.com/</link></item></channel></rss>","中文",5);
        expect(parsed.getInt("result_count")==2,"Deduplicate links and reject unsafe schemes");
        expect(parsed.getJSONArray("results").getJSONObject(0).getString("title").equals("中文 & Android"),"Unicode and entities");
        expect(!parsed.getJSONArray("results").getJSONObject(0).getString("snippet").contains("<b>"),"Strip HTML");
        expect(WebSearch.parse("<rss><channel>"+item+item+"</channel></rss>","x",1).getInt("result_count")==1,"Limit");
        expect(WebSearch.parse("<rss><channel/></rss>","x",5).getInt("result_count")==0,"Empty results remain empty");
        for(String xml:new String[]{"<html>captcha</html>","<rss>","<!DOCTYPE rss [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><rss>&x;</rss>"}) {
            boolean failed=false;try{WebSearch.parse(xml,"x",5);}catch(Exception e){failed=true;}expect(failed,"Reject non-RSS, malformed XML and XXE");
        }
        WebSearch cancelled=new WebSearch();cancelled.cancel();
        try{cancelled.search(args("test",1));throw new AssertionError("Cancelled search started");}catch(InterruptedException expected){}
        boolean found=false;JSONArray tools=TaskPolicy.tools(true);
        for(int i=0;i<tools.length();i++)if(tools.getJSONObject(i).getJSONObject("function").getString("name").equals("web_search"))found=true;
        expect(found,"Search exposed in model contract");
        expect(!TaskPolicy.describe(new JSONObject().put("op","web_search").put("query","private-query")).contains("private-query"),"Toast does not disclose query");
        System.out.println("PASS: search validation, Unicode, limits, deduplication, unsafe URLs, XML/XXE failures, cancellation and model schema");
        if(flags.length>0&&flags[0].equals("--live")) {
            JSONObject live=new WebSearch().search(args("Android developer documentation",3));
            expect(live.getInt("result_count")>0&&live.getInt("result_count")<=3,"Live results");
            System.out.println("PASS: live public search returned "+live.getInt("result_count")+" results from "+live.getString("provider"));
        }
    }
}
