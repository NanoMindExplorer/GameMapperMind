const fs = require('fs');

const o = fs.readFileSync('src/components/OverlayWysiwyg.tsx', 'utf-8');

const hookParts = o.match(/export default function OverlayWysiwyg\([^)]+\) \{([\s\S]+?)return \(/);
const hookBody = hookParts[1];

let hookCode = `import React from 'react';
import { GamepadProfile, VirtualButton } from '../types';

export function useOverlayWysiwyg(props: any) {
  const { activeProfile, onUpdateProfile, onLogMessage, activeKeys = [], activeAxes = {lx:0, ly:0, rx:0, ry:0}, isNativeOverlay = false } = props;
` + hookBody + `
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
`;

fs.writeFileSync('src/hooks/useOverlayWysiwyg.ts', hookCode);
