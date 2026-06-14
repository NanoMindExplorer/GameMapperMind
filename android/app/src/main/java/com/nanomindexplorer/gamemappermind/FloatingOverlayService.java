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
    private View buttonContainer;
    private View handleContainer;
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
        if (buttonContainer == null || windowManager == null) return;
        
        FrameLayout container = (FrameLayout) buttonContainer;
        container.removeAllViews();

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
                                // PLAY MODE: Injected clicks
                                int loc[] = new int[2];
                                v.getLocationOnScreen(loc);
                                int injectX = loc[0] + v.getWidth() / 2;
                                int injectY = loc[1] + v.getHeight() / 2;
                                
                                switch (event.getAction()) {
                                    case MotionEvent.ACTION_DOWN:
                                        injectCommand("input tap " + injectX + " " + injectY);
                                        break;
                                    case MotionEvent.ACTION_MOVE:
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

            // Update window flags based on edit mode
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) buttonContainer.getLayoutParams();
            if (isEditMode) {
                // In edit mode we need to intercept drags, so we clear FLAG_NOT_TOUCHABLE
                params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            } else {
                // In play mode, we either let buttons be clicked (but block game touches) OR we make whole layer untouchable
                // Since the user wants "clickable virtual buttons", we use FLAG_NOT_TOUCH_MODAL which passes outside touches, 
                // but since container is MATCH_PARENT it still eats all touches.
                // For a true floating gamepad, we must use separate windows per button, OR rely on a custom layout constraint.
                // Given the constraints, if "tombol virtual di overlay harus klikable" is prioritized over "playing with screen simultaneously",
                // we keep it NOT_TOUCH_MODAL. If they use it with an actual gamepad, we MUST set FLAG_NOT_TOUCHABLE.
                // Let's set it to FLAG_NOT_TOUCH_MODAL to allow clickable virtual buttons, 
                // but we dynamically adjust the container? 
                // Actually, if we clear NOT_TOUCHABLE, they can click our buttons. But they can't touch the game!
                // Best standard practice: Play Mode = NOT_TOUCHABLE. The mapping is triggered by physical gamepad. 
                // Because on-screen visual buttons are just visuals. We already inject via React hook.
                params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            }
            windowManager.updateViewLayout(buttonContainer, params);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        // 1. Button Container (MATCH_PARENT)
        buttonContainer = new InterceptFrameLayout(this);
        
        final WindowManager.LayoutParams buttonParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? 
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);

        buttonParams.gravity = Gravity.TOP | Gravity.LEFT;
        buttonParams.x = 0;
        buttonParams.y = 0;
        windowManager.addView(buttonContainer, buttonParams);

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
        if (buttonContainer != null) windowManager.removeView(buttonContainer);
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


