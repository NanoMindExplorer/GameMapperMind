# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0-FINAL] - 2026-06-23
### Added
- Added secure ADMIN_TOKEN enforcement.
- Added AES-256-GCM encryption for stored profiles and macros.
- Multi-gamepad support structure.

### Changed
- Refactored radial deadzone into constants.
- Updated vitest configuration.

### Fixed
- Fixed unauthenticated access to /api/logs and /api/macros (BUG-C02, BUG-C03).
- Added rate limit to /api/log (BUG-C04).
- Handled spurious race conditions in useGamepadLoop (BUG-H01).

### Security
- Resolved missing cors protections.
