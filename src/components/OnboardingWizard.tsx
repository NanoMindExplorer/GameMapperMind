import React, { useState } from 'react';
import { useShizuku } from '../hooks/useShizuku';
import TouchInjection from '../plugins/TouchInjection';

interface OnboardingWizardProps {
  onComplete: () => void;
}

export const OnboardingWizard: React.FC<OnboardingWizardProps> = ({ onComplete }) => {
  const [step, setStep] = useState(1);
  const [status, setStatus] = useState<'idle' | 'loading' | 'success' | 'error'>('idle');
  const [message, setMessage] = useState('');
  const { requestShizukuPermission, startDaemon } = useShizuku();

  const steps = [
    { id: 1, title: "Aktifkan Shizuku", desc: "Pastikan Shizuku sudah berjalan (via Wireless Debugging)" },
    { id: 2, title: "Berikan Izin Shizuku", desc: "Izinkan GameMapperMind mengakses Shizuku" },
    { id: 3, title: "Test Touch Injection", desc: "Pastikan injeksi sentuhan bekerja" },
    { id: 4, title: "Selesai", desc: "Wizard selesai. Siap digunakan!" }
  ];

  const currentStep = steps.find(s => s.id === step)!;

  const handleNext = async () => {
    if (step === 1) {
      setStatus('loading');
      setMessage('Pastikan Shizuku aktif...');
      setTimeout(() => {
        setStatus('success');
        setMessage('Lanjut ke langkah izin.');
        setStep(2);
      }, 800);
    } else if (step === 2) {
      setStatus('loading');
      setMessage('Meminta izin Shizuku...');
      try {
        const result = await requestShizukuPermission();
        if (result.success) {
          setStatus('success');
          setMessage('Izin OK! Memulai daemon...');
          await startDaemon();
          setTimeout(() => setStep(3), 1000);
        } else {
          setStatus('error');
          setMessage(result.error || 'Gagal mendapatkan izin.');
        }
      } catch (e: any) {
        setStatus('error');
        setMessage(e.message || 'Error saat request permission.');
      }
    } else if (step === 3) {
      setStatus('loading');
      setMessage('Test injection di tengah layar...');
      try {
        const res = await TouchInjection.testInjection({ x: 540, y: 960 });
        if (res && res.success !== false) {
          setStatus('success');
          setMessage('Test berhasil! Sentuhan muncul di layar.');
          setTimeout(() => setStep(4), 1400);
        } else {
          setStatus('error');
          setMessage('Test gagal. Cek daemon & Shizuku.');
        }
      } catch {
        setStatus('error');
        setMessage('Test error. Restart daemon.');
      }
    } else if (step === 4) {
      try {
        const { Preferences } = await import('@capacitor/preferences');
        await Preferences.set({ key: 'onboardingCompleted', value: 'true' });
      } catch {}
      onComplete();
    }
  };

  return (
    <div className="fixed inset-0 bg-black/90 z-[100] flex items-center justify-center p-4">
      <div className="bg-zinc-900 border border-zinc-700 rounded-2xl w-full max-w-md p-6">
        <div className="flex justify-center gap-2 mb-6">
          {steps.map((s, i) => (
            <div key={i} className={`w-3 h-3 rounded-full ${s.id === step ? 'bg-blue-500 scale-125' : s.id < step ? 'bg-emerald-500' : 'bg-zinc-600'}`} />
          ))}
        </div>

        <h2 className="text-center text-2xl font-semibold mb-1">Setup Pertama Kali</h2>
        <p className="text-center text-sm text-zinc-400 mb-6">GameMapperMind v2.3.0 — Phase 1</p>

        <div className="mb-6">
          <div className="text-sm text-zinc-400 mb-1">Langkah {step} dari 4</div>
          <div className="text-xl font-semibold mb-1">{currentStep.title}</div>
          <p className="text-sm text-zinc-400">{currentStep.desc}</p>
        </div>

        {status !== 'idle' && (
          <div className={`mb-6 p-4 rounded-xl text-sm border ${status === 'success' ? 'bg-emerald-950 border-emerald-800 text-emerald-400' : status === 'error' ? 'bg-red-950 border-red-800 text-red-400' : 'bg-blue-950 border-blue-800 text-blue-400'}`}>
            {message}
          </div>
        )}

        <div className="flex gap-3">
          {step > 1 && step < 4 && (
            <button onClick={() => { setStatus('idle'); setStep(step-1); }} className="flex-1 py-3 rounded-xl border border-zinc-700 active:bg-zinc-800">Kembali</button>
          )}
          <button onClick={handleNext} disabled={status === 'loading'} className="flex-1 py-3 rounded-xl bg-white text-black font-medium active:bg-zinc-200 disabled:opacity-50">
            {step === 4 ? 'Selesai & Mulai Bermain' : status === 'loading' ? 'Memproses...' : 'Lanjutkan'}
          </button>
        </div>

        {step === 1 && <p className="text-[10px] text-center text-zinc-500 mt-4">Buka app Shizuku → Start via Wireless Debugging</p>}
      </div>
    </div>
  );
};
