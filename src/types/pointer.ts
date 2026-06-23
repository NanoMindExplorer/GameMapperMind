export enum PointerIdRange {
    /** 1-15: Used by Macro Engine */
    MACRO = 1,
    /** 16-89: Used by Native Gamepad mapping */
    NATIVE = 16,
    /** 90-99: Used by generic tap commands */
    TAP = 90,
    /** 100+: Used by manual interactions */
    MANUAL = 100
}
