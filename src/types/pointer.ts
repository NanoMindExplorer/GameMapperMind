export enum PointerIdRange {
    /** 1-15: Used by Macro Engine */
    MACRO = 1,
    /** 16-89: Used by Native Gamepad mapping */
    NATIVE = 16,
    /**
     * BUG-N1/N11 FIX: 100-199 — Used by generic tap commands.
     * Previously was 90, but TouchDaemonService.injectTap uses range 100-199
     * (changed in BUG-A1/M6 fix to avoid collision with gamepad pointers 0-63).
     * If TAP=90 is passed to injectTap, it would collide with the native tap range
     * and cause ID reuse issues. Now TAP=100 aligns with the native range start.
     */
    TAP = 100,
    /** 200+: Used by manual interactions */
    MANUAL = 200
}
