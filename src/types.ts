/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 * @author NanoMind Explorer
 * 
 * types.ts - Central Type Definitions
 * 
 * Definisi semua tipe TypeScript yang digunakan di seluruh aplikasi.
 * File ini menjadi single source of truth untuk semua interface dan type.
 * 
 * FIX BUG-L03: Tambah bgDimLevel ke GamepadProfile
 * FIX BUG-M05: Tambah androidEventCode ke ButtonMapping
 * FIX BUG-L04: Tambah endX/endY ke MacroStep untuk swipe
 * 
 * Digunakan oleh:
 * - src/App.tsx
 * - src/defaults.ts
 * - src/hooks/useGamepadLoop.ts
 * - src/hooks/useShizuku.ts
 * - src/components/MacroEngine.tsx
 * - src/components/OverlayWysiwyg.tsx
 * - src/components/GameSelector.tsx
 * - src/plugins/TouchInjection.ts
 */

// ============================================
// GAMEPAD PROFILE TYPES
// ============================================

/**
 * Tipe button mapping
 * - button: Button biasa (A, B, X, Y, LB, RB, dll)
 * - analog: Analog stick (L_STICK, R_STICK)
 * - dpad: D-Pad directional button
 * - swipe: Zone untuk swipe gesture
 * - hold: Zone untuk hold/long-press
 */
export type ButtonType = 'button' | 'analog' | 'dpad' | 'swipe' | 'hold';

/**
 * Direction untuk swipe gesture
 */
export type SwipeDirection = 'UP' | 'DOWN' | 'LEFT' | 'RIGHT';

/**
 * ButtonMapping - Definisi single button dalam profile
 * 
 * Setiap button memiliki:
 * - Posisi (x, y) dalam persentase layar (0-100)
 * - Ukuran (width, height) dalam pixel
 * - Tipe (button, analog, dpad, swipe, hold)
 * - Mapping ke gamepad key (mappedKey)
 * - Android event code untuk native injection
 */
export interface ButtonMapping {
  /** Unique ID untuk button ini */
  id: string;
  
  /** Key gamepad yang di-map (A, B, X, Y, LB, RB, LT, RT, L_STICK, R_STICK, DPAD_*, START, SELECT, TOUCH_1-4) */
  mappedKey: string;
  
  /** Posisi X dalam persentase layar (0-100) */
  x: number;
  
  /** Posisi Y dalam persentase layar (0-100) */
  y: number;
  
  /** Lebar button dalam pixel (default: 60) */
  width?: number;
  
  /** Tinggi button dalam pixel (default: 60) */
  height?: number;
  
  /** Tipe button (default: 'button') */
  type?: ButtonType;
  
  /** Label display untuk button (optional) */
  label?: string;
  
  /** 
   * Android KeyEvent code untuk native injection
   * FIX BUG-M05: Harus sesuai dengan Android KeyEvent constants
   * Reference: https://developer.android.com/reference/android/view/KeyEvent
   * 
   * Common codes:
   * - 96: KEYCODE_BUTTON_A
   * - 97: KEYCODE_BUTTON_B
   * - 99: KEYCODE_BUTTON_X
   * - 100: KEYCODE_BUTTON_Y
   * - 102: KEYCODE_BUTTON_L1/L2
   * - 103: KEYCODE_BUTTON_R1/R2
   * - 19-22: KEYCODE_DPAD_*
   * - 108: KEYCODE_BUTTON_START
   * - 109: KEYCODE_BUTTON_SELECT
   * - 0: Virtual (tidak ada key code, handled via touch injection)
   */
  androidEventCode: number;
  
  /** Direction untuk swipe (hanya untuk type: 'swipe') */
  swipeDirection?: SwipeDirection;
  
  /** Duration swipe dalam ms (hanya untuk type: 'swipe', default: 300) */
  swipeDuration?: number;
}

/**
 * GamepadProfile - Profil lengkap untuk satu game
 * 
 * Berisi semua konfigurasi mapping untuk game tertentu.
 * Bisa di-export/import sebagai JSON file.
 */
export interface GamepadProfile {
  /** Unique ID untuk profile ini */
  id: string;
  
  /** Nama profile (display name) */
  name: string;
  
  /** Nama game (untuk categorization) */
  game: string;
  
  /** Package name Android (optional, untuk auto-detect) */
  packageName?: string;
  
  /** Deskripsi profile (optional) */
  description?: string;
  
  /** 
   * Deadzone untuk analog stick (0.0 - 1.0)
   * Default: 0.15 (15%)
   * Nilai di bawah deadzone akan di-ignore
   */
  deadzone: number;
  
  /** 
   * Smoothing factor untuk analog stick (0.0 - 1.0)
   * Default: 0.5 (50%)
   * Higher = lebih smooth tapi lebih lag
   */
  smoothing: number;
  
  /** 
   * Opacity global untuk semua node di overlay (0-100)
   * Default: 80
   */
  globalOpacity: number;
  
  /** 
   * FIX BUG-L03: Level dimming untuk background overlay (0-100)
   * Default: 50
   * 0 = tidak ada dimming, 100 = background hitam penuh
   */
  bgDimLevel?: number;
  
  /** 
   * Apakah anti-ban randomization aktif
   * Jika true, akan menambahkan jitter pada koordinat dan timing
   */
  antiBanEnabled: boolean;
  
  /** Timestamp pembuatan profile (epoch ms) */
  createdAt: number;
  
  /** Timestamp terakhir kali profile diupdate (epoch ms) */
  updatedAt: number;
  
  /** Array semua button mappings */
  buttons: ButtonMapping[];
}

// ============================================
// MACRO TYPES
// ============================================

/**
 * Tipe step dalam macro
 * - tap: Touch down + immediate touch up
 * - swipe: Touch down + move + touch up
 * - hold: Touch down + wait + touch up
 * - wait: Delay tanpa touch
 */
export type MacroStepType = 'tap' | 'swipe' | 'hold' | 'wait';

/**
 * MacroStep - Single step dalam macro sequence
 * 
 * Setiap step mendefinisikan satu aksi touch atau delay.
 */
export interface MacroStep {
  /** Unique ID untuk step ini */
  id: string;
  
  /** Tipe aksi */
  type: MacroStepType;
  
  /** Posisi X dalam persentase layar (0-100) */
  x: number;
  
  /** Posisi Y dalam persentase layar (0-100) */
  y: number;
  
  /** 
   * FIX: End position untuk swipe (persentase layar)
   * Hanya digunakan untuk type: 'swipe'
   */
  endX?: number;
  
  /** 
   * FIX: End position untuk swipe (persentase layar)
   * Hanya digunakan untuk type: 'swipe'
   */
  endY?: number;
  
  /** 
   * Duration untuk hold/swipe dalam ms
   * Untuk tap: duration touch down sebelum touch up
   * Untuk swipe: total durasi gerakan
   * Untuk hold: durasi hold
   * Default: 50ms untuk tap, 300ms untuk swipe, 500ms untuk hold
   */
  duration: number;
  
  /** 
   * Delay setelah step selesai dalam ms
   * Jeda sebelum step berikutnya dimulai
   * Default: 0 (tidak ada delay)
   */
  delay?: number;
  
  /** Deskripsi step (optional, untuk dokumentasi) */
  description?: string;
}

/**
 * GamepadMacro - Definisi macro sequence
 * 
 * Macro adalah serangkaian step yang bisa di-playback
 * untuk mengotomasi aksi touch.
 */
export interface GamepadMacro {
  /** Unique ID untuk macro ini */
  id: string;
  
  /** Nama macro (display name) */
  name: string;
  
  /** Deskripsi macro (optional) */
  description?: string;
  
  /** Array semua step dalam sequence */
  steps: MacroStep[];
  
  /** 
   * Jumlah loop untuk playback
   * 1 = play sekali, 0 = infinite loop
   * Default: 1
   */
  loopCount: number;
  
  /** 
   * Delay antar loop dalam ms
   * Jeda sebelum loop berikutnya dimulai
   * Default: 100ms
   */
  loopDelay: number;
  
  /** 
   * Apakah macro ini enabled
   * Jika false, macro tidak akan muncul di list playback
   */
  isEnabled: boolean;
  
  /** Timestamp pembuatan macro (epoch ms) */
  createdAt: number;
  
  /** Timestamp terakhir kali macro diupdate (epoch ms) */
  updatedAt: number;
}

// ============================================
// SHIZUKU / DAEMON TYPES
// ============================================

/**
 * Status koneksi Shizuku/ADB
 * - CONNECTED_SHIZUKU: Terkoneksi via Shizuku app
 * - CONNECTED_ADB: Terkoneksi via ADB wireless
 * - DISCONNECTED: Tidak terkoneksi
 */
export type ShizukuStatus = 'CONNECTED_SHIZUKU' | 'CONNECTED_ADB' | 'DISCONNECTED';

/**
 * ShizukuState - State koneksi Shizuku/ADB
 * 
 * Digunakan oleh useShizuku hook untuk tracking status.
 */
export interface ShizukuState {
  /** Status koneksi saat ini */
  status: ShizukuStatus;
  
  /** Apakah daemon sedang berjalan */
  daemonRunning: boolean;
  
  /** Versi daemon yang terkoneksi */
  daemonVersion: string;
  
  /** Log lines dari daemon (untuk debugging) */
  logLines: string[];
}

// ============================================
// NATIVE MAPPING TYPES
// ============================================

/**
 * NativeMappingStatus - Status native mapping service
 * 
 * FIX BUG-M12: Interface untuk tracking native mapping
 */
export interface NativeMappingStatus {
  /** Apakah service sedang berjalan */
  isRunning: boolean;
  
  /** Latency rata-rata dalam ms */
  latency: number;
  
  /** Jumlah pointer aktif saat ini */
  activePointers: number;
  
  /** Uptime service dalam ms */
  uptime: number;
  
  /** Total touch events yang diinject */
  totalInjections: number;
  
  /** Timestamp terakhir ada aktivitas */
  lastActivity: number;
  
  /** Error message jika ada */
  lastError?: string;
}

// ============================================
// GAMEPAD INFO TYPES
// ============================================

/**
 * GamepadInfo - Informasi gamepad yang terkoneksi
 */
export interface GamepadInfo {
  /** Unique ID gamepad */
  id: string;
  
  /** Nama gamepad */
  name: string;
  
  /** Vendor ID (jika tersedia) */
  vendorId?: string;
  
  /** Product ID (jika tersedia) */
  productId?: string;
  
  /** Jumlah button */
  buttonCount: number;
  
  /** Jumlah axis */
  axisCount: number;
  
  /** Apakah mendukung rumble/haptic */
  hasRumble: boolean;
  
  /** Apakah memiliki gyroscope */
  hasGyroscope: boolean;
}

// ============================================
// TOUCH INJECTION TYPES
// ============================================

/**
 * TouchPoint - Single touch point
 * 
 * Digunakan untuk multi-touch injection
 */
export interface TouchPoint {
  /** Pointer ID (0-19) */
  id: number;
  
  /** Koordinat X dalam pixel */
  x: number;
  
  /** Koordinat Y dalam pixel */
  y: number;
  
  /** Tekanan sentuhan (0.0 - 1.0) */
  pressure?: number;
  
  /** Ukuran area sentuhan dalam pixel */
  size?: number;
}

// ============================================
// ANTI-BAN TYPES
// ============================================

/**
 * AntiBanConfig - Konfigurasi anti-ban randomization
 */
export interface AntiBanConfig {
  /** Apakah anti-ban enabled */
  enabled: boolean;
  
  /** Jitter koordinat dalam pixel (default: 5) */
  coordinateJitter: number;
  
  /** Jitter timing dalam persentase (default: 10 = 10%) */
  timingJitter: number;
  
  /** Apakah variasi pressure aktif */
  pressureVariation: boolean;
  
  /** Apakah variasi size aktif */
  sizeVariation: boolean;
  
  /** Apakah humanize analog stick aktif */
  humanizeAnalog: boolean;
}

// ============================================
// APP STATE TYPES
// ============================================

/**
 * AppState - Global state aplikasi
 * 
 * Digunakan untuk tracking state di App.tsx
 */
export interface AppState {
  /** Profile yang sedang aktif */
  activeProfileId: string;
  
  /** Semua profiles yang tersedia */
  profiles: GamepadProfile[];
  
  /** Semua macros yang tersedia */
  macros: GamepadMacro[];
  
  /** Status koneksi Shizuku */
  shizukuState: ShizukuState;
  
  /** Apakah gamepad terkoneksi */
  isGamepadConnected: boolean;
  
  /** Informasi gamepad yang terkoneksi */
  gamepadInfo: GamepadInfo | null;
  
  /** Active button keys (untuk visual feedback) */
  activeKeys: string[];
  
  /** Active axes values (untuk visual feedback) */
  activeAxes: {
    lx: number;
    ly: number;
    rx: number;
    ry: number;
  };
  
  /** Log messages (untuk debugging) */
  logMessages: string[];
  
  /** Apakah native mapping aktif */
  isNativeMappingActive: boolean;
  
  /** Status native mapping */
  nativeMappingStatus: NativeMappingStatus | null;
}

// ============================================
// EVENT TYPES
// ============================================

/**
 * GamepadButtonEvent - Event untuk button press
 */
export interface GamepadButtonEvent {
  /** Nama button */
  buttonName: string;
  
  /** Value (0 atau 1, atau 0.0-1.0 untuk analog trigger) */
  value: number;
  
  /** Index gamepad */
  gamepadIndex?: number;
  
  /** Timestamp event */
  timestamp?: number;
}

/**
 * GamepadAxisEvent - Event untuk axis change
 */
export interface GamepadAxisEvent {
  /** Array axes values [LX, LY, RX, RY, LT, RT] */
  axes: number[];
  
  /** Index gamepad */
  gamepadIndex?: number;
  
  /** Timestamp event */
  timestamp?: number;
}

// ============================================
// UTILITY TYPES
// ============================================

/**
 * Partial type untuk update operations
 * Membuat semua property optional kecuali id
 */
export type PartialProfile = Omit<Partial<GamepadProfile>, 'id'> & { id: string };
export type PartialMacro = Omit<Partial<GamepadMacro>, 'id'> & { id: string };
export type PartialButton = Omit<Partial<ButtonMapping>, 'id'> & { id: string };
export type PartialStep = Omit<Partial<MacroStep>, 'id'> & { id: string };

/**
 * Callback types
 */
export type LogMessageCallback = (message: string) => void;
export type ProfileUpdateCallback = (profile: GamepadProfile) => void;
export type MacroUpdateCallback = (macros: GamepadMacro[]) => void;

// ============================================
// CONSTANTS
// ============================================

/**
 * Default values untuk profile
 */
export const DEFAULT_PROFILE_VALUES = {
  deadzone: 0.15,
  smoothing: 0.5,
  globalOpacity: 80,
  bgDimLevel: 50,
  antiBanEnabled: true,
} as const;

/**
 * Default values untuk macro
 */
export const DEFAULT_MACRO_VALUES = {
  loopCount: 1,
  loopDelay: 100,
  isEnabled: true,
} as const;

/**
 * Default values untuk button
 */
export const DEFAULT_BUTTON_VALUES = {
  width: 60,
  height: 60,
  type: 'button' as ButtonType,
  androidEventCode: 0,
} as const;

/**
 * Default values untuk macro step
 */
export const DEFAULT_STEP_VALUES = {
  duration: 50,
  delay: 0,
} as const;

/**
 * Valid mapped keys
 */
export const VALID_MAPPED_KEYS = [
  'A', 'B', 'X', 'Y',
  'LB', 'RB', 'LT', 'RT',
  'L_STICK', 'R_STICK',
  'DPAD_UP', 'DPAD_DOWN', 'DPAD_LEFT', 'DPAD_RIGHT',
  'START', 'SELECT',
  'TOUCH_1', 'TOUCH_2', 'TOUCH_3', 'TOUCH_4',
  'L_STICK_CLICK', 'R_STICK_CLICK',
  'HOME', 'TOUCHPAD',
] as const;

export type MappedKey = typeof VALID_MAPPED_KEYS[number];
