import React, { useState, useEffect } from 'react';
import ShizukuBridge from '../plugins/ShizukuBridge';

interface MappingItem {
  hardwareKey: string;
  x: number;
  y: number;
}

const BUTTONS = [
  "A", "B", "X", "Y", "LB", "RB", "LT", "RT",
  "START", "SELECT", "L3", "R3", "UP", "DOWN", "LEFT", "RIGHT"
];

// [VERIFIED] - Simple mapping setup UI
export function MappingSetup({ profileId }: { profileId: string }) {
  const [mappings, setMappings] = useState<MappingItem[]>([]);
  const [joystick, setJoystick] = useState({ centerX: 250, centerY: 500, radius: 150 });
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    fetchProfile();
  }, [profileId]);

  const fetchProfile = async () => {
    try {
      const res = await fetch(`/api/profile/${profileId}`);
      if (res.ok) {
        const data = await res.json();
        if (data.mappings) {
          // Merge with default buttons
          const defaultMappings = BUTTONS.map(btn => ({
            hardwareKey: btn,
            x: 0,
            y: 0
          }));
          
          const merged = defaultMappings.map(def => {
            const found = data.mappings.find((m: any) => m.hardwareKey === def.hardwareKey);
            return found || def;
          });
          
          setMappings(merged);
        } else {
          setMappings(BUTTONS.map(btn => ({ hardwareKey: btn, x: 0, y: 0 })));
        }
        if (data.joystick) {
          setJoystick(data.joystick);
        }
      } else {
        setMappings(BUTTONS.map(btn => ({ hardwareKey: btn, x: 0, y: 0 })));
      }
    } catch (err) {
      console.error(err);
      setMappings(BUTTONS.map(btn => ({ hardwareKey: btn, x: 0, y: 0 })));
    }
  };

  const handleChange = (index: number, field: 'x' | 'y', value: number) => {
    const newMappings = [...mappings];
    newMappings[index][field] = value;
    setMappings(newMappings);
  };

  const handleJoystickChange = (field: 'centerX' | 'centerY' | 'radius', value: number) => {
    setJoystick(prev => ({ ...prev, [field]: value }));
  };

  const handleTest = async (x: number, y: number) => {
    try {
      await ShizukuBridge.injectTap({ x, y });
    } catch (err) {
      console.error("Test tap failed:", err);
    }
  };

  const handleSave = async () => {
    setLoading(true);
    try {
      await fetch('/api/profile/save', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ profileId, mappings, joystick })
      });
      alert('Tersimpan!');
    } catch (err) {
      console.error(err);
      alert('Gagal menyimpan');
    }
    setLoading(false);
  };

  return (
    <div className="p-4 bg-gray-900 text-white rounded-lg w-full max-w-2xl mx-auto h-full overflow-auto">
      <h2 className="text-xl font-bold mb-4">Pengaturan Mapping: {profileId}</h2>
      
      <div className="mb-6 bg-gray-800 p-4 rounded">
        <h3 className="font-semibold mb-2">Joystick Axis (Kiri)</h3>
        <div className="flex gap-2 items-center flex-wrap">
          <label>X:</label>
          <input 
            type="number" 
            className="w-20 bg-gray-700 p-1 rounded text-white" 
            value={joystick.centerX} 
            onChange={e => handleJoystickChange('centerX', parseInt(e.target.value) || 0)} 
          />
          <label className="ml-2">Y:</label>
          <input 
            type="number" 
            className="w-20 bg-gray-700 p-1 rounded text-white" 
            value={joystick.centerY} 
            onChange={e => handleJoystickChange('centerY', parseInt(e.target.value) || 0)} 
          />
          <label className="ml-2">Radius:</label>
          <input 
            type="number" 
            className="w-20 bg-gray-700 p-1 rounded text-white" 
            value={joystick.radius} 
            onChange={e => handleJoystickChange('radius', parseInt(e.target.value) || 0)} 
          />
        </div>
      </div>

      <div className="space-y-2 mb-4">
        {mappings.map((m, idx) => (
          <div key={m.hardwareKey} className="flex gap-2 items-center bg-gray-800 p-2 rounded">
            <span className="w-20 font-bold">{m.hardwareKey}</span>
            <input 
              type="number" 
              className="w-20 bg-gray-700 p-1 rounded text-white" 
              value={m.x} 
              onChange={e => handleChange(idx, 'x', parseInt(e.target.value) || 0)}
              placeholder="X"
            />
            <input 
              type="number" 
              className="w-20 bg-gray-700 p-1 rounded text-white" 
              value={m.y} 
              onChange={e => handleChange(idx, 'y', parseInt(e.target.value) || 0)}
              placeholder="Y"
            />
            <button 
              onClick={() => handleTest(m.x, m.y)}
              className="px-3 py-1 bg-blue-600 hover:bg-blue-500 rounded text-sm ml-auto"
            >
              Test
            </button>
          </div>
        ))}
      </div>
      
      <button 
        onClick={handleSave} 
        disabled={loading}
        className="w-full py-2 bg-green-600 hover:bg-green-500 rounded font-bold mt-4"
      >
        {loading ? 'Menyimpan...' : 'Simpan Profil'}
      </button>
    </div>
  );
}
