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
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.graphics.Color;
import androidx.core.app.NotificationCompat;
import org.json.JSONArray;
import org.json.JSONObject;

public class FloatingOverlayService extends Service {
    private WindowManager windowManager;
    private View floatingContainer;
    private static final String CHANNEL_ID = "OverlayServiceChannel";

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

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
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
            String configJson = intent.getStringExtra("config");
            updateOverlayViews(configJson);
        }

        return START_STICKY;
    }

    private void updateOverlayViews(String configJson) {
        if (floatingContainer == null || windowManager == null) return;
        
        FrameLayout container = (FrameLayout) floatingContainer;
        // Keep the drag handle
        View dragHandle = container.getChildAt(0);
        container.removeAllViews();
        container.addView(dragHandle);

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
                    gd.setColor(Color.parseColor("#801e293b")); // semi-transparent slate
                    gd.setCornerRadius(100f);
                    gd.setStroke(4, Color.parseColor("#818cf8"));
                    btnView.setBackground(gd);
                    
                    int w = btn.optInt("width", 50);
                    int h = btn.optInt("height", 50);
                    double xPct = btn.optDouble("x", 0) / 100.0;
                    double yPct = btn.optDouble("y", 0) / 100.0;
                    
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
                    lp.leftMargin = (int)(screenWidth * xPct);
                    lp.topMargin = (int)(screenHeight * yPct);
                    
                    btnView.setAlpha((float)(btn.optDouble("opacity", 100) / 100.0));
                    container.addView(btnView, lp);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void injectTouchViaShizuku(int x, int y) {
        try {
            if (rikka.shizuku.Shizuku.pingBinder() && rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                java.lang.reflect.Method method = rikka.shizuku.Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
                method.setAccessible(true);
                // Add randomization for anti-ban
                int dx = x + (int)((Math.random() - 0.5) * 10);
                int dy = y + (int)((Math.random() - 0.5) * 10);
                method.invoke(null, new String[]{"sh", "-c", "input tap " + dx + " " + dy}, null, null);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        floatingContainer = new InterceptFrameLayout(this);
        InterceptFrameLayout container = (InterceptFrameLayout) floatingContainer;
        
        container.setInputEventListener(new InterceptFrameLayout.InputEventListener() {
            @Override
            public boolean onGamepadEvent(MotionEvent event) {
                return false;
            }

            @Override
            public boolean onKeyEvent(KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    // Try to map to Shizuku command
                    injectTouchViaShizuku(500, 500); // Placeholder coordinate
                }
                return false;
            }
        });
        
        // This is the draggable handle
        TextView icon = new TextView(this);
        icon.setText("🎮");
        icon.setTextSize(18f);
        icon.setBackgroundColor(Color.parseColor("#1e293b"));
        icon.setTextColor(Color.WHITE);
        icon.setPadding(15, 15, 15, 15);
        
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(Color.parseColor("#1e293b"));
        gd.setCornerRadius(100f);
        gd.setStroke(2, Color.parseColor("#818cf8"));
        icon.setBackground(gd);

        FrameLayout.LayoutParams handleParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        container.addView(icon, handleParams);

        // We make the whole container match_parent, but NOT_FOCUSABLE and NOT_TOUCH_MODAL
        // so touches pass through where there are no views!
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? 
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = 0;
        params.y = 0;

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        windowManager.addView(floatingContainer, params);

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
                        initialX = (int)v.getX();
                        initialY = (int)v.getY();
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isClick = true;
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (isClick) {
                            Intent intent = new Intent(FloatingOverlayService.this, MainActivity.class);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                            startActivity(intent);
                        }
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(event.getRawX() - initialTouchX) > 10 || Math.abs(event.getRawY() - initialTouchY) > 10) {
                            isClick = false;
                        }
                        v.setX(initialX + (event.getRawX() - initialTouchX));
                        v.setY(initialY + (event.getRawY() - initialTouchY));
                        return true;
                }
                return false;
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (floatingContainer != null) windowManager.removeView(floatingContainer);
    }
}

