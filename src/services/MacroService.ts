import { MacroProfile, MacroStep } from '../types/macro.js';

export class MacroService {
  private static activeMacros: Set<string> = new Set();
  
  public static async fetchMacros(): Promise<MacroProfile[]> {
    try {
      const res = await fetch('/api/macros');
      return await res.json();
    } catch (e) {
      console.error(e);
      return [];
    }
  }

  public static async saveMacro(macro: MacroProfile): Promise<boolean> {
    const method = macro.id ? 'PUT' : 'POST';
    const url = macro.id ? `/api/macros/${macro.id}` : '/api/macros';
    try {
      const res = await fetch(url, {
        method,
        headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${localStorage.getItem('ADMIN_TOKEN') || 'NX-DEFAULT-DEV-TOKEN'}` },
        body: JSON.stringify(macro)
      });
      return res.ok;
    } catch(e) {
      return false;
    }
  }

  public static async executeMacro(macro: MacroProfile, injectTouch: (step: MacroStep) => void) {
    if (this.activeMacros.has(macro.id)) return; // Prevent concurrent identical macros
    this.activeMacros.add(macro.id);
    
    let lastTime = performance.now();
    
    for (const step of macro.actions) {
      if (!this.activeMacros.has(macro.id)) break; // Was cancelled
      
      if (step.type === 'delay' && step.delayMs) {
        const delay = step.delayMs / Math.max(0.1, macro.playbackSpeed);
        await new Promise(r => setTimeout(r, delay));
      } else {
        injectTouch(step);
      }
    }
    
    this.activeMacros.delete(macro.id);
  }

  public static cancelMacro(macroId: string) {
    this.activeMacros.delete(macroId);
  }
}
