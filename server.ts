import express from "express";
import path from "path";
import fs from "fs";
import { createServer as createViteServer } from "vite";

const app = express();
const PORT = 3000;

app.use(express.json());

// In-memory data store for live simulation state of Gamepad Mapper Mind – Nexion
let daemonState = {
  status: "CONNECTED_SHIZUKU", // DISCONNECTED | CHECKING | CONNECTED_SHIZUKU | CONNECTED_ADB
  daemonRunning: true,
  daemonVersion: "v2.8.4-Nexion",
  logLines: [
    "[INFO] TouchDaemon v2.8.4-Nexion started of process 9815 (shelld)",
    "[INFO] Hooked into backend socket namespace: @gampad_mapper_ipc",
    "[INFO] Initializing uinput driver injection device...",
    "[SUCCESS] Allocated /dev/uinput: Touch virtual device descriptor created (10 touch slots)",
    "[INFO] Native raw reading listening on /dev/input/event1 (Vortex XP107 DualMode Gamepad)",
    "[INFO] Native raw reading listening on /dev/input/event5 (Vortex Gyroscopic Motion Sensor Unit)",
    "[INFO] Shizuku user process bound securely via AIDL ITouchDaemonControl",
    "[SUCCESS] Client listening loop operational at sub-8ms frequency",
    "[GYRO] Madgwick Sensor Fusion active. 250Hz sample acquisition running...",
    "[INFO] Default profile for Genshin Impact loaded successfully"
  ]
};

let activeProfileId = "genshin";
let calibrationState = {
  offsetX: -0.0125,
  offsetY: 0.0084,
  offsetZ: 0.0031,
  samplesCollected: 512,
  noiseLevel: 0.0019,
  lastCalibrated: "2026-06-13 14:15:22"
};

// API: Get status
app.get("/api/daemon/status", (req, res) => {
  res.json(daemonState);
});

// API: Trigger action (Start daemon, Stop daemon, Change activation mode)
app.post("/api/daemon/control", (req, res) => {
  const { action, mode } = req.body;
  if (action === "start") {
    daemonState.daemonRunning = true;
    daemonState.status = mode === "desktop" ? "CONNECTED_ADB" : "CONNECTED_SHIZUKU";
    daemonState.logLines.push(`[INFO] [${new Date().toISOString()}] Daemon start requested via ${mode} mode.`);
    daemonState.logLines.push(`[SUCCESS] Daemon spawned successfully. Binary decrypted & verified.`);
  } else if (action === "stop") {
    daemonState.daemonRunning = false;
    daemonState.status = "DISCONNECTED";
    daemonState.logLines.push(`[INFO] [${new Date().toISOString()}] Daemon kill signal dispatch: shutting down /dev/uinput bindings.`);
  } else if (action === "toggle_mode") {
    if (daemonState.status === "CONNECTED_SHIZUKU") {
      daemonState.status = "CONNECTED_ADB";
    } else {
      daemonState.status = "CONNECTED_SHIZUKU";
    }
    daemonState.logLines.push(`[INFO] Switched principal orchestration target to ${daemonState.status}`);
  }
  res.json(daemonState);
});

// API: Add log
app.post("/api/daemon/log", (req, res) => {
  const { message } = req.body;
  if (message) {
    daemonState.logLines.push(`[USER] [${new Date().toLocaleTimeString()}] ${message}`);
    if (daemonState.logLines.length > 50) {
      daemonState.logLines.shift();
    }
  }
  res.json({ success: true });
});

// API: Calibration
app.post("/api/daemon/calibrate", (req, res) => {
  const { samples, offset } = req.body;
  calibrationState = {
    offsetX: offset ? offset.x : (Math.random() * 0.02 - 0.01),
    offsetY: offset ? offset.y : (Math.random() * 0.02 - 0.01),
    offsetZ: offset ? offset.z : (Math.random() * 0.02 - 0.01),
    samplesCollected: samples || 500,
    noiseLevel: 0.001 + Math.random() * 0.002,
    lastCalibrated: new Date().toISOString().replace('T', ' ').substring(0, 19)
  };
  
  daemonState.logLines.push(`[CALIBRATE] Gyro auto-calibration finished. New biases: X[${calibrationState.offsetX.toFixed(4)}] Y[${calibrationState.offsetY.toFixed(4)}] Z[${calibrationState.offsetZ.toFixed(4)}]`);
  daemonState.logLines.push(`[CALIBRATE] Average sample variance: ${(calibrationState.noiseLevel * 1000).toFixed(2)}mG. Applied to matrix filter.`);
  
  res.json(calibrationState);
});

app.get("/api/daemon/calibration", (req, res) => {
  res.json(calibrationState);
});

app.post("/api/daemon/inject", (req, res) => {
  const { command, id, x, y } = req.body;
  
  if (command === "tap") {
    console.log(`[INFO] Server acknowledged tap request for [${x}, ${y}], but execution is native.`);
  } else if (command === "swipe") {
    console.log(`[INFO] Server acknowledged swipe request, but execution is native.`);
  } else if (command === "touch_down") {
    console.log(`[INFO] Server acknowledged touch_down, execution native.`);
  } else if (command === "touch_move") {
    console.log(`[INFO] Server acknowledged touch_move, execution native.`);
  } else if (command === "touch_up") {
    console.log(`[INFO] Server acknowledged touch_up, execution native.`);
  } else if (command === "key") {
    console.log(`[INFO] Server acknowledged macro key, execution native.`);
  }
  
  if (daemonState.logLines.length > 50) daemonState.logLines.shift();
  
  res.json({ success: true, status: "delegated_to_native" });
});

// API Get/Set Active Profile ID
app.get("/api/profile/active", (req, res) => {
  res.json({ profileId: activeProfileId });
});

app.post("/api/profile/active", (req, res) => {
  const { profileId } = req.body;
  if (profileId) {
    activeProfileId = profileId;
    daemonState.logLines.push(`[PROFILE] Dynamic active app transition observed: Profile active -> [${profileId}]`);
  }
  res.json({ success: true, activeProfileId });
});

const PROFILES_PATH = path.join(process.cwd(), 'profiles.json');

// Helper to read profiles
function readProfiles() {
  try {
    if (fs.existsSync(PROFILES_PATH)) {
      const data = fs.readFileSync(PROFILES_PATH, 'utf-8');
      return JSON.parse(data);
    }
  } catch (err) {
    console.error('Error reading profiles:', err);
  }
  return {};
}

// Helper to write profiles
function writeProfiles(profiles: any) {
  try {
    fs.writeFileSync(PROFILES_PATH, JSON.stringify(profiles, null, 2), 'utf-8');
  } catch (err) {
    console.error('Error writing profiles:', err);
  }
}

// endpoints:
app.post('/api/profile/save', (req, res) => {
  try {
    const { profileId, mappings, joystick } = req.body;
    if (!profileId) {
      return res.status(400).json({ success: false, message: 'profileId is required' });
    }
    const profiles = readProfiles();
    profiles[profileId] = { mappings, joystick };
    writeProfiles(profiles);
    res.json({ success: true });
  } catch (err) {
    console.error(err);
    res.status(500).json({ success: false, error: 'Internal server error' });
  }
});

app.get('/api/profile/:id', (req, res) => {
  if (req.params.id === 'active') return; // Skip if active endpoint
  try {
    const profiles = readProfiles();
    const profile = profiles[req.params.id] || { mappings: [], joystick: { centerX: 250, centerY: 500, radius: 150 } };
    res.json(profile);
  } catch (err) {
    console.error(err);
    res.status(500).json({ success: false, error: 'Internal server error' });
  }
});

// Vite middleware for development or serving assets
async function startServer() {
  if (process.env.NODE_ENV !== "production") {
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: "spa",
    });
    app.use(vite.middlewares);
  } else {
    const distPath = path.join(process.cwd(), "dist");
    app.use(express.static(distPath));
    app.get("*", (req, res) => {
      res.sendFile(path.join(distPath, "index.html"));
    });
  }

  app.listen(PORT, "0.0.0.0", () => {
    console.log(`Gamepad Mapper Mind – Nexion express server running on http://localhost:${PORT}`);
  });
}

startServer();
