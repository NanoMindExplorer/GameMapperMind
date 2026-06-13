import express from "express";
import path from "path";
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

// AI Integration Tunnel State Store
let aiTunnelState = {
  isEnabled: false,
  activeAgent: "vlm_gemini", // vision_agent | vlm_gemini | reinforcement_rl
  tunnelStatus: "WAITING_FOR_CLIENT", // WAITING_FOR_CLIENT | TUNNEL_CONNECTED | AUTOPILOT_DRIVING
  clientIp: "192.168.1.104",
  apiToken: "NX-9981-GEMINI-TUNNEL",
  responseDelayMs: 64,
  confidenceScore: 0.95,
  totalModelCommandsExecuted: 142,
  allowAutonomousTap: true,
  allowMacroTriggers: true,
  logs: [
    "[AI-TUNNEL] Listening spawned on port 3000 at /api/ai/* secure bindings.",
    "[AI-TUNNEL] Authorization token successfully generated: NX-9981-GEMINI-TUNNEL",
    "[AI-TUNNEL] Waiting for VLM agent websocket or direct REST API handshake...",
    "[SYSTEM] AI Copilot Ready. Access parameters using correct header authentication."
  ]
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

// API: AI Integration Tunnel status
app.get("/api/ai/tunnel-status", (req, res) => {
  res.json(aiTunnelState);
});

// API: Configure AI settings
app.post("/api/ai/tunnel-control", (req, res) => {
  const { isEnabled, activeAgent, allowAutonomousTap, allowMacroTriggers } = req.body;
  
  if (isEnabled !== undefined) {
    aiTunnelState.isEnabled = isEnabled;
    aiTunnelState.logs.push(`[AI-TUNNEL] Auto-pilot status toggled to: ${isEnabled ? 'ACTIVE' : 'INACTIVE'}`);
    daemonState.logLines.push(`[AI-COPILOT] ${isEnabled ? 'Autonomous agent engaged' : 'Autonomous agent disengaged'}`);
  }
  if (activeAgent !== undefined) {
    aiTunnelState.activeAgent = activeAgent;
    aiTunnelState.logs.push(`[AI-TUNNEL] Agent model changed to: ${activeAgent.toUpperCase()}`);
  }
  if (allowAutonomousTap !== undefined) aiTunnelState.allowAutonomousTap = allowAutonomousTap;
  if (allowMacroTriggers !== undefined) aiTunnelState.allowMacroTriggers = allowMacroTriggers;
  
  res.json(aiTunnelState);
});

// API: Global Emergency Kill Switch
app.post("/api/ai/kill-switch", (req, res) => {
  aiTunnelState.isEnabled = false;
  aiTunnelState.tunnelStatus = "DISENGAGED";
  
  const timestamp = new Date().toLocaleTimeString();
  
  aiTunnelState.logs.push(`[${timestamp}] [EMERGENCY-KILL] !!! INSTANT TERMINATION TRIGGERED !!!`);
  aiTunnelState.logs.push(`[${timestamp}] [EMERGENCY-KILL] Disengaged all VLM/AI otonom drivers.`);
  if (aiTunnelState.logs.length > 50) aiTunnelState.logs.shift();
  
  daemonState.logLines.push(`[KILL-SWITCH] [${timestamp}] EMERGENCY STOP ACTIVATED. All virtual tactile uinput hooks have been wiped.`);
  daemonState.logLines.push(`[KILL-SWITCH] [${timestamp}] AI-driven automation & macro buffers forcibly purged.`);
  if (daemonState.logLines.length > 50) daemonState.logLines.shift();
  
  res.json({ success: true, aiTunnelState, daemonState });
});

// API: Post virtual controller command through AI Tunnel
app.post("/api/ai/input", (req, res) => {
  const { token, command, params } = req.body;
  
  if (!token || token !== aiTunnelState.apiToken) {
    return res.status(401).json({ error: "Unauthorized. Invalid or missing AI Tunnel Token." });
  }
  
  if (!aiTunnelState.isEnabled) {
    return res.status(400).json({ error: "AI Tunnel is currently disabled. Enable it in the interface first." });
  }
  
  aiTunnelState.tunnelStatus = "AUTOPILOT_DRIVING";
  aiTunnelState.totalModelCommandsExecuted += 1;
  aiTunnelState.confidenceScore = 0.85 + Math.random() * 0.14;
  aiTunnelState.responseDelayMs = Math.floor(45 + Math.random() * 30);
  
  const timestamp = new Date().toLocaleTimeString();
  const detailString = params ? JSON.stringify(params) : "None";
  
  const logMsg = `[COMMAND] Executed: ${command.toUpperCase()} | Params: ${detailString}`;
  aiTunnelState.logs.push(`[${timestamp}] ${logMsg}`);
  if (aiTunnelState.logs.length > 50) aiTunnelState.logs.shift();
  
  // Inject directly into Daemon raw device log
  let formatDeviceLog = `[AI-INJECT] Injected ${command} driver signal (${detailString})`;
  daemonState.logLines.push(formatDeviceLog);
  if (daemonState.logLines.length > 50) daemonState.logLines.shift();
  
  res.json({
    success: true,
    totalExecuted: aiTunnelState.totalModelCommandsExecuted,
    targetStatus: aiTunnelState.tunnelStatus
  });
});

// API: Run virtual model simulation triggers (for preview/testing)
app.post("/api/ai/sim-vision", (req, res) => {
  if (!aiTunnelState.isEnabled) {
    return res.status(400).json({ error: "Please enable the AI Tunnel first." });
  }

  const { scenarioId, customPrompt, customGoal } = req.body;
  let customAction: any = { cmd: "tap", params: { x: 500, y: 500, label: "Simulasi default" } };

  const timestamp = new Date().toLocaleTimeString();
  let logMsg = "";

  if (scenarioId === "harian_quest") {
    const actions = [
      { cmd: "tap", params: { x: 120, y: 150, label: "Membuka navigasi navigasi quest harian" } },
      { cmd: "drag", params: { fromX: 100, fromY: 200, toX: 500, toY: 600, label: "Navigasi otomatis menyusuri jalur kuil harian" } },
      { cmd: "press_button", params: { key: "BUTTON_A", label: "Dialog interaktif dengan NPC petualang harian" } },
      { cmd: "press_button", params: { key: "BUTTON_Y", label: "Mengambil hadiah petualangan harian" } }
    ];
    customAction = actions[Math.floor(Math.random() * actions.length)];
    logMsg = `[SIM-VLM] Skenario [Quest Harian] - Visual: ${customAction.params.label}. Mengeksekusi ${customAction.cmd.toUpperCase()}`;
  } 
  else if (scenarioId === "boss_dodge") {
    const actions = [
      { cmd: "press_button", params: { key: "BUTTON_B", label: "Menghindari serangan AoE Merah dengan Dash Cepat" } },
      { cmd: "gyro_tilt", params: { x: -0.22, y: 0.1, label: "Gyro: Melacak laju proyektil Boss" } },
      { cmd: "press_button", params: { key: "BUTTON_Y", label: "Meluncurkan serangan Ultimate saat Boss stagger" } },
      { cmd: "drag", params: { fromX: 300, fromY: 500, toX: 100, toY: 500, label: "Analog kiri: Menarik diri ke zona aman" } }
    ];
    customAction = actions[Math.floor(Math.random() * actions.length)];
    logMsg = `[SIM-VLM] Skenario [Boss Dodge] - Status: ${customAction.params.label}. Mengirim driver ${customAction.cmd.toUpperCase()}`;
  } 
  else if (scenarioId === "farm_ore") {
    const actions = [
      { cmd: "drag", params: { fromX: 250, fromY: 100, toX: 250, toY: 450, label: "Mengarahkan kamera ke bongkahan Magic Crystal Ore" } },
      { cmd: "press_button", params: { key: "BUTTON_A", label: "Serangan dasar menebas batu tambang" } },
      { cmd: "tap", params: { x: 800, y: 450, label: "Otomatisasi memungut ore jatuh ke inventori" } }
    ];
    customAction = actions[Math.floor(Math.random() * actions.length)];
    logMsg = `[SIM-VLM] Skenario [Farm Ore Material] - Deteksi: ${customAction.params.label}. Driver ${customAction.cmd.toUpperCase()} terinjeksi`;
  } 
  else if (scenarioId === "custom_scenario") {
    const promptStr = customPrompt || "Melakukan penjelajahan peta luar";
    const goalStr = customGoal || "Mendapatkan penemuan baru";
    
    const actions = [
      { cmd: "tap", params: { x: 450, y: 350, label: `Analisis VLM: Mengidentifikasi target sesuai instruksi [${promptStr}]` } },
      { cmd: "drag", params: { fromX: 200, fromY: 200, toX: 400, toY: 200, label: `Bergerak menyusuri area demi mencapai target [${goalStr}]` } },
      { cmd: "press_button", params: { key: "BUTTON_A", label: `Injeksi trigger interaksi otonom untuk insturksi custom` } }
    ];
    customAction = actions[Math.floor(Math.random() * actions.length)];
    logMsg = `[SIM-CUSTOM] Skenario Kustom: "${promptStr}" - Evaluasi: ${customAction.params.label}. Mode ${customAction.cmd.toUpperCase()} aktif`;
  } 
  else {
    const simActions = [
      { cmd: "tap", params: { x: 742, y: 184, label: "Membuka Menu / Peta" } },
      { cmd: "drag", params: { fromX: 200, fromY: 500, toX: 350, toY: 500, label: "Analog Kiri: Berjalan Maju" } },
      { cmd: "press_button", params: { key: "BUTTON_Y", label: "Trigger Skill Ultimate" } },
      { cmd: "gyro_tilt", params: { x: 0.15, y: -0.05, label: "Sensor Gyro: Mengarahkan Kamera" } }
    ];
    customAction = simActions[Math.floor(Math.random() * simActions.length)];
    logMsg = `[SIM-VLM] Pengenalan Visual: ${customAction.params.label}. Mengeksekusi driver ${customAction.cmd.toUpperCase()}`;
  }

  aiTunnelState.tunnelStatus = "AUTOPILOT_DRIVING";
  aiTunnelState.totalModelCommandsExecuted += 1;
  aiTunnelState.confidenceScore = 0.89 + Math.random() * 0.10;
  aiTunnelState.responseDelayMs = Math.floor(60 + Math.random() * 40);

  aiTunnelState.logs.push(`[${timestamp}] ${logMsg}`);
  if (aiTunnelState.logs.length > 50) aiTunnelState.logs.shift();

  let formatDeviceLog = `[AI-INJECT] Driver Autopilot meluncurkan aksi (${customAction.cmd}): ${JSON.stringify(customAction.params)}`;
  daemonState.logLines.push(formatDeviceLog);
  if (daemonState.logLines.length > 50) daemonState.logLines.shift();

  res.json(aiTunnelState);
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
