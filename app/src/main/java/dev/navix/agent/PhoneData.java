package dev.navix.agent;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.telephony.*;
import org.json.*;
import java.util.*;

/** Read-only data access; runtime Android permissions remain authoritative. */
final class PhoneData {
    private static void permission(Context context,String name) {
        if(context.checkSelfPermission(name)!=PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("缺少权限 "+name+"，请在 DroidPilot 点击短信/号码权限设置");
    }
    static JSONObject sms(Context context,JSONObject q) throws Exception {
        permission(context,Manifest.permission.READ_SMS);
        String folder=q.optString("folder","inbox");
        if(!Arrays.asList("inbox","sent","all").contains(folder))throw new IllegalArgumentException("Invalid folder");
        int limit=q.optInt("limit",10); if(limit<1||limit>20)throw new IllegalArgumentException("limit must be 1..20");
        List<String> where=new ArrayList<>(),args=new ArrayList<>();
        if(q.has("address")) { String address=q.getString("address"); if(address.length()>200)throw new IllegalArgumentException("Address too long"); where.add("address = ?"); args.add(address); }
        for(String key:new String[]{"since_ms","before_id"})if(q.has(key)) {
            long value=q.getLong(key); if(value<0)throw new IllegalArgumentException("Invalid "+key);
            where.add(key.equals("since_ms")?"date >= ?":"_id < ?"); args.add(Long.toString(value));
        }
        JSONArray rows=new JSONArray(); boolean more=false; long last=-1;
        Uri uri=Uri.parse(folder.equals("all")?"content://sms":"content://sms/"+folder);
        try(Cursor cursor=context.getContentResolver().query(uri,new String[]{"_id","address","body","date","type","read"},
                where.isEmpty()?null:String.join(" AND ",where),args.isEmpty()?null:args.toArray(new String[0]),"_id DESC")) {
            if(cursor==null)throw new IllegalStateException("SMS provider unavailable");
            while(cursor.moveToNext()) {
                if(rows.length()==limit) { more=true; break; }
                last=cursor.getLong(0); String body=cursor.getString(2); if(body==null)body="";
                rows.put(new JSONObject().put("id",last).put("address",cursor.getString(1)).put("body",body.substring(0,Math.min(2000,body.length())))
                    .put("body_truncated",body.length()>2000).put("date_ms",cursor.getLong(3)).put("type",cursor.getInt(4)).put("read",cursor.getInt(5)!=0));
            }
        }
        JSONObject result=new JSONObject().put("ok",true).put("messages",rows).put("has_more",more);
        if(more)result.put("next_before_id",last);
        return result;
    }
    @SuppressWarnings("deprecation") static JSONObject numbers(Context context) throws Exception {
        permission(context,Manifest.permission.READ_PHONE_STATE); permission(context,Manifest.permission.READ_PHONE_NUMBERS);
        SubscriptionManager manager=context.getSystemService(SubscriptionManager.class);
        if(manager==null)throw new IllegalStateException("Subscription service unavailable");
        List<SubscriptionInfo> subscriptions=manager.getActiveSubscriptionInfoList(); JSONArray rows=new JSONArray();
        if(subscriptions!=null)for(SubscriptionInfo sim:subscriptions) {
            String number=Build.VERSION.SDK_INT>=33?manager.getPhoneNumber(sim.getSubscriptionId()):sim.getNumber();
            if(number==null)number="";
            JSONObject row=new JSONObject().put("subscription_id",sim.getSubscriptionId()).put("slot",sim.getSimSlotIndex())
                .put("carrier",String.valueOf(sim.getCarrierName())).put("number",number).put("available",!number.isEmpty());
            if(number.isEmpty())row.put("reason","SIM/运营商未提供本机号码，请询问用户；不要猜测");
            rows.put(row);
        }
        return new JSONObject().put("ok",true).put("subscriptions",rows).put("note","号码来源于系统订阅信息，不保证是账户绑定号码；没有活动 SIM 时列表为空");
    }
}
