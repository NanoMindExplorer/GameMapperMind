import React from 'react';
import { GamepadProfile, VirtualButton } from '../types';

export function useOverlayWysiwyg(props: any) {
  const { activeProfile, onUpdateProfile, onLogMessage, activeKeys = [], activeAxes = {lx:0, ly:0, rx:0, ry:0}, isNativeOverlay = false } = props;

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

    // Sync opacity local state with profile if provided on load
    React.useEffect(() => {
    if (activeProfile.globalOpacity !== undefined && activeProfile.globalOpacity !== globalNodeOpacity) {
      setGlobalNodeOpacity(activeProfile.globalOpacity);
    }
  }, [activeProfile.id]);

  // Visual Protection & Graphics Quality Engine State
  const [hideGrid, setHideGrid] = React.useState(false);
  const [hideAllNodes, setHideAllNodes] = React.useState(false);
  const [bgDimLevel, setBgDimLevel] = React.useState(0); // 0% Dim = Maximum Raw Graphic Quality (Perfect graphics, No obstruction)
  const [globalNodeOpacity, setGlobalNodeOpacity] = React.useState(activeProfile.globalOpacity ?? 80); // 80% default opacity

  // Update screenshot background to match active profiles
  React.useEffect(() => {
    if (activeProfile.screenshotMode) {
      setScreenshotMode(activeProfile.screenshotMode);
    } else if (activeProfile.id === 'genshin' || activeProfile.id === 'pubg' || activeProfile.id === 'codm' || activeProfile.id === 'efootball') {
      setScreenshotMode(activeProfile.id as any);
    }
    
    if (activeProfile.customScreenshotUrl) {
      setCustomScreenshotUrl(activeProfile.customScreenshotUrl);
    } else {
      setCustomScreenshotUrl(null); // Reset when switching to a profile without custom screenshot
    }
  }, [activeProfile.id, activeProfile.screenshotMode, activeProfile.customScreenshotUrl]);

  const selectedButton = activeProfile.buttons.find(b => b.id === selectedButtonId);

  // Drag simulation helpers
  const handleDragStart = (e: React.MouseEvent, btnId: string) => {
    e.stopPropagation();
    setSelectedButtonId(btnId);
    setIsDragging(true);
  };

  const handleDragEnd = () => {
    setIsDragging(false);
    setIsDraggingNexion(false);
  };

  const handleDragMove = (e: React.MouseEvent) => {
    if (isDraggingNexion) {
      nexionDragHasMoved.current = true;
      const container = e.currentTarget.getBoundingClientRect();
      const x = Math.max(0, Math.min(100, ((e.clientX - container.left) / container.width) * 100));
      const y = Math.max(0, Math.min(100, ((e.clientY - container.top) / container.height) * 100));
      setNexionPos({ x, y });
      return;
    }

    if (!isDragging || !selectedButtonId) return;
    
    const container = e.currentTarget.getBoundingClientRect();
    const x = Math.max(0, Math.min(100, ((e.clientX - container.left) / container.width) * 100));
    const y = Math.max(0, Math.min(100, ((e.clientY - container.top) / container.height) * 100));

    const updatedButtons = activeProfile.buttons.map(b => {
      if (b.id === selectedButtonId) {
        return { ...b, x, y };
      }
      return b;
    });
    
    onUpdateProfile({ ...activeProfile, buttons: updatedButtons });
  };

  const handleContainerClick = () => {
    setSelectedButtonId(null);
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

  const handleAddSpecificButton = (label: string, mappedKey: string, androidEventCode: number, defaultSize: number = 56, type: VirtualButton['type'] = 'button') => {
    const freshId = `btn_${Date.now().toString().slice(-4)}`;
    const newBtn: VirtualButton = {
      player: activePlayer,
      id: freshId,
      label,
      type,
      x: 50,
      y: 50,
      width: defaultSize,
      height: defaultSize,
      mappedKey,
      androidEventCode,
      opacity: 0.6
    };
    onUpdateProfile({
      ...activeProfile,
      buttons: [...activeProfile.buttons, newBtn]
    });
    setSelectedButtonId(freshId);
    onLogMessage(`Overlay Canvas: Menambahkan ${label}`);
  };

  const handleAddNewButton = (
    type: 'button' | 'analog_stick' | 'gyro_area' | 'swipe',
    swipeDirection?: 'UP' | 'DOWN' | 'LEFT' | 'RIGHT'
  ) => {
    const freshId = `btn_${Date.now().toString().slice(-4)}`;
    
    let label = 'New Tap';
    let mappedKey = 'BUTTON_B';
    let androidEventCode = 97;
    
    if (type === 'analog_stick') {
      label = 'L-Stick';
      mappedKey = 'L_STICK';
      androidEventCode = 0;
    } else if (type === 'gyro_area') {
      label = 'Camera Trigger';
      mappedKey = 'GYRO';
      androidEventCode = 0;
    } else if (type === 'swipe') {
      if (swipeDirection === 'UP') {
        label = 'Swipe Atas (UP)';
        mappedKey = 'R_STICK_UP';
        androidEventCode = 201;
      } else if (swipeDirection === 'DOWN') {
        label = 'Swipe Bawah (DOWN)';
        mappedKey = 'R_STICK_DOWN';
        androidEventCode = 202;
      } else if (swipeDirection === 'LEFT') {
        label = 'Swipe Kiri (LEFT)';
        mappedKey = 'R_STICK_LEFT';
        androidEventCode = 203;
      } else if (swipeDirection === 'RIGHT') {
        label = 'Swipe Kanan (RIGHT)';
        mappedKey = 'R_STICK_RIGHT';
        androidEventCode = 204;
      }
    }

    const newBtn: VirtualButton = {
      player: activePlayer,
      id: freshId,
      label,
      type,
      x: 50,
      y: 50,
      width: type === 'button' ? 56 : type === 'analog_stick' ? 120 : type === 'swipe' ? 68 : 200,
      height: type === 'button' ? 56 : type === 'analog_stick' ? 120 : type === 'swipe' ? 68 : 120,
      mappedKey,
      androidEventCode,
      opacity: 0.6,
      swipeDirection
    };
    onUpdateProfile({
      ...activeProfile,
      buttons: [...activeProfile.buttons, newBtn]
    });
    setSelectedButtonId(freshId);
    onLogMessage(`Overlay Canvas: Appended virtual node '${newBtn.label}' to active viewport`);
  };

  const handleRemoveButton = (btnId: string) => {
    const updated = activeProfile.buttons.filter(b => b.id !== btnId);
    onUpdateProfile({ ...activeProfile, buttons: updated });
    setSelectedButtonId(null);
    onLogMessage(`Overlay Canvas: Discarded node ${btnId} layout constraints`);
  };

  // Safe relocation simulating drag directly inside relative bounding boxes
  const relocateButtonOffset = (direction: 'up' | 'down' | 'left' | 'right') => {
    if (!selectedButton) return;
    let { x, y } = selectedButton;
    if (direction === 'up') y = Math.max(0, y - 2);
    if (direction === 'down') y = Math.min(100, y + 2);
    if (direction === 'left') x = Math.max(0, x - 2);
    if (direction === 'right') x = Math.min(100, x + 2);

    handleUpdateBtnProperty('x', x);
    handleUpdateBtnProperty('y', y);
  };

  // Background mock representation
  const getBackgroundUrl = () => {
    if (screenshotMode === 'custom' && customScreenshotUrl) return customScreenshotUrl;
    if (screenshotMode === 'genshin') return 'https://images.unsplash.com/photo-1542751371-adc38448a05e?auto=format&fit=crop&q=80&w=1200';
    if (screenshotMode === 'pubg') return 'https://images.unsplash.com/photo-1534423861386-85a16f5d13fd?auto=format&fit=crop&q=80&w=1200';
    if (screenshotMode === 'codm') return 'https://images.unsplash.com/photo-1511512578047-dfb367046420?auto=format&fit=crop&q=80&w=1200';
    if (screenshotMode === 'efootball') return 'https://images.unsplash.com/photo-1508098682722-e99c43a406b2?auto=format&fit=crop&q=80&w=1200'; // high quality green soccer field
    return 'https://images.unsplash.com/photo-1511512578047-dfb367046420?auto=format&fit=crop&q=80&w=1200';
  };

  // Register togglePalette globally for Android
  React.useEffect(() => {
    if (isNativeOverlay && typeof window !== 'undefined' && window.AndroidOverlay) {
      window.AndroidOverlay.setInteractive(showPalette);
    }
  }, [showPalette, isNativeOverlay]);

  React.useEffect(() => {
    window.togglePalette = (isOpen: boolean) => {
      setShowPalette(isOpen);
      if (!isOpen) {
        setSelectedButtonId(null);
      }
    };
    
  return {
    activeProfile, onUpdateProfile, onLogMessage, activeKeys, activeAxes, isNativeOverlay,
    showConfig, setShowConfig, selectedButtonId, setSelectedButtonId, screenshotMode, setScreenshotMode,
    customScreenshotUrl, setCustomScreenshotUrl, fileInputRef, isDragging, setIsDragging, nexionPos, setNexionPos,
    isDraggingNexion, setIsDraggingNexion, nexionDragHasMoved, showPalette, setShowPalette, activePlayer, setActivePlayer,
    hideGrid, setHideGrid, hideAllNodes, setHideAllNodes, bgDimLevel, setBgDimLevel, globalNodeOpacity, setGlobalNodeOpacity,
    handleContainerClick, handleDragMove, handleDragEnd, handleTouchMove, handleTouchEnd, handleAddButton,
    handleRemoveButton, relocateButtonOffset, getBackgroundUrl, handleUpdateBtnProperty,
    selectedButton: activeProfile?.buttons?.find((b: any) => b.id === selectedButtonId)
  };
}
