/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 * @author NanoMind Explorer
 *
 * Trailing-Edge Profile Debouncer
 * Ensures that high-frequency profile updates (e.g., dragging buttons on WYSIWYG canvas)
 * are smoothly throttled to prevent IPC Binder congestion, while GUARANTEEING that the
 * final resting coordinate/state is delivered to the native layer via a trailing edge flush.
 */

import { GamepadProfile } from '../types';

export interface ProfileDebouncerOptions {
  /**
   * Debounce delay in milliseconds before executing a trailing-edge flush.
   * Default: 250ms
   */
  debounceDelayMs?: number;

  /**
   * Maximum wait time in milliseconds during continuous updates before forcing
   * an intermediate flush.
   * Default: 600ms
   */
  maxWaitMs?: number;

  /**
   * Async or sync callback executed when a profile payload is dispatched to native.
   */
  onSync: (profileJson: string) => Promise<void> | void;
}

export class ProfileDebouncer {
  private readonly debounceDelayMs: number;
  private readonly maxWaitMs: number;
  private readonly onSync: (profileJson: string) => Promise<void> | void;

  private lastSentJson: string = '';
  private lastSentTime: number = 0;
  private currentProfileId: string = '';
  private latestProfile: GamepadProfile | null = null;

  private trailingTimer: ReturnType<typeof setTimeout> | null = null;
  private isSyncing: boolean = false;
  private hasPendingSync: boolean = false;

  constructor(options: ProfileDebouncerOptions) {
    this.debounceDelayMs = options.debounceDelayMs ?? 250;
    this.maxWaitMs = options.maxWaitMs ?? 600;
    this.onSync = options.onSync;
  }

  /**
   * Ingest a new profile state update.
   * - If profile ID switches, dispatches immediately.
   * - If forceImmediate is true or first run, dispatches immediately.
   * - Otherwise, applies leading/max-wait dispatch and schedules a trailing-edge flush.
   */
  public update(profile: GamepadProfile | null, options: { forceImmediate?: boolean } = {}): void {
    if (!profile) return;

    this.latestProfile = profile;
    const profileJson = JSON.stringify(profile);

    // Profile ID switch: user switched games/profiles, sync immediately!
    const isProfileIdSwitched = this.currentProfileId !== '' && this.currentProfileId !== profile.id;
    this.currentProfileId = profile.id;

    if (options.forceImmediate || isProfileIdSwitched || this.lastSentTime === 0) {
      this.clearTrailingTimer();
      this.executeSend(profileJson);
      return;
    }

    // Deduplication: if payload is identical to what was last successfully sent, nothing to do.
    if (profileJson === this.lastSentJson && !this.hasPendingSync) {
      this.clearTrailingTimer();
      return;
    }

    const now = Date.now();
    const timeSinceLast = now - this.lastSentTime;

    // Clear any existing trailing timer before rescheduling
    this.clearTrailingTimer();

    // If max wait has elapsed and not currently in-flight, send intermediate edge
    if (timeSinceLast >= this.maxWaitMs && !this.isSyncing) {
      this.executeSend(profileJson);
    } else {
      // Schedule trailing edge flush
      const remaining = Math.max(50, Math.min(this.debounceDelayMs, this.maxWaitMs - timeSinceLast));
      this.trailingTimer = setTimeout(() => {
        this.trailingTimer = null;
        if (this.latestProfile) {
          const trailingJson = JSON.stringify(this.latestProfile);
          if (trailingJson !== this.lastSentJson) {
            this.executeSend(trailingJson);
          }
        }
      }, remaining);
    }
  }

  /**
   * Dispatches the profile JSON to the native sync handler with concurrency and error protection.
   */
  private executeSend(json: string): void {
    if (this.isSyncing) {
      this.hasPendingSync = true;
      return;
    }

    this.isSyncing = true;
    this.hasPendingSync = false;
    this.lastSentJson = json;
    this.lastSentTime = Date.now();

    try {
      const result = this.onSync(json);
      if (result && typeof (result as any).then === 'function') {
        (result as Promise<void>).then(
          () => {
            this.handleSyncComplete();
          },
          (err) => {
            console.warn('[ProfileDebouncer] Failed to sync profile to native:', err);
            this.handleSyncComplete();
          }
        );
      } else {
        this.handleSyncComplete();
      }
    } catch (err) {
      console.warn('[ProfileDebouncer] Failed to sync profile to native:', err);
      this.handleSyncComplete();
    }
  }

  private handleSyncComplete(): void {
    this.isSyncing = false;
    if (this.hasPendingSync && this.latestProfile) {
      this.hasPendingSync = false;
      const freshJson = JSON.stringify(this.latestProfile);
      if (freshJson !== this.lastSentJson) {
        this.executeSend(freshJson);
      }
    }
  }

  /**
   * Forces an immediate flush of the latest profile if it differs from the last sent state.
   */
  public flush(): void {
    this.clearTrailingTimer();
    if (this.latestProfile) {
      const finalJson = JSON.stringify(this.latestProfile);
      if (finalJson !== this.lastSentJson) {
        this.executeSend(finalJson);
      }
    }
  }

  /**
   * Flushes any pending updates and cleans up timers on unmount/destruction.
   */
  public dispose(): void {
    this.flush();
    this.clearTrailingTimer();
  }

  private clearTrailingTimer(): void {
    if (this.trailingTimer !== null) {
      clearTimeout(this.trailingTimer);
      this.trailingTimer = null;
    }
  }

  public getLastSentJson(): string {
    return this.lastSentJson;
  }

  public isTrailingScheduled(): boolean {
    return this.trailingTimer !== null;
  }
}
