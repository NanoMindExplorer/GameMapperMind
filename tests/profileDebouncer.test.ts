import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { ProfileDebouncer } from '../src/utils/profileDebouncer';
import { GamepadProfile } from '../src/types';

describe('ProfileDebouncer (Trailing-Edge Sync Tests)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  const createDummyProfile = (id: string, x: number = 50, y: number = 50): GamepadProfile => ({
    id,
    name: `Profile ${id}`,
    game: 'test_game',
    buttons: [
      {
        id: 'btn_1',
        mappedKey: 'A',
        x,
        y,
        radius: 40,
        interactionType: 'hold'
      }
    ]
  });

  it('sends the first profile immediately on initial mount/update', () => {
    const onSync = vi.fn();
    const debouncer = new ProfileDebouncer({ onSync });

    const profile = createDummyProfile('profile_1', 10, 20);
    debouncer.update(profile);

    expect(onSync).toHaveBeenCalledTimes(1);
    expect(onSync).toHaveBeenCalledWith(JSON.stringify(profile));
    expect(debouncer.isTrailingScheduled()).toBe(false);
  });

  it('sends immediately when profile ID changes (switching game profile)', () => {
    const onSync = vi.fn();
    const debouncer = new ProfileDebouncer({ onSync });

    const p1 = createDummyProfile('efootball', 10, 20);
    debouncer.update(p1);
    expect(onSync).toHaveBeenCalledTimes(1);

    const p2 = createDummyProfile('genshin', 30, 40);
    debouncer.update(p2);

    expect(onSync).toHaveBeenCalledTimes(2);
    expect(onSync).toHaveBeenLastCalledWith(JSON.stringify(p2));
    expect(debouncer.isTrailingScheduled()).toBe(false);
  });

  it('deduplicates identical profile updates and does not trigger IPC', () => {
    const onSync = vi.fn();
    const debouncer = new ProfileDebouncer({ onSync });

    const p1 = createDummyProfile('profile_1', 10, 20);
    debouncer.update(p1);
    expect(onSync).toHaveBeenCalledTimes(1);

    // Same content update
    debouncer.update({ ...p1 });
    expect(onSync).toHaveBeenCalledTimes(1);
    expect(debouncer.isTrailingScheduled()).toBe(false);
  });

  it('guarantees trailing-edge execution with the final resting position after drag finishes', () => {
    const onSync = vi.fn();
    const debouncer = new ProfileDebouncer({
      debounceDelayMs: 250,
      maxWaitMs: 600,
      onSync
    });

    const initial = createDummyProfile('profile_1', 0, 0);
    debouncer.update(initial);
    expect(onSync).toHaveBeenCalledTimes(1);
    expect(JSON.parse(onSync.mock.calls[0][0]).buttons[0].x).toBe(0);

    // Simulate user dragging button rapidly across 5 frames within 150ms:
    vi.advanceTimersByTime(30);
    debouncer.update(createDummyProfile('profile_1', 10, 10));

    vi.advanceTimersByTime(30);
    debouncer.update(createDummyProfile('profile_1', 20, 20));

    vi.advanceTimersByTime(30);
    debouncer.update(createDummyProfile('profile_1', 35, 35));

    // User finishes dragging at (50, 50):
    vi.advanceTimersByTime(30);
    const finalProfile = createDummyProfile('profile_1', 50, 50);
    debouncer.update(finalProfile);

    // During rapid dragging within debounce window, extra sends are suppressed:
    expect(onSync).toHaveBeenCalledTimes(1);
    expect(debouncer.isTrailingScheduled()).toBe(true);

    // Advance time past debounce delay (250ms):
    vi.advanceTimersByTime(260);

    // Trailing edge MUST have fired with the final position (50, 50)!
    expect(onSync).toHaveBeenCalledTimes(2);
    const lastDispatched = JSON.parse(onSync.mock.calls[1][0]);
    expect(lastDispatched.buttons[0].x).toBe(50);
    expect(lastDispatched.buttons[0].y).toBe(50);
    expect(debouncer.isTrailingScheduled()).toBe(false);
  });

  it('forces intermediate sync when continuous drag exceeds maxWaitMs', () => {
    const onSync = vi.fn();
    const debouncer = new ProfileDebouncer({
      debounceDelayMs: 200,
      maxWaitMs: 500,
      onSync
    });

    debouncer.update(createDummyProfile('p1', 0, 0));
    expect(onSync).toHaveBeenCalledTimes(1);

    // Simulate drag updating every 100ms for 600ms
    for (let i = 1; i <= 6; i++) {
      vi.advanceTimersByTime(100);
      debouncer.update(createDummyProfile('p1', i * 10, i * 10));
    }

    // At 500ms+, maxWaitMs forces an intermediate flush:
    expect(onSync.mock.calls.length).toBeGreaterThanOrEqual(2);

    // Advance to let final trailing edge complete
    vi.advanceTimersByTime(300);
    const finalCall = JSON.parse(onSync.mock.calls[onSync.mock.calls.length - 1][0]);
    expect(finalCall.buttons[0].x).toBe(60);
  });

  it('flush() immediately dispatches pending profile changes without waiting for timer', () => {
    const onSync = vi.fn();
    const debouncer = new ProfileDebouncer({
      debounceDelayMs: 300,
      onSync
    });

    debouncer.update(createDummyProfile('p1', 0, 0));
    expect(onSync).toHaveBeenCalledTimes(1);

    // Update with new position
    debouncer.update(createDummyProfile('p1', 99, 99));
    expect(onSync).toHaveBeenCalledTimes(1);
    expect(debouncer.isTrailingScheduled()).toBe(true);

    // Call flush immediately
    debouncer.flush();

    expect(onSync).toHaveBeenCalledTimes(2);
    const flushed = JSON.parse(onSync.mock.calls[1][0]);
    expect(flushed.buttons[0].x).toBe(99);
    expect(debouncer.isTrailingScheduled()).toBe(false);
  });

  it('dispose() flushes pending changes and cleans up timers cleanly', () => {
    const onSync = vi.fn();
    const debouncer = new ProfileDebouncer({
      debounceDelayMs: 300,
      onSync
    });

    debouncer.update(createDummyProfile('p1', 0, 0));
    debouncer.update(createDummyProfile('p1', 42, 42));

    debouncer.dispose();

    expect(onSync).toHaveBeenCalledTimes(2);
    expect(JSON.parse(onSync.mock.calls[1][0]).buttons[0].x).toBe(42);
    expect(debouncer.isTrailingScheduled()).toBe(false);
  });
});
