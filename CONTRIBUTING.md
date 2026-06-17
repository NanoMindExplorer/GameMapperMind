# Contributing to GameMapperMind

Terima kasih atas minat Anda untuk berkontribusi! Kami menerima kontribusi dalam bentuk **profil game baru** yang akan ditambahkan ke aplikasi.

## 🎮 Cara Menambahkan Profil Game Baru

### Langkah 1: Fork Repository
1. Buka https://github.com/NanoMindExplorer/GameMapperMind
2. Klik tombol **"Fork"** di kanan atas
3. Pilih akun Anda sebagai destination fork

### Langkah 2: Clone Fork Anda
```bash
git clone https://github.com/YOUR_USERNAME/GameMapperMind.git
cd GameMapperMind
```

### Langkah 3: Buat Branch Baru
```bash
git checkout -b add-profile-mygame
```

### Langkah 4: Copy Template
```bash
cp src/communityProfiles/_template.ts src/communityProfiles/myGame.ts
```

### Langkah 5: Edit Profil
Buka `src/communityProfiles/myGame.ts` dengan text editor dan edit:
- `id` — ID unik (lowercase, tanpa spasi, contoh: `my_game`)
- `name` — Nama game
- `packageName` — Package Android game (cek di Play Store URL)
- `buttons[]` — Posisi tombol (gunakan percentage 0-100)
- `mappings[]` — Hardware mapping (absolute pixels untuk 2800x1840)
- `leftJoystick` / `rightJoystick` — Zona analog stick

### Langkah 6: Daftarkan Profil
Edit `src/communityProfiles/index.ts`:
```typescript
import { MY_GAME } from './myGame';

const communityProfileImports: GamepadProfile[] = [
  MY_GAME,  // tambahkan di sini
];
```

### Langkah 7: Commit + Push
```bash
git add src/communityProfiles/
git commit -m "feat: add profile for [Game Name]"
git push origin add-profile-mygame
```

### Langkah 8: Submit Pull Request
1. Buka fork Anda di GitHub
2. Klik **"Compare & pull request"**
3. Isi template PR (game name, package, dll)
4. Klik **"Create pull request"**

## ⚠️ Aturan Penting

### ✅ Yang BOLEH Anda Lakukan
- Menambah file baru di `src/communityProfiles/`
- Mengedit `src/communityProfiles/index.ts` (untuk import)
- Mengedit file profil Anda sendiri (yang sudah pernah Anda tambahkan)

### ❌ Yang TIDAK Boleh Anda Lakukan
- Mengedit file di luar `src/communityProfiles/`
- Mengedit `src/gameProfiles.ts` (profil official)
- Mengedit file Android (`android/`)
- Mengedit workflow (`.github/workflows/`)
- Mengedit konfigurasi build (`vite.config.ts`, `tsconfig.json`, dll)
- Mengedit komponen UI (`src/components/`)

### 🔒 Sistem Keamanan
- **CODEOWNERS**: Semua file di luar `src/communityProfiles/` butuh approval owner
- **PR Validation**: Workflow otomatis cek apakah PR hanya sentuh file profil
- **Type Check**: Workflow otomatis validasi TypeScript schema
- **Duplicate Check**: Workflow otomatis cek ID profil tidak duplikat

## 📐 Panduan Koordinat

### Sistem Percentage (0-100)
- `x=0` → tepi kiri layar
- `x=50` → tengah layar
- `x=100` → tepi kanan layar
- `y=0` → tepi atas layar
- `y=100` → tepi bawah layar

### Layout Standar (Landscape)
```
┌─────────────────────────────────────────┐
│ [Select]                    [Start]     │
│                                         │
│  [LB]                          [RB]     │
│  [LT]                          [RT]     │
│                                         │
│                                         │
│                    [Y]                  │
│              [X]       [B]              │
│                    [A]                  │
│                                         │
│    ◯                    ◯               │
│  (L-Stick)           (R-Stick)         │
└─────────────────────────────────────────┘
```

### Tombol Gamepad → mappedKey
| Gamepad | mappedKey | androidEventCode |
|---------|-----------|-----------------|
| A | `BUTTON_A` | 96 |
| B | `BUTTON_B` | 97 |
| X | `BUTTON_X` | 99 |
| Y | `BUTTON_Y` | 100 |
| LB | `BUTTON_L1` | 101 |
| RB | `BUTTON_R1` | 102 |
| LT | `BUTTON_L2` | 104 |
| RT | `BUTTON_R2` | 105 |
| L3 | `BUTTON_L3` | 103 |
| R3 | `BUTTON_R3` | 106 |
| START | `BUTTON_START` | 108 |
| SELECT | `BUTTON_SELECT` | 109 |
| D-Pad Up | `DPAD_UP` | 106 |
| D-Pad Down | `DPAD_DOWN` | 107 |
| D-Pad Left | `DPAD_LEFT` | 108 |
| D-Pad Right | `DPAD_RIGHT` | 109 |

## ❓ Butuh Bantuan?

- Buka [Issue](https://github.com/NanoMindExplorer/GameMapperMind/issues/new) untuk bertanya
- Lihat profil yang sudah ada di `src/gameProfiles.ts` sebagai referensi
- Gunakan `_template.ts` sebagai starting point

Terima kasih telah berkontribusi! 🎮
