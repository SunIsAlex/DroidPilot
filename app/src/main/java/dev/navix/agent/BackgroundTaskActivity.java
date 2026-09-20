package dev.navix.agent;

import android.app.Activity;
import android.os.Bundle;
import org.json.JSONObject;

/** User-selected recent-task entry; the real task stays on the virtual display until tapped. */
public final class BackgroundTaskActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        android.widget.TextView text=new android.widget.TextView(this);
        text.setText("DroidPilot · 正在将后台应用移回主屏…"); text.setPadding(32,80,32,32); setContentView(text);
        try {
            BackgroundTasks.restore(this,new JSONObject().put("task_id",getIntent().getIntExtra("task_id",-1))
                .put("component",getIntent().getStringExtra("component")),true);
        } catch(Exception e) { finishAndRemoveTask(); }
    }
}
