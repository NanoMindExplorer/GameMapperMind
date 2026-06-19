import {StrictMode} from 'react';
import {createRoot} from 'react-dom/client';
import App from './App.tsx';
import OverlayApp from './OverlayApp.tsx';
import './index.css';

const isOverlay = window.location.search.includes('overlay=true');

if (isOverlay) {
  document.documentElement.classList.add('is-overlay');
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>

    {isOverlay ? <OverlayApp /> : <App />}
  </StrictMode>,
);
