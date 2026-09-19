package dev.navix.agent;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

/** Shows execution phases, never private model reasoning or typed content. */
final class TaskOverlay {
    enum Phase { THINKING, ACTING, OBSERVING, WAITING, PAUSED, PAUSING, STOPPING, FINISHED, QUESTION }
    private final Context context;
    private final WindowManager windows;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout panel;
    private TextView title, detail;
    private boolean closed, warned;
    private final Runnable click;
    private float positionX=1f, positionY=0.07f;
    private float downX, downY;
    private int startX, startY;
    private boolean dragging;
    private int dispatches, touchDowns, touchUps, touchCancels, clicks;
    String diagnostics() { return "dispatch="+dispatches+" down="+touchDowns+" up="+touchUps+" cancel="+touchCancels+" clicks="+clicks+" dragging="+dragging; }
    private final class TouchPanel extends LinearLayout {
        TouchPanel() { super(context); }
        @Override public boolean dispatchTouchEvent(MotionEvent event) { dispatches++; return super.dispatchTouchEvent(event); }
        @Override public boolean onInterceptTouchEvent(MotionEvent event) { return true; }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if(getLayoutParams()==null)return false;
            WindowManager.LayoutParams layout=(WindowManager.LayoutParams)getLayoutParams();
            switch(event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touchDowns++;
                    downX=event.getRawX(); downY=event.getRawY(); startX=layout.x; startY=layout.y;
                    dragging=false; return true;
                case MotionEvent.ACTION_MOVE:
                    float dx=event.getRawX()-downX, dy=event.getRawY()-downY;
                    int slop=ViewConfiguration.get(context).getScaledTouchSlop();
                    if(dx*dx+dy*dy>slop*slop)dragging=true;
                    if(dragging)moveTo(startX+Math.round(dx),startY+Math.round(dy));
                    return true;
                case MotionEvent.ACTION_UP:
                    touchUps++;
                    if(dragging)savePosition();
                    else performClick();
                    dragging=false; return true;
                case MotionEvent.ACTION_CANCEL:
                    touchCancels++;
                    if(dragging)savePosition();
                    dragging=false; return true;
                default: return true;
            }
        }
        @Override public boolean performClick() { clicks++; super.performClick(); return true; }
    }
    private android.graphics.Rect available() {
        if(android.os.Build.VERSION.SDK_INT>=30) {
            WindowMetrics metrics=windows.getCurrentWindowMetrics();
            android.graphics.Rect bounds=new android.graphics.Rect(metrics.getBounds());
            android.graphics.Insets insets=metrics.getWindowInsets().getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            bounds.left+=insets.left; bounds.top+=insets.top; bounds.right-=insets.right; bounds.bottom-=insets.bottom;
            return bounds;
        }
        android.util.DisplayMetrics metrics=new android.util.DisplayMetrics(); windows.getDefaultDisplay().getRealMetrics(metrics);
        return new android.graphics.Rect(0,dp(28),metrics.widthPixels,metrics.heightPixels-dp(48));
    }
    private android.graphics.Rect movementBounds() {
        android.graphics.Rect bounds=available();
        int width=panel.getWidth()>0?panel.getWidth():((WindowManager.LayoutParams)panel.getLayoutParams()).width;
        int height=panel.getHeight()>0?panel.getHeight():dp(80);
        bounds.left+=dp(8); bounds.top+=dp(8);
        bounds.right=Math.max(bounds.left,bounds.right-width-dp(8));
        bounds.bottom=Math.max(bounds.top,bounds.bottom-height-dp(8));
        return bounds;
    }
    private void moveTo(int x,int y) {
        if(panel==null)return;
        android.graphics.Rect bounds=movementBounds();
        WindowManager.LayoutParams layout=(WindowManager.LayoutParams)panel.getLayoutParams();
        layout.x=Math.max(bounds.left,Math.min(bounds.right,x));
        layout.y=Math.max(bounds.top,Math.min(bounds.bottom,y));
        positionX=bounds.width()==0?0f:(float)(layout.x-bounds.left)/bounds.width();
        positionY=bounds.height()==0?0f:(float)(layout.y-bounds.top)/bounds.height();
        try { windows.updateViewLayout(panel,layout); } catch(RuntimeException ignored) {}
    }
    private void savePosition() {
        context.getSharedPreferences("overlay_position",0).edit().putFloat("x",positionX).putFloat("y",positionY).apply();
    }
    void reposition() {
        main.post(() -> {
            if(panel==null||closed||dragging)return;
            WindowManager.LayoutParams layout=(WindowManager.LayoutParams)panel.getLayoutParams();
            layout.width=Math.min(dp(248),Math.max(dp(80),available().width()-dp(24)));
            android.graphics.Rect bounds=movementBounds();
            moveTo(bounds.left+Math.round(bounds.width()*positionX),bounds.top+Math.round(bounds.height()*positionY));
        });
    }
    TaskOverlay(Context context, Runnable click) {
        this.click=click;
        this.context = context;
        windows = context.getSystemService(WindowManager.class);
        android.content.SharedPreferences prefs=context.getSharedPreferences("overlay_position",0);
        positionX=Math.max(0f,Math.min(1f,prefs.getFloat("x",1f)));
        positionY=Math.max(0f,Math.min(1f,prefs.getFloat("y",0.07f)));
    }
    private int dp(int n) { return Math.round(n * context.getResources().getDisplayMetrics().density); }
    void show(Phase phase, String summary) {
        main.post(() -> {
            if (closed) return;
            if (!context.getSharedPreferences("config",0).getBoolean("task_overlay",true)) { remove(); return; }
            if (!Settings.canDrawOverlays(context)) { remove(); unavailable(); return; }
            try {
                if (panel == null) {
                    panel = new TouchPanel(); panel.setOrientation(LinearLayout.VERTICAL);
                    panel.setPadding(dp(12),dp(8),dp(12),dp(8));
                    panel.setOnClickListener(view -> click.run());
                    panel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                    GradientDrawable background = new GradientDrawable();
                    background.setColor(Color.rgb(22,29,42)); background.setCornerRadius(dp(14)); panel.setBackground(background);
                    title = new TextView(context); title.setTextSize(14); title.setMaxLines(1);
                    detail = new TextView(context); detail.setTextSize(12); detail.setTextColor(Color.WHITE);
                    detail.setMaxLines(2); detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    panel.addView(title); panel.addView(detail);
                    WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        Math.min(dp(248),context.getResources().getDisplayMetrics().widthPixels-dp(24)),
                        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_SECURE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
                    params.alpha = 1f;
                    params.gravity = Gravity.TOP | Gravity.LEFT;
                    if(android.os.Build.VERSION.SDK_INT>=30)params.setFitInsetsTypes(0);
                    android.graphics.Rect initial=available();
                    params.x=initial.left+dp(8)+Math.round(Math.max(0,initial.width()-params.width-dp(16))*positionX);
                    params.y=initial.top+dp(8)+Math.round(Math.max(0,initial.height()-dp(96))*positionY);
                    params.setTitle("DroidPilot 任务状态");
                    windows.addView(panel,params);
                    panel.addOnLayoutChangeListener((view,l,t,r,b,ol,ot,or,ob) -> { if(r-l!=or-ol||b-t!=ob-ot)reposition(); });
                    reposition();
                }
                WindowManager.LayoutParams layout=(WindowManager.LayoutParams)panel.getLayoutParams();
                // Keep the input window fully opaque. On this device, lowering the
                // window alpha prevented touches from reaching dispatchTouchEvent.
                // Fade the content instead; visual appearance stays translucent.
                layout.alpha=1f;
                panel.setAlpha(phase==Phase.FINISHED ? 0.45f : 0.92f);
                windows.updateViewLayout(panel,layout);
                String label; int color;
                switch (phase) {
                    case ACTING: label="● 正在操控手机"; color=Color.rgb(255,188,90); break;
                    case OBSERVING: label="● 正在读取页面 · 未操作"; color=Color.rgb(108,220,201); break;
                    case WAITING: label="● 等待中 · 未操作"; color=Color.rgb(207,214,225); break;
                    case PAUSED: label="● 已暂停 · 未操作"; color=Color.rgb(255,188,90); break;
                    case QUESTION: label="● 等待你回答"; color=Color.rgb(255,188,90); break;
                    case FINISHED: label="● 任务已结束 · DroidPilot"; color=Color.rgb(207,214,225); break;
                    case PAUSING: label="● 正在暂停"; color=Color.rgb(255,188,90); break;
                    case STOPPING: label="● 正在停止"; color=Color.rgb(255,155,155); break;
                    default: label="● 模型思考中 · 未操作"; color=Color.rgb(139,182,255);
                }
                title.setText(label); title.setTextColor(color);
                String hint=phase==Phase.QUESTION ? "点击打开助手，回答后继续任务" : phase==Phase.FINISHED ? summary : phase==Phase.PAUSED ? summary : phase==Phase.PAUSING ? "等待当前请求返回；再次点击可继续" : phase==Phase.STOPPING ? summary : summary+" · 点击暂停";
                detail.setText(hint.substring(0,Math.min(100,hint.length())));
            } catch (RuntimeException e) { remove(); unavailable(); }
        });
    }
    private void unavailable() {
        if (!warned) {
            warned = true;
            Toast.makeText(context,"悬浮状态不可用，请在助手中开启悬浮窗权限；任务继续运行",Toast.LENGTH_LONG).show();
        }
    }
    private void remove() {
        if (panel != null) { try { windows.removeViewImmediate(panel); } catch (RuntimeException ignored) {} panel=null; }
    }
    void close() { main.post(() -> { closed = true; remove(); }); }
}
