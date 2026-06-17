# FASE 2 — Performance Optimization

Sesuai kontrak: 1 fase per respons, file lengkap tanpa placeholder, algoritma terbaik.

## Ringkasan Perubahan

### 2.1 InputPipelineWorker.kt — Dynamic Adaptive Polling

**Path tujuan di repo:**
```
android/app/src/main/java/com/gamemappermind/app/daemon/InputPipelineWorker.kt
```
*(Ganti `com.gamemappermind.app` dengan package name Anda jika berbeda.)*

**Algoritma adaptif yang diimplementasikan:**

| Tier | Periode | Frekuensi | Kondisi aktivasi |
|------|---------|-----------|------------------|
| LOW  | 16 ms   | 60 Hz     | Idle > 1.5 s / CPU ≥ 85% / queue ≥ 32 |
| MID  | 8 ms    | 125 Hz    | Default idle-active / CPU terkendali |
| HIGH | 4 ms    | 250 Hz    | Analog delta ≥ 6% + CPU < 85% + queue < 12 |

**Hysteresis (anti-flapping):**
- Promosi MID → HIGH: butuh analog delta ≥ 6% secara konsisten
- Demosi HIGH → MID: butuh analog delta ≤ 2% selama ≥ 300 ms
- Demosi MID → LOW: butuh idle 1.5 s tanpa event
- Promosi LOW → MID: event apa pun segera

**Backpressure awareness:**
- `touchInjector.pendingQueueDepth()` dipanggil setiap tick
- Queue ≥ 32 (hard watermark) → paksa turun ke LOW
- Queue ≥ 12 (soft watermark) → blok promosi ke HIGH, demote MID → LOW

**CPU load monitoring:**
- Sampling `/proc/stat` setiap 25 tick (~100 ms @ 250 Hz)
- EMA smoothing (α = 0.4) — reaksi cepat dalam 5 sampel
- Hard watermark 85%, soft watermark 55%

**Activity tracking:**
- Ring buffer 64-entry untuk event timestamps
- `btnRateHz` dihitung dari sliding window 250 ms
- `analogDelta` = max(||L||, ||R||) — Euclidean magnitude

**Thread safety:**
- Worker thread: `THREAD_PRIORITY_URGENT_DISPLAY (-8)` untuk minim jitter
- Single-looper, no chained handlers — setiap tick self-contained
- `tickRunnable` menangkap semua Throwable → pipeline tidak pernah crash

**JS bridge payload (untuk monitoring):**
```json
{
  "type": "gamepad",
  "ts": 12345,
  "lx": 0.5, "ly": 0.3,
  "rx": 0.0, "ry": 0.0,
  "lt": 0.0, "rt": 0.0,
  "analogDelta": 0.583,
  "tier": "HIGH",
  "periodMs": 4,
  "cpu": 0.42,
  "btnRateHz": 12.0,
  "qDepth": 2
}
```

---

### 2.2 TouchInjector.kt — Pointer Pool 100 slots + LRU Eviction

**Path tujuan di repo:**
```
android/app/src/main/java/com/gamemappermind/app/input/TouchInjector.kt
```

**Layout pool:**

| Range | Tipe | Kebijakan |
|-------|------|-----------|
| 0–1   | Analog sticks (sticky) | Tidak pernah di-evict saat active. Slot 0 = L, Slot 1 = R |
| 2–9   | Reserved future (gyro, macro) | Tidak dipakai saat ini |
| 10–99 | General pool (buttons/swipes) | LRU eviction saat penuh |

**LRU algorithm:**
1. `acquireGeneralPoolSlot()` cari slot FREE pertama dari index 10
2. Jika tidak ada, cari slot dengan `lastUsedNs` tertua di range 10–99
3. Evict slot tersebut: kirim `ACTION_UP` sintetis untuk release bersih
4. Return slot index ke caller

**Anti-reuse pointerId:**
- `AtomicInteger` monoton, increment per acquire
- Wrap di `0x7FFFFFFF` (signed-int safe)
- Tidak reuse dalam 30 s (Android InputDispatcher track active pointers)

**Multi-pointer support:**
- `injectMultiPointerMove(activeSlots: IntArray)` untuk gesture 2-jari
- Build `PointerProperties[]` + `PointerCoords[]` dari slot aktif
- Single `MotionEvent` dengan `ACTION_MOVE` untuk semua pointer

**Coalescing untuk analog:**
- `analogMove()` skip emit jika delta < 1.0 px² (deadzone koherensi)
- Mencegah flooding InputManager saat analog diam di micro-jitter

**Thread safety:**
- `ReentrantLock(fair=false)` untuk throughput (analog path high-frequency)
- Reflection lookup di constructor (sekali saja)
- MotionEvent di-recycle setelah inject (no leak)

**Backpressure API:**
- `pendingQueueDepth()` return jumlah slot di state DOWN/MOVE
- Pipeline worker baca setiap tick → trigger tier downgrade

**Constructor injection:**
```kotlin
TouchInjector(
    getScreenWidth = { displayMetrics.widthPixels },
    getScreenHeight = { displayMetrics.heightPixels }
)
```
*Layar dibaca ulang tiap call → handle rotation/resize otomatis.*

---

## Dependencies yang harus sudah ada

Sebelum deploy FASE 2, pastikan file-file ini sudah ada dari FASE 1:

1. ✅ `OverlayApp.tsx` — Window property conflict fix
2. ✅ `FloatingOverlayService.java` — Safe JS execution
3. ✅ `useShizuku.ts` — Real command execution
4. ✅ `OverlayWysiwyg.tsx` — Precision coordinates

Jika FASE 1 belum di-merge, FASE 2 tetap bisa di-deploy (tidak ada dependency compile-time), tapi testing end-to-end sebaiknya setelah FASE 1.

## Class yang di-assume sudah ada (dari Tahap 1–4)

- `GamepadManager.Snapshot` — data class dengan fields: `leftStickX/Y, rightStickX/Y, leftTrigger, rightTrigger, dpad, buttonsBits`, methods: `hasAnyButton()`, `isButtonPressed(code)`
- `AnalogProcessor` — methods: `process(snapshot, profile, screenW, screenH, injector)`, `releaseAll(injector)`, `onProfileChanged(profile)`
- `GameProfile` — data class dengan `mappings: List<Mapping>`
- `Mapping` — data class dengan `id, buttonCode, action, xPercent, yPercent, endXPercent, endYPercent, durationMs`; companion: `ACTION_TAP`, `ACTION_SWIPE`

Jika signature di repo Anda berbeda, sesuaikan field access di:
- `processTick()` → `snapshot.leftStickX`, dll.
- `handleButtonEvent()` → `m.xPercent`, `m.action`, dll.

## Testing checklist FASE 2

- [ ] Build APK berhasil (no Kotlin compile error)
- [ ] Aplikasi launch tanpa crash
- [ ] Buka game → gerak analog → lihat logcat `Tier → HIGH (4ms)` muncul
- [ ] Diamkan gamepad 2 detik → logcat `Tier → LOW (16ms)`
- [ ] Tekan 10+ tombol cepat → `qDepth` naik tapi tidak melebihi 32
- [ ] Tekan analog kiri + kanan bersamaan → slot 0 dan 1 aktif
- [ ] Kill aplikasi → tidak ada leak pointer (cek dengan `dumpsys input`)
- [ ] Rotasi tablet 90° → `screenWidthPx/screenHeightPx` berubah, koordinat tetap akurat

## Logcat filter untuk monitoring

```bash
adb logcat -s InputPipelineWorker:D TouchInjector:D
```

Filter adaptif tier:
```bash
adb logcat | grep "Tier →"
```

Filter pool pressure:
```bash
adb logcat | grep -E "(qDepth|inject failed|acquireSlot)"
```

---

## Setelah FASE 2 selesai

Lanjut ke **FASE 3 — Clean Naming & Shared Schema**:
- Rename `nexon` → `GameMapper` di seluruh codebase
- JSON Schema untuk `game_profile.json`
- Zod validation di TS side
- kotlinx.serialization di Kotlin side

**Konfirmasi untuk lanjut ke FASE 3?**
