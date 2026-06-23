import React from 'react';
import { GamepadProfile, VirtualButton } from '../types';

export interface OverlayWysiwygProps {
  activeProfile: GamepadProfile;
  onUpdateProfile: (updated: GamepadProfile) => void;
  onLogMessage: (msg: string) => void;
  activeKeys?: string[];
  activeAxes?: {lx: number, ly: number, rx: number, ry: number};
  isNativeOverlay?: boolean;
}

export function useOverlayWysiwyg({
  activeProfile, onUpdateProfile, onLogMessage, activeKeys = [], activeAxes = {lx:0, ly:0, rx:0, ry:0}, isNativeOverlay = false
}: OverlayWysiwygProps) {
  
  const [showConfig, setShowConfig] = React.useState(true);
  const [selectedButtonId, setSelectedButtonId] = React.useState<string | null>(null);
  const [screenshotMode, setScreenshotMode] = React.useState<string>('genshin');
  const [customScreenshotUrl, setCustomScreenshotUrl] = React.useState<string | null>(null);
  const fileInputRef = React.useRef<HTMLInputElement>(null);
  const [isDragging, setIsDragging] = React.useState(false);
  const [nexionPos, setNexionPos] = React.useState({ x: 50, y: 10 });
  const [isDraggingNexion, setIsDraggingNexion] = React.useState(false);
  const nexionDragHasMoved = React.useRef(false);
  const [showPalette, setShowPalette] = React.useState(false);
  
  const [activePlayer, setActivePlayer] = React.useState<1|2|3|4>(1); 
  const [hideGrid, setHideGrid] = React.useState(false);
  const [hideAllNodes, setHideAllNodes] = React.useState(false);
  
  const [bgDimLevel, setBgDimLevel] = React.useState(30);
  const [globalNodeOpacity, setGlobalNodeOpacity] = React.useState(80);

  const selectedButton = activeProfile.buttons.find(b => b.id === selectedButtonId);

  const handleContainerClick = (e: React.MouseEvent) => {
    if (isDragging) return;
    setSelectedButtonId(null);
    if (showPalette) setShowPalette(false);
  };

  const handleDragStart = (btnId: string, e: React.MouseEvent | React.TouchEvent) => {
    e.stopPropagation();
    setSelectedButtonId(btnId);
    setIsDragging(true);
  };

  const handleDragMove = (e: any) => {
    if (isDraggingNexion) {
      nexionDragHasMoved.current = true;
      const clientX = e.touches ? e.touches[0].clientX : e.clientX;
      const clientY = e.touches ? e.touches[0].clientY : e.clientY;
      const rect = document.getElementById('canvas-container')?.getBoundingClientRect();
      if (rect) {
        let x = ((clientX - rect.left) / rect.width) * 100;
        let y = ((clientY - rect.top) / rect.height) * 100;
        setNexionPos({
          x: Math.max(0, Math.min(100, x)),
          y: Math.max(0, Math.min(100, y))
        });
      }
      return;
    }

    if (!isDragging || !selectedButtonId) return;

    const clientX = e.touches ? e.touches[0].clientX : e.clientX;
    const clientY = e.touches ? e.touches[0].clientY : e.clientY;

    const rect = document.getElementById('canvas-container')?.getBoundingClientRect();
    if (rect) {
      let x = ((clientX - rect.left) / rect.width) * 100;
      let y = ((clientY - rect.top) / rect.height) * 100;
      
      x = Math.max(0, Math.min(100, x));
      y = Math.max(0, Math.min(100, y));

      const updatedButtons = activeProfile.buttons.map(b => {
        if (b.id === selectedButtonId) {
          return { ...b, x, y };
        }
        return b;
      });
      onUpdateProfile({ ...activeProfile, buttons: updatedButtons });
    }
  };

  const handleDragEnd = () => {
    setIsDragging(false);
    if (isDraggingNexion) setTimeout(() => setIsDraggingNexion(false), 50);
  };

  const handleUpdateBtnProperty = (key: keyof VirtualButton, value: any) => {
    if (!selectedButtonId) return;
    const updatedButtons = activeProfile.buttons.map(b => {
      if (b.id === selectedButtonId) {
        return { ...b, [key]: value };
      }
      return b;
    });
    onUpdateProfile({ ...activeProfile, buttons: updatedButtons });
  };

  const relocateButtonOffset = (id: string, dx: number, dy: number) => {
    const updatedButtons = activeProfile.buttons.map(b => {
      if (b.id === id) {
        return { 
          ...b, 
          x: Math.max(0, Math.min(100, b.x + dx)), 
          y: Math.max(0, Math.min(100, b.y + dy)) 
        };
      }
      return b;
    });
    onUpdateProfile({ ...activeProfile, buttons: updatedButtons });
  };

  const handleAddSpecificButton = (label: string, mappedKey: string, androidEventCode: number, defaultSize: number = 56, type: VirtualButton['type'] = 'button') => {
    const freshId = Math.random().toString(16).substring(2, 10).padStart(8, '0');
    
    const newBtn: VirtualButton = {
      id: freshId,
      label,
      x: 50,
      y: 50,
      width: defaultSize,
      height: defaultSize,
      mappedKey: mappedKey as any,
      androidEventCode,
      player: activePlayer,
      type,
      opacity: 80,
      deadzone: type === 'analog_stick' ? 0.15 : undefined,
      sensitivity: type === 'analog_stick' ? 1.0 : undefined,
      tapDuration: type === 'swipe' ? 30 : undefined
    };
    onUpdateProfile({
      ...activeProfile,
      buttons: [...activeProfile.buttons, newBtn]
    });
    setSelectedButtonId(freshId);
    setShowPalette(false);
  };

  const handleAddNewButton = (type: VirtualButton['type'] = 'button', defaultLabel: string = 'New', eventCode: number = 0) => {
    handleAddSpecificButton(defaultLabel, 'BUTTON_A', eventCode, type === 'swipe' ? 80 : 56, type);
  };

  const handleRemoveButton = (id: string) => {
    onUpdateProfile({
      ...activeProfile,
      buttons: activeProfile.buttons.filter(b => b.id !== id)
    });
    setSelectedButtonId(null);
  };

  const getBackgroundUrl = () => {
    if (screenshotMode === 'custom' && customScreenshotUrl) return customScreenshotUrl;
    if (screenshotMode === 'genshin') return 'https://images.unsplash.com/photo-1542751371-adc38448a05e?auto=format&fit=crop&q=80&w=1200';
    if (screenshotMode === 'pubg') return 'https://images.unsplash.com/photo-1534423861386-85a16f5d13fd?auto=format&fit=crop&q=80&w=1200';
    if (screenshotMode === 'codm') return 'https://images.unsplash.com/photo-1511512578047-dfb367046420?auto=format&fit=crop&q=80&w=1200';
    if (screenshotMode === 'efootball') return 'https://images.unsplash.com/photo-1508098682722-e99c43a406b2?auto=format&fit=crop&q=80&w=1200'; 
    return 'https://images.unsplash.com/photo-1511512578047-dfb367046420?auto=format&fit=crop&q=80&w=1200';
  };

  React.useEffect(() => {
    if (isNativeOverlay && typeof window !== 'undefined' && (window as any).AndroidOverlay) {
      (window as any).AndroidOverlay.setInteractive(showPalette);
    }
  }, [showPalette, isNativeOverlay]);

  React.useEffect(() => {
    (window as any).togglePalette = (isOpen: boolean) => {
      setShowPalette(isOpen);
      if (!isOpen) {
        setSelectedButtonId(null);
      }
    };
    return () => {
      delete (window as any).togglePalette;
    };
  }, []);

  return {
    activeProfile, onUpdateProfile, onLogMessage, activeKeys, activeAxes, isNativeOverlay,
    showConfig, setShowConfig, selectedButtonId, setSelectedButtonId, screenshotMode, setScreenshotMode,
    customScreenshotUrl, setCustomScreenshotUrl, fileInputRef, isDragging, setIsDragging, nexionPos, setNexionPos,
    isDraggingNexion, setIsDraggingNexion, nexionDragHasMoved, showPalette, setShowPalette,
    activePlayer, setActivePlayer, hideGrid, setHideGrid, hideAllNodes, setHideAllNodes,
    bgDimLevel, setBgDimLevel, globalNodeOpacity, setGlobalNodeOpacity, selectedButton,
    handleContainerClick, handleDragStart, handleDragMove, handleDragEnd, handleUpdateBtnProperty,
    relocateButtonOffset, handleAddSpecificButton, handleAddNewButton, handleRemoveButton, getBackgroundUrl
  };
}
