package dev.navix.agent;
import android.app.Activity;
import android.os.Bundle;
import android.widget.*;
public final class FixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        ScrollView scroll = new ScrollView(this); scroll.setId(R.id.test_scroll);
        LinearLayout column = new LinearLayout(this); column.setOrientation(1); column.setFocusableInTouchMode(true); column.requestFocus(); column.setPadding(24,48,24,24); scroll.addView(column);
        TextView status = new TextView(this); status.setId(R.id.test_status); status.setText("测试就绪"); column.addView(status);
        Button click = new Button(this); click.setId(R.id.test_click); click.setText("点击测试"); click.setOnClickListener(v -> status.setText("点击成功")); column.addView(click);
        Button hold = new Button(this); hold.setId(R.id.test_long); hold.setText("长按测试"); hold.setOnLongClickListener(v -> { status.setText("长按成功"); return true; }); column.addView(hold);
        EditText input = new EditText(this); input.setId(R.id.test_input); input.setHint("中文输入测试"); column.addView(input);
        EditText password = new EditText(this); password.setId(R.id.test_password); password.setInputType(129); password.setText("fixture-secret"); column.addView(password);
        for (int i=0;i<40;i++) { TextView row = new TextView(this); row.setText("滚动测试行 " + i); row.setPadding(0,24,0,24); column.addView(row); }
        setContentView(scroll);
    }
}
