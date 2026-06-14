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
import androidx.core.app.NotificationCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.DataOutputStream;

public class FloatingOverlayService extends Service {
    private WindowManager windowManager;
    private View floatingContainer;
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
                    java.lang.reflect.Method method = rikka.shizuku.Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
                    method.setAccessible(true);
                    shizukuProcess = (Process) method.invoke(null, new String[]{"sh"}, null, null);
                    shizukuOut = new DataOutputStream(shizukuProcess.getOutputStream());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void injectCommand(String cmd) {
        if (shizukuOut != null) {
            try {
                shizukuOut.writeBytes(cmd + "\n");
                shizukuOut.flush();
            } catch (Exception e) {
                e.printStackTrace();
            }
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
        if (floatingContainer == null || windowManager == null) return;
        
        FrameLayout container = (FrameLayout) floatingContainer;
        View dragHandle = container.getChildAt(0); // The main draggable handle
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
                    gd.setColor(isEditMode ? Color.parseColor("#901e293b") : Color.TRANSPARENT);
                    gd.setCornerRadius(100f);
                    gd.setStroke(isEditMode ? 4 : 0, Color.parseColor("#818cf8"));
                    btnView.setBackground(gd);
                    
                    int w = btn.optInt("width", 100);
                    int h = btn.optInt("height", 100);
                    double xPct = btn.optDouble("x", 50) / 100.0;
                    double yPct = btn.optDouble("y", 50) / 100.0;
                    
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
                    lp.leftMargin = (int)(screenWidth * xPct) - w/2;
                    lp.topMargin = (int)(screenHeight * yPct) - h/2;
                    
                    btnView.setAlpha(isEditMode ? 1.0f : (float)(btn.optDouble("opacity", 100) / 100.0));
                    container.addView(btnView, lp);
                    
                    final String btnId = btn.optString("id");
                    
                    // Setup touch listeners for both Edit Mode (Drag) and Play Mode (Inject)
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
                                        initialX = ((FrameLayout.LayoutParams) v.getLayoutParams()).leftMargin;
                                        initialY = ((FrameLayout.LayoutParams) v.getLayoutParams()).topMargin;
                                        initialTouchX = event.getRawX();
                                        initialTouchY = event.getRawY();
                                        return true;
                                    case MotionEvent.ACTION_MOVE:
                                        int dx = (int)(event.getRawX() - initialTouchX);
                                        int dy = (int)(event.getRawY() - initialTouchY);
                                        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
                                        lp.leftMargin = initialX + dx;
                                        lp.topMargin = initialY + dy;
                                        container.updateViewLayout(v, lp);
                                        return true;
                                }
                            } else {
                                // PLAY MODE: Inject touch via Shizuku!
                                // Find exact center of the button
                                int loc[] = new int[2];
                                v.getLocationOnScreen(loc);
                                int injectX = loc[0] + v.getWidth() / 2;
                                int injectY = loc[1] + v.getHeight() / 2;
                                
                                switch (event.getAction()) {
                                    case MotionEvent.ACTION_DOWN:
                                        injectCommand("input tap " + injectX + " " + injectY);
                                        break;
                                    case MotionEvent.ACTION_MOVE:
                                        // implement swipe if needed
                                        break;
                                    case MotionEvent.ACTION_UP:
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
        floatingContainer = new InterceptFrameLayout(this);
        InterceptFrameLayout container = (InterceptFrameLayout) floatingContainer;

        // This is the draggable handle / Edit Mode toggle
        TextView icon = new TextView(this);
        icon.setText("🎮");
        icon.setTextSize(20f);
        icon.setGravity(Gravity.CENTER);
        
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(Color.parseColor("#1e293b"));
        gd.setCornerRadius(100f);
        gd.setStroke(3, Color.parseColor("#10b981"));
        icon.setBackground(gd);

        FrameLayout.LayoutParams handleParams = new FrameLayout.LayoutParams(120, 120);
        handleParams.leftMargin = 50;
        handleParams.topMargin = 50;
        container.addView(icon, handleParams);

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
                        initialX = ((FrameLayout.LayoutParams) v.getLayoutParams()).leftMargin;
                        initialY = ((FrameLayout.LayoutParams) v.getLayoutParams()).topMargin;
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
                            // Refresh layout to update borders
                            updateOverlayViews(currentConfigJson);
                        }
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(event.getRawX() - initialTouchX) > 10 || Math.abs(event.getRawY() - initialTouchY) > 10) {
                            isClick = false;
                        }
                        int dx = (int)(event.getRawX() - initialTouchX);
                        int dy = (int)(event.getRawY() - initialTouchY);
                        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
                        lp.leftMargin = initialX + dx;
                        lp.topMargin = initialY + dy;
                        container.updateViewLayout(v, lp);
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


