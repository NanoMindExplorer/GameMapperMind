/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 * @author NanoMind Explorer
 * 
 * capacitor.config.ts - Capacitor Configuration
 * 
 * Konfigurasi Capacitor untuk aplikasi GameMapperMind.
 * Dioptimalkan untuk:
 * - Low-latency gamepad input processing (< 8ms)
 * - Touch injection via Shizuku/ADB
 * - Overlay WYSIWYG editor
 * - Background daemon service
 * - Anti-ban randomization
 * 
 * Konfigurasi ini mencakup:
 * - App metadata (appId, appName, version)
 * - Android-specific optimizations
 * - Plugin configurations
 * - Server settings
 * - Build optimizations
 */
import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  // ==========================================
  // APP METADATA
  // ==========================================
  appId: 'com.nanomind.gamemapper',
  appName: 'Gamepad Mapper Mind',
  webDir: 'dist',
  bundledWebRuntime: false,
  
  // ==========================================
  // ANDROID-SPECIFIC CONFIGURATION
  // ==========================================
  android: {
    // Allow mixed content (HTTP + HTTPS)
    // Diperlukan untuk beberapa game yang menggunakan HTTP endpoints
    allowMixedContent: true,
    
    // Background color untuk splash screen dan WebView
    backgroundColor: '#060608',
    
    // Capture all input events (gamepad, keyboard, touch)
    // CRITICAL: Harus true untuk gamepad mapping
    captureInput: true,
    
    // Disable web contents debugging di production
    webContentsDebuggingEnabled: false,
    
    // Custom user agent untuk identifikasi
    overrideUserAgent: 'GamepadMapper/1.0 Android',
    
    // Override WebView settings
    overrideWebViewUserAgent: 'GamepadMapper/1.0 Android WebView',
    
    // Enable hardware acceleration
    hardwareAcceleration: 'enabled',
    
    // Minimum WebView version
    minWebViewVersion: 80,
    
    // Allow file access
    allowFileAccess: true,
    
    // Allow universal access from file URLs
    allowUniversalAccessFromFileUrls: false,
    
    // Allow file access from file URLs
    allowFileAccessFromFileUrls: false,
    
    // Background color untuk WebView
    webViewBackgroundColor: '#060608',
    
    // Scroll view settings
    scrollViewDeceleration: 'fast',
    scrollViewBounce: false,
    
    // Touch handling
    touchAction: 'manipulation',
    
    // Zoom settings
    zoomEnabled: false,
    
    // Media playback
    mediaPlaybackRequiresUserGesture: false,
    
    // Geolocation (tidak digunakan, tapi tetap di-disable untuk privacy)
    geolocationEnabled: false,
    
    // JavaScript enabled
    javaScriptEnabled: true,
    
    // DOM storage enabled
    domStorageEnabled: true,
    
    // Database enabled
    databaseEnabled: true,
    
    // Cache mode
    cacheMode: 'LOAD_DEFAULT',
    
    // Text zoom
    textZoom: 100,
    
    // Form data (deprecated tapi tetap di-set)
    formDataEnabled: false,
    
    // Load with overview mode
    loadWithOverviewMode: true,
    
    // Support multiple windows
    supportMultipleWindows: false,
    
    // App cache enabled
    appCacheEnabled: true,
    
    // Block network images (tidak digunakan)
    blockNetworkImage: false,
    
    // Block network loads (tidak digunakan)
    blockNetworkLoads: false,
    
    // Layout algorithm
    layoutAlgorithm: 'NORMAL',
    
    // Mixed content mode
    mixedContentMode: 'MIXED_CONTENT_ALWAYS_ALLOW',
    
    // Vertical scrollbar
    verticalScrollbarEnabled: false,
    
    // Horizontal scrollbar
    horizontalScrollbarEnabled: false,
    
    // Vertical scrollbar style
    verticalScrollbarStyle: 'INSIDE_OVERLAY',
    
    // Horizontal scrollbar style
    horizontalScrollbarStyle: 'INSIDE_OVERLAY',
    
    // Scrollbar fade delay
    scrollbarFadeDelay: 300,
    
    // Scrollbar fade duration
    scrollbarFadeDuration: 300,
    
    // Initial scale
    initialScale: 0,
    
    // Use wide viewport
    useWideViewPort: true,
    
    // Default font size
    defaultFontSize: 16,
    
    // Default text encoding
    defaultTextEncodingName: 'UTF-8',
    
    // Resource preload
    resourcePreload: true,
    
    // Safe browsing (disabled untuk performance)
    safeBrowsingEnabled: false,
  },
  
  // ==========================================
  // IOS-SPECIFIC CONFIGURATION (Optional)
  // ==========================================
  ios: {
    // iOS configuration tidak digunakan saat ini
    // Tapi tetap di-define untuk future compatibility
    backgroundColor: '#060608',
    scrollEnabled: false,
    zoomEnabled: false,
  },
  
  // ==========================================
  // PLUGIN CONFIGURATIONS
  // ==========================================
  plugins: {
    // ==========================================
    // SPLASH SCREEN
    // ==========================================
    SplashScreen: {
      // Duration splash screen tampil (ms)
      launchShowDuration: 2000,
      
      // Auto hide splash screen
      launchAutoHide: true,
      
      // Background color
      backgroundColor: '#060608',
      
      // Android splash resource name
      androidSplashResourceName: 'splash',
      
      // Android scale type
      androidScaleType: 'CENTER_CROP',
      
      // Show spinner
      showSpinner: false,
      
      // Spinner color
      androidSpinnerStyle: 'large',
      iosSpinnerStyle: 'large',
      
      // Spinner color
      spinnerColor: '#6366f1',
      
      // Fade in/out duration
      fadeDuration: 300,
      
      // Use dark mode
      useDialog: false,
    },
    
    // ==========================================
    // KEYBOARD
    // ==========================================
    Keyboard: {
      // Resize mode saat keyboard muncul
      // 'none' = tidak resize (overlay tetap full screen)
      resize: 'none',
      
      // Resize on full screen mode
      resizeOnFullScreen: true,
      
      // Style
      style: 'DARK',
      
      // Placeholder text
      placeholder: '',
    },
    
    // ==========================================
    // HAPTICS
    // ==========================================
    Haptics: {
      // Enable vibration feedback
      enableVibrate: true,
      
      // Default vibration duration (ms)
      defaultDuration: 50,
      
      // Vibration patterns
      patterns: {
        light: [10],
        medium: [20],
        heavy: [30],
        success: [10, 50, 10],
        error: [30, 50, 30, 50, 30],
      },
    },
    
    // ==========================================
    // LOCAL NOTIFICATIONS
    // ==========================================
    LocalNotifications: {
      // Small icon untuk notification
      smallIcon: 'ic_stat_icon_config_sample',
      
      // Icon color
      iconColor: '#6366f1',
      
      // Sound
      sound: 'default',
      
      // Vibration pattern
      vibrationPattern: [0, 100, 50, 100],
      
      // Default channel ID
      defaultChannelId: 'gamepad_mapper_default',
      
      // Default channel name
      defaultChannelName: 'Gamepad Mapper',
      
      // Default channel description
      defaultChannelDescription: 'Notifications from Gamepad Mapper Mind',
    },
    
    // ==========================================
    // PREFERENCES (Storage)
    // ==========================================
    Preferences: {
      // Storage type
      storageType: 'localStorage',
      
      // Prefix untuk keys
      keyPrefix: 'gamemapper_',
    },
    
    // ==========================================
    // FILESYSTEM
    // ==========================================
    Filesystem: {
      // Default directory
      defaultDirectory: 'Documents',
      
      // Allow file access
      allowFileAccess: true,
      
      // Recursive operations
      recursiveOperations: true,
    },
    
    // ==========================================
    // SHARE
    // ==========================================
    Share: {
      // Dialog title
      dialogTitle: 'Share Profile',
      
      // Show title
      showTitle: true,
    },
    
    // ==========================================
    // APP LAUNCHER
    // ==========================================
    AppLauncher: {
      // Open URL in external browser
      openInExternalBrowser: false,
    },
    
    // ==========================================
    // APP
    // ==========================================
    App: {
      // Handle back button
      handleBackButton: true,
      
      // Exit on back button
      exitOnBackButton: false,
      
      // Back button listener
      backButtonText: 'Back',
    },
    
    // ==========================================
    // STATUS BAR
    // ==========================================
    StatusBar: {
      // Style
      style: 'DARK',
      
      // Background color
      backgroundColor: '#060608',
      
      // Overlays WebView
      overlaysWebView: true,
      
      // Visible
      visible: true,
    },
    
    // ==========================================
    // GESTURE
    // ==========================================
    Gesture: {
      // Enable gestures
      enabled: true,
      
      // Swipe threshold
      swipeThreshold: 50,
      
      // Long press duration
      longPressDuration: 500,
    },
    
    // ==========================================
    // MOTION
    // ==========================================
    Motion: {
      // Enable motion sensors
      enabled: true,
      
      // Update interval (ms)
      updateInterval: 16, // ~60fps
    },
    
    // ==========================================
    // DEVICE
    // ==========================================
    Device: {
      // Get device info
      getInfo: true,
      
      // Get battery info
      getBatteryInfo: true,
      
      // Get network info
      getNetworkInfo: true,
    },
    
    // ==========================================
    // NETWORK
    // ==========================================
    Network: {
      // Monitor network changes
      monitorChanges: true,
      
      // Update interval (ms)
      updateInterval: 5000,
    },
    
    // ==========================================
    // SCREEN READER
    // ==========================================
    ScreenReader: {
      // Enable screen reader
      enabled: false,
    },
    
    // ==========================================
    // SCREEN ORIENTATION
    // ==========================================
    ScreenOrientation: {
      // Default orientation
      defaultOrientation: 'unspecified',
      
      // Lock orientation
      lockOrientation: false,
    },
    
    // ==========================================
    // CLIPBOARD
    // ==========================================
    Clipboard: {
      // Enable clipboard
      enabled: true,
    },
    
    // ==========================================
    // BROWSER
    // ==========================================
    Browser: {
      // Toolbar color
      toolbarColor: '#060608',
      
      // Presentation style
      presentationStyle: 'fullscreen',
    },
    
    // ==========================================
    // CAMERA (Optional - untuk screenshot)
    // ==========================================
    Camera: {
      // Enable camera
      enabled: false,
    },
    
    // ==========================================
    // PHOTO LIBRARY (Optional)
    // ==========================================
    PhotoLibrary: {
      // Enable photo library
      enabled: false,
    },
  },
  
  // ==========================================
  // SERVER CONFIGURATION
  // ==========================================
  server: {
    // Android scheme
    androidScheme: 'https',
    
    // Allow cleartext traffic (HTTP)
    cleartext: false,
    
    // Allow navigation
    allowNavigation: [
      '*.nanomind.dev',
      '*.github.com',
    ],
    
    // URL untuk development
    // url: 'http://192.168.1.100:5173',
    
    // Hostname
    hostname: 'gamemapper.app',
    
    // Port
    port: 443,
  },
  
  // ==========================================
  // COMMAND CONFIGURATION
  // ==========================================
  command: {
    // Run command
    run: {
      // Live reload
      liveReload: true,
      
      // Host
      host: 'localhost',
      
      // Port
      port: 5173,
    },
  },
  
  // ==========================================
  // CORDOVA CONFIGURATION
  // ==========================================
  cordova: {
    // Preferences
    preferences: {
      // WebView settings
      'android-minSdkVersion': '22',
      'android-targetSdkVersion': '34',
      'android-compileSdkVersion': '34',
      
      // Backup
      'BackupWebStorage': 'local',
      
      // Splash screen
      'SplashScreenDelay': '2000',
      'AutoHideSplashScreen': 'true',
      'ShowSplashScreenSpinner': 'false',
      
      // Keyboard
      'KeyboardResize': 'false',
      'KeyboardResizeMode': 'native',
      
      // Status bar
      'StatusBarOverlaysWebView': 'true',
      'StatusBarStyle': 'lightcontent',
      'StatusBarBackgroundColor': '#060608',
      
      // Orientation
      'Orientation': 'unspecified',
      
      // Full screen
      'Fullscreen': 'false',
      
      // Media playback
      'MediaPlaybackRequiresUserAction': 'false',
      
      // Allow inline media playback
      'AllowInlineMediaPlayback': 'true',
    },
  },
  
  // ==========================================
  // WEB VITALS (Performance Monitoring)
  // ==========================================
  webVitals: {
    // Enable web vitals monitoring
    enabled: true,
    
    // Metrics to track
    metrics: ['CLS', 'FID', 'LCP', 'FCP', 'TTFB'],
    
    // Report to console
    reportToConsole: true,
  },
  
  // ==========================================
  // LOGGING
  // ==========================================
  logging: {
    // Log level
    level: 'info',
    
    // Log to console
    console: true,
    
    // Log to file
    file: false,
    
    // Log format
    format: 'json',
  },
  
  // ==========================================
  // SECURITY
  // ==========================================
  security: {
    // Content Security Policy
    csp: "default-src 'self' data: https:; script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; font-src 'self' data:; connect-src 'self' https:;",
    
    // Enable CORS
    cors: true,
    
    // Allowed origins
    allowedOrigins: [
      'https://*.nanomind.dev',
      'https://*.github.com',
    ],
  },
  
  // ==========================================
  // PERFORMANCE
  // ==========================================
  performance: {
    // Enable performance monitoring
    enabled: true,
    
    // Sample rate (0.0 - 1.0)
    sampleRate: 1.0,
    
    // Trace enabled
    traceEnabled: true,
    
    // Max trace duration (ms)
    maxTraceDuration: 30000,
  },
};

export default config;
