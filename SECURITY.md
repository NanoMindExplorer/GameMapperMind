# Security Policy

## Supported Versions

| Version | Supported          |
|---------|--------------------|
| 2.1.x   | :white_check_mark: |
| < 2.1.0 | :x:                |

## Reporting a Vulnerability

Jika Anda menemukan kerentanan keamanan di GameMapperMind, **JANGAN** buka Issue publik. Laporkan secara privat:

### Cara Melaporkan

1. **Email**: kirim detail ke security@nanomindexplorer.com
2. **GitHub Private Vulnerability Reporting**:
   - Buka https://github.com/NanoMindExplorer/GameMapperMind/security/advisories/new
   - Klik "Report a vulnerability"

### Informasi yang Diperlukan

- Deskripsi kerentanan
- Langkah reproduksi (PoC jika memungkinkan)
- Versi app yang terdampak
- Platform/Android version
- Dampak potensial (RCE, data leak, privilege escalation, dll)

### Response Time

- **Konfirmasi penerimaan**: dalam 48 jam
- **Assessment awal**: dalam 7 hari
- **Fix & disclosure**: max 90 hari untuk critical

### Disclosure Policy

- Coordinated disclosure — fix dirilis dulu, lalu advisory dipublikasikan setelah pengguna punya waktu update
- Credit akan diberikan kepada reporter

## Security Measures

- Shizuku permission required (no root)
- Shell command whitelist (getevent, dumpsys input, pm list packages only)
- Profile persistence encrypted (AES-256-GCM)
- TOOL_TYPE_FINGER (bukan MOUSE)
- Source: TOUCHSCREEN
- Path C (shell) tidak fire saat multi-pointer aktif

## Scope

### In Scope
- Kerentanan di codebase GameMapperMind
- Bypass permission Shizuku
- RCE via profile JSON / macro system
- Data leak dari encrypted profiles

### Out of Scope
- Bug di Shizuku itu sendiri (lapor ke RikkaApps/Shizuku)
- Bug di Android InputManager / MotionEvent API
- Detection oleh anti-cheat game
- Penggunaan app untuk curang di game
