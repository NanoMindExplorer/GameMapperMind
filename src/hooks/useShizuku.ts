/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 * @author NanoMind Explorer
 */
import { useCallback, useRef } from 'react';
import { ShizukuState } from '../types';
import TouchInjection from '../plugins/TouchInjection';

// FIX BUG-C05: Whitelist command yang diizinkan untuk mencegah command injection
const ALLOWED_COMMANDS = [
  'start_daemon',
  'stop_daemon',
  'check_status',
  'check_battery',
  'inject_touch_down',
  'inject_touch_move',
  'inject_touch_up',
  'inject_multi_touch',
  'inject_key_event',
  'start_gamepad_listener',
  'stop_gamepad_listener',
  'start_overlay',
  'stop_overlay',
  'update_profile',
  'start_native_mapping',
  'stop_native_mapping',
  'update_native_profile',
  'emergency_kill'
] as const;

type AllowedCommand = typeof ALLOWED_COMMANDS[number];

function isValidCommand(command: string): command is AllowedCommand {
  return ALLOWED_COMMANDS.includes(command as AllowedCommand);
}

// FIX BUG-C05: Sanitize input untuk mencegah injection
function sanitizeInput(input: string): string {
  // Hapus karakter berbahaya
  return input
    .replace(/[;&|`$(){}[$$!#]/g, '')
    .replace(/\.\.\//g, '')
    .replace(/\/\.\./g, '')
    .trim()
    .substring(0, 500); // Batasi panjang
}

export function useShizuku() {
  // FIX BUG-C05: Ref untuk tracking command history (audit trail)
  const commandHistoryRef = useRef<string[]>([]);

  const checkShizukuStatus = useCallback(async (currentState: ShizukuState): Promise<ShizukuState> => {
    try {
      const result = await TouchInjection.checkShizukuStatus();
      
      if (result && typeof result === 'object') {
        const newState: ShizukuState = {
          status: result.status || currentState.status,
          daemonRunning: result.daemonRunning ?? currentState.daemonRunning,
          daemonVersion: result.daemonVersion || currentState.daemonVersion,
          logLines: currentState.logLines
        };

        if (newState.status !== currentState.status || newState.daemonRunning !== currentState.daemonRunning) {
          const timestamp = new Date().toLocaleTimeString('en-US', { hour12: false });
          const msg = newState.status === 'CONNECTED_SHIZUKU' 
            ? 'SYSTEM: Shizuku bridge connected. Daemon is active.'
            : newState.status === 'CONNECTED_ADB'
            ? 'SYSTEM: ADB bridge connected. Daemon is active.'
            : 'SYSTEM: Shizuku service disconnected.';
          
          const newLine = `[${timestamp}] ${msg}`;
          const newLines = [...currentState.logLines, newLine];
          if (newLines.length > 50) newLines.shift();
          newState.logLines = newLines;
        }

        return newState;
      }
      return currentState;
    } catch (err) {
      console.error('checkShizukuStatus error:', err);
      return {
        ...currentState,
        status: 'DISCONNECTED',
        daemonRunning: false
      };
    }
  }, []);

  // FIX BUG-C05: executeShizukuCommand dengan sanitasi dan whitelist
  const executeShizukuCommand = useCallback(async (command: string): Promise<any> => {
    // Validate command against whitelist
    if (!isValidCommand(command)) {
      console.error(`[useShizuku] REJECTED: Command "${command}" is not in the allowed list.`);
      throw new Error(`Command "${command}" is not allowed. Allowed commands: ${ALLOWED_COMMANDS.join(', ')}`);
    }

    // Sanitize command
    const sanitizedCommand = sanitizeInput(command);
    if (sanitizedCommand !== command) {
      console.warn(`[useShizuku] Command sanitized: "${command}" -> "${sanitizedCommand}"`);
    }

    // Log command for audit trail
    commandHistoryRef.current.push(`[${new Date().toISOString()}] ${sanitizedCommand}`);
    if (commandHistoryRef.current.length > 100) {
      commandHistoryRef.current.shift();
    }

    try {
      const result = await TouchInjection.executeShizukuCommand({ command: sanitizedCommand });
      return result;
    } catch (err) {
      console.error(`[useShizuku] Command "${sanitizedCommand}" failed:`, err);
      throw err;
    }
  }, []);

  const injectInput = useCallback(async (x: number, y: number, action: 'down' | 'move' | 'up', pointerId: number = 0): Promise<void> => {
    try {
      const sanitizedX = Math.max(0, Math.min(window.screen.width, Math.round(x)));
      const sanitizedY = Math.max(0, Math.min(window.screen.height, Math.round(y)));

      switch (action) {
        case 'down':
          await TouchInjection.touchDown({ pointerId, x: sanitizedX, y: sanitizedY });
          break;
        case 'move':
          await TouchInjection.touchMove({ pointerId, x: sanitizedX, y: sanitizedY });
          break;
        case 'up':
          await TouchInjection.touchUp({ pointerId });
          break;
      }
    } catch (err) {
      console.error('injectInput error:', err);
      throw err;
    }
  }, []);

  const startDaemon = useCallback(async (): Promise<void> => {
    try {
      await TouchInjection.startDaemon();
    } catch (err) {
      console.error('startDaemon error:', err);
      throw err;
    }
  }, []);

  const stopDaemon = useCallback(async (): Promise<void> => {
    try {
      await TouchInjection.stopDaemon();
    } catch (err) {
      console.error('stopDaemon error:', err);
      throw err;
    }
  }, []);

  const checkBattery = useCallback(async (): Promise<boolean> => {
    try {
      // FIX BUG-L02: Return false di non-native environment
      const result = await TouchInjection.checkBattery();
      return result === true;
    } catch (err) {
      console.error('checkBattery error:', err);
      return false;
    }
  }, []);

  return {
    checkShizukuStatus,
    executeShizukuCommand,
    injectInput,
    startDaemon,
    stopDaemon,
    checkBattery,
    getCommandHistory: () => [...commandHistoryRef.current]
  };
}
