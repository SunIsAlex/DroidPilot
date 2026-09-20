package dev.navix.agent;

import android.hardware.display.*;
import android.media.Image;
import android.media.ImageReader;
import android.graphics.PixelFormat;
import android.os.*;

/** Root-owned offscreen display with independent focus and an actively drained surface. */
final class BackgroundDisplay {
    private int id=-1;
    private ImageReader reader;
    private final IDisplayManager manager=IDisplayManager.Stub.asInterface(ServiceManager.getService("display"));
    private final IVirtualDisplayCallback callback=new IVirtualDisplayCallback.Stub() {
        public void onPaused() {}
        public void onResumed() {}
        public void onStopped() { }
    };
    int id() { return id; }
    org.json.JSONObject metrics() throws Exception {
        android.view.DisplayInfo info=id<0?null:manager.getDisplayInfo(id);
        return new org.json.JSONObject().put("ok",true).put("display_id",info==null?-1:id)
            .put("width",info==null?0:info.logicalWidth).put("height",info==null?0:info.logicalHeight)
            .put("density_dpi",info==null?0:info.logicalDensityDpi);
    }
    int open(Handler handler) throws Exception {
        android.view.DisplayInfo primary=manager.getDisplayInfo(0);
        if(primary==null)throw new IllegalStateException("无法读取主屏显示配置");
        if(id>=0&&manager.getDisplayInfo(id)!=null) {
            android.view.DisplayInfo current=manager.getDisplayInfo(id);
            if(current.logicalWidth!=primary.logicalWidth||current.logicalHeight!=primary.logicalHeight||current.logicalDensityDpi!=primary.logicalDensityDpi)
                throw new IllegalStateException("主屏配置已变化，请先关闭后台虚拟屏，再重新开始任务");
            return id;
        }
        if(id>=0)close();
        if(Build.VERSION.SDK_INT<34)throw new IllegalStateException("后台独立焦点模式需要 Android 14 或以上");
        reader=ImageReader.newInstance(primary.logicalWidth,primary.logicalHeight,PixelFormat.RGBA_8888,3);
        reader.setOnImageAvailableListener(source -> {
            try(Image frame=source.acquireLatestImage()) { /* Drain without storing pixels. */ }
            catch(IllegalStateException ignored) {}
        },handler);
        try {
            int flags=DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_FOCUS
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED;
            VirtualDisplayConfig config=new VirtualDisplayConfig.Builder("DroidPilot Background",primary.logicalWidth,primary.logicalHeight,primary.logicalDensityDpi)
                .setFlags(flags).setSurface(reader.getSurface()).setRequestedRefreshRate(30f).build();
            id=manager.createVirtualDisplay(config,callback,null,"com.android.shell");
            if(id<0)throw new IllegalStateException("系统拒绝创建独立虚拟屏");
            return id;
        } catch(Exception|LinkageError e) {
            close(); throw new IllegalStateException("后台虚拟屏不可用："+e.getClass().getSimpleName()+": "+e.getMessage(),e);
        }
    }
    void close() {
        try { if(id>=0)manager.releaseVirtualDisplay(callback); } catch(Exception ignored) {}
        id=-1;
        if(reader!=null) { reader.close(); reader=null; }
    }
}
