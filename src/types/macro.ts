export interface MacroStep {
  id: string;
  type: 'touch_down' | 'touch_move' | 'touch_up' | 'delay';
  x?: number;
  y?: number;
  delayMs?: number;
  pointerId: number;
}

export interface MacroProfile {
  id: string;
  name: string;
  actions: MacroStep[];
  triggerKey: string;
  playbackSpeed: number;
}
