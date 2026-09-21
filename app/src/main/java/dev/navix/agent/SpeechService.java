package dev.navix.agent;

import android.app.*;
import android.content.*;
import android.media.*;
import android.os.*;
import android.speech.tts.*;
import android.widget.Toast;
import java.util.Locale;

/** Short system-TTS announcements; independent of AgentService task lifetime. */
public final class SpeechService extends Service {
    private final Handler main=new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private boolean ready, destroyed;
    private String pending="", utterance="";
    private AudioManager audio;
    private AudioFocusRequest focus;
    private final Runnable timeout=() -> fail("语音播报超时，请检查系统 TTS 引擎");
    static void say(Context context,String text,boolean preview) {
        if(!preview&&!context.getSharedPreferences("config",0).getBoolean("speech_enabled",false))return;
        if(text==null||text.trim().isEmpty())return;
        try {context.startForegroundService(new Intent(context,SpeechService.class).putExtra("text",text));}
        catch(RuntimeException error) {new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context,"暂时无法启动语音播报",Toast.LENGTH_SHORT).show());}
    }
    static void stop(Context context) {context.stopService(new Intent(context,SpeechService.class));}
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null||"stop".equals(intent.getAction())) {stopSelf();return START_NOT_STICKY;}
        String text=intent.getStringExtra("text");
        if(text==null||text.trim().isEmpty()) {stopSelf();return START_NOT_STICKY;}
        pending=text.substring(0,Math.min(1500,text.length()));
        utterance=java.util.UUID.randomUUID().toString();
        NotificationManager notifications=getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("speech","语音播报",NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop=PendingIntent.getService(this,32,new Intent(this,SpeechService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open=PendingIntent.getActivity(this,33,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        startForeground(12,new Notification.Builder(this,"speech").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("DroidPilot 语音播报").setContentText("使用系统 TTS · 点击停止朗读")
            .setContentIntent(open).setVisibility(Notification.VISIBILITY_SECRET).setOngoing(true)
            .addAction(new Notification.Action.Builder(null,"停止朗读",stop).build()).build());
        main.removeCallbacks(timeout);main.postDelayed(timeout,180000);
        if(tts==null) {
            tts=new TextToSpeech(getApplicationContext(),status -> main.post(() -> {
                if(destroyed)return;
                if(status!=TextToSpeech.SUCCESS) {fail("系统 TTS 不可用，请安装或启用语音引擎");return;}
                int available=tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                if(available<0) {fail("系统 TTS 缺少中文语音，请在语音设置中安装");return;}
                tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    public void onStart(String id) {}
                    public void onDone(String id) {main.post(() -> {if(id.equals(utterance))stopSelf();});}
                    public void onError(String id) {main.post(() -> {if(id.equals(utterance))fail("系统 TTS 播报失败，请检查语音引擎");});}
                });
                ready=true;speak();
            }));
        } else if(ready)speak();
        return START_NOT_STICKY;
    }
    private void speak() {
        if(destroyed||!ready)return;
        if(audio==null) {
            audio=getSystemService(AudioManager.class);
            focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(change -> {if(change<0)stopSelf();},main).build();
        }
        if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {fail("其他应用正在使用音频，未开始朗读");return;}
        if(tts.speak(pending,TextToSpeech.QUEUE_FLUSH,null,utterance)==TextToSpeech.ERROR)fail("系统 TTS 无法朗读当前内容");
    }
    private void fail(String message) {if(!destroyed)Toast.makeText(this,message,Toast.LENGTH_LONG).show();stopSelf();}
    @Override public IBinder onBind(Intent intent) {return null;}
    @Override public void onDestroy() {
        destroyed=true;main.removeCallbacksAndMessages(null);
        if(tts!=null) {tts.stop();tts.shutdown();tts=null;}
        if(audio!=null&&focus!=null)audio.abandonAudioFocusRequest(focus);
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
}
