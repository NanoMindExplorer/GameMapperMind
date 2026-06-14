package com.nanomindexplorer.gamemappermind;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.graphics.Color;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.DataOutputStream;

public class FloatingOverlayService extends Service {
    private WindowManager windowManager;
    private View handleContainer;
    private java.util.List<View> virtualButtonWindows = new java.util.ArrayList<>();
    private static final String CHANNEL_ID = "OverlayServiceChannel";
    private boolean isEditMode = false;
    private String currentConfigJson = "{}";
    
    // Persistent Shizuku Shell for ultra-fast injection
    private Process shizukuProcess;
    private DataOutputStream shizukuOut;

    public FloatingOverlayService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Overlay Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }
    
    private void initShizukuDaemon() {
        try {
            if (rikka.shizuku.Shizuku.pingBinder() && rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                if (shizukuProcess == null) {
                    Log.d("GameMapper", "Starting persistent Shizuku shell process");
                    java.lang.reflect.Method method = rikka.shizuku.Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
                    method.setAccessible(true);
                    shizukuProcess = (Process) method.invoke(null, new String[]{"sh"}, null, null);
                    shizukuOut = new DataOutputStream(shizukuProcess.getOutputStream());
                    Log.d("GameMapper", "Shizuku daemon shell started successfully");
                }
            } else {
                Log.w("GameMapper", "Shizuku not ready or permission denied");
            }
        } catch (Exception e) {
            Log.e("GameMapper", "Failed to init Shizuku daemon", e);
        }
    }

    private void injectCommand(String cmd) {
        if (shizukuOut != null) {
            try {
                Log.d("GameMapper", "Injecting fast command: " + cmd);
                shizukuOut.writeBytes(cmd + "\n");
                shizukuOut.flush();
            } catch (Exception e) {
                Log.e("GameMapper", "Fast injection failed", e);
                new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(FloatingOverlayService.this, "Shizuku injection failed!", Toast.LENGTH_SHORT).show());
            }
        } else {
            Log.w("GameMapper", "Cannot inject, Shizuku daemon out stream is null");
            new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(FloatingOverlayService.this, "Shizuku not connected!", Toast.LENGTH_SHORT).show());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        initShizukuDaemon();
        
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("GameMapperMind Overlay")
                .setContentText("Overlay is running")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(pendingIntent)
                .build();
        
        startForeground(1, notification);

        if (intent != null && intent.hasExtra("config")) {
            currentConfigJson = intent.getStringExtra("config");
            updateOverlayViews(currentConfigJson);
        }

        return START_STICKY;
    }

    private void updateOverlayViews(String configJson) {
        if (windowManager == null) return;
        
        for (View v : virtualButtonWindows) {
            try {
                windowManager.removeView(v);
            } catch (Exception e) {}
        }
        virtualButtonWindows.clear();

        try {
            JSONObject profile = new JSONObject(configJson);
            if (profile.has("buttons")) {
                JSONArray buttons = profile.getJSONArray("buttons");
                int screenWidth = getResources().getDisplayMetrics().widthPixels;
                int screenHeight = getResources().getDisplayMetrics().heightPixels;

                for (int i = 0; i < buttons.length(); i++) {
                    JSONObject btn = buttons.getJSONObject(i);
                    TextView btnView = new TextView(this);
                    btnView.setText(btn.optString("label", "btn"));
                    btnView.setTextColor(Color.WHITE);
                    btnView.setGravity(Gravity.CENTER);
                    
                    android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                    gd.setColor(isEditMode ? Color.parseColor("#901e293b") : Color.parseColor("#401e293b")); // semi transparent in play mode visible
                    gd.setCornerRadius(100f);
                    gd.setStroke(isEditMode ? 4 : 2, Color.parseColor("#818cf8"));
                    btnView.setBackground(gd);
                    
                    int w = btn.optInt("width", 100);
                    int h = btn.optInt("height", 100);
                    double xPct = btn.optDouble("x", 50) / 100.0;
                    double yPct = btn.optDouble("y", 50) / 100.0;
                    
                    final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                            w,
                            h,
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? 
                                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                            PixelFormat.TRANSLUCENT);
                    params.gravity = Gravity.TOP | Gravity.LEFT;
                    params.x = (int)(screenWidth * xPct) - w/2;
                    params.y = (int)(screenHeight * yPct) - h/2;
                    
                    btnView.setAlpha(isEditMode ? 1.0f : (float)(btn.optDouble("opacity", 100) / 100.0));
                    windowManager.addView(btnView, params);
                    virtualButtonWindows.add(btnView);
                    
                    final String btnId = btn.optString("id");
                    
                    // Setup touch listeners
                    btnView.setOnTouchListener(new View.OnTouchListener() {
                        private int initialX;
                        private int initialY;
                        private float initialTouchX;
                        private float initialTouchY;

                        @Override
                        public boolean onTouch(View v, MotionEvent event) {
                            if (isEditMode) {
                                switch (event.getAction()) {
                                    case MotionEvent.ACTION_DOWN:
                                        initialX = params.x;
                                        initialY = params.y;
                                        initialTouchX = event.getRawX();
                                        initialTouchY = event.getRawY();
                                        return true;
                                    case MotionEvent.ACTION_MOVE:
                                        params.x = initialX + (int)(event.getRawX() - initialTouchX);
                                        params.y = initialY + (int)(event.getRawY() - initialTouchY);
                                        windowManager.updateViewLayout(v, params);
                                        return true;
                                }
                            } else {
                                // PLAY MODE: Injected clicks
                                int loc[] = new int[2];
                                v.getLocationOnScreen(loc);
                                int injectX = loc[0] + v.getWidth() / 2;
                                int injectY = loc[1] + v.getHeight() / 2;
                                
                                switch (event.getAction()) {
                                    case MotionEvent.ACTION_DOWN:
                                        Log.d("GameMapper", "Virtual button clicked: " + btnId + " at " + injectX + ", " + injectY);
                                        injectCommand("input swipe " + injectX + " " + injectY + " " + injectX + " " + injectY + " 30");
                                        v.setAlpha(0.5f); // feedback
                                        break;
                                    case MotionEvent.ACTION_UP:
                                    case MotionEvent.ACTION_CANCEL:
                                        v.setAlpha((float)(btn.optDouble("opacity", 100) / 100.0));
                                        break;
                                }
                                return true;
                            }
                            return false;
                        }
                    });
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        // 2. Handle Container (WRAP_CONTENT)
        handleContainer = new FrameLayout(this);
        TextView icon = new TextView(this);
        icon.setText("🎮");
        icon.setTextSize(20f);
        icon.setGravity(Gravity.CENTER);
        
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(Color.parseColor("#1e293b"));
        gd.setCornerRadius(100f);
        gd.setStroke(3, Color.parseColor("#10b981"));
        icon.setBackground(gd);

        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(120, 120);
        ((FrameLayout)handleContainer).addView(icon, iconParams);

        final WindowManager.LayoutParams handleWindowParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? 
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        handleWindowParams.gravity = Gravity.TOP | Gravity.LEFT;
        handleWindowParams.x = 50;
        handleWindowParams.y = 50;
        
        windowManager.addView(handleContainer, handleWindowParams);

        icon.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;
            private boolean isClick;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = handleWindowParams.x;
                        initialY = handleWindowParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isClick = true;
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (isClick) {
                            // Toggle Edit Mode
                            isEditMode = !isEditMode;
                            android.graphics.drawable.GradientDrawable bg = (android.graphics.drawable.GradientDrawable) v.getBackground();
                            bg.setStroke(3, isEditMode ? Color.parseColor("#ef4444") : Color.parseColor("#10b981"));
                            v.setBackground(bg);
                            updateOverlayViews(currentConfigJson);
                        }
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(event.getRawX() - initialTouchX) > 10 || Math.abs(event.getRawY() - initialTouchY) > 10) {
                            isClick = false;
                        }
                        handleWindowParams.x = initialX + (int)(event.getRawX() - initialTouchX);
                        handleWindowParams.y = initialY + (int)(event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(handleContainer, handleWindowParams);
                        return true;
                }
                return false;
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        for (View v : virtualButtonWindows) {
            try {
                windowManager.removeView(v);
            } catch (Exception e) {}
        }
        virtualButtonWindows.clear();

        if (handleContainer != null) windowManager.removeView(handleContainer);
        if (shizukuOut != null) {
            try {
                shizukuOut.writeBytes("exit\n");
                shizukuOut.flush();
                shizukuOut.close();
            } catch (Exception e) {}
        }
        if (shizukuProcess != null) shizukuProcess.destroy();
    }
}


