var __create = Object.create;
var __defProp = Object.defineProperty;
var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
var __getOwnPropNames = Object.getOwnPropertyNames;
var __getProtoOf = Object.getPrototypeOf;
var __hasOwnProp = Object.prototype.hasOwnProperty;
var __copyProps = (to, from, except, desc) => {
  if (from && typeof from === "object" || typeof from === "function") {
    for (let key of __getOwnPropNames(from))
      if (!__hasOwnProp.call(to, key) && key !== except)
        __defProp(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable });
  }
  return to;
};
var __toESM = (mod, isNodeMode, target) => (target = mod != null ? __create(__getProtoOf(mod)) : {}, __copyProps(
  // If the importer is in node compatibility mode or this is not an ESM
  // file that has been converted to a CommonJS file using a Babel-
  // compatible transform (i.e. "__esModule" has not been set), then set
  // "default" to the CommonJS "module.exports" for node compatibility.
  isNodeMode || !mod || !mod.__esModule ? __defProp(target, "default", { value: mod, enumerable: true }) : target,
  mod
));

// server.ts
var import_express = __toESM(require("express"), 1);
var import_path = __toESM(require("path"), 1);
var import_vite = require("vite");
var app = (0, import_express.default)();
var PORT = 3e3;
app.use(import_express.default.json());
var daemonState = {
  status: "CONNECTED_SHIZUKU",
  // DISCONNECTED | CHECKING | CONNECTED_SHIZUKU | CONNECTED_ADB
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
var activeProfileId = "genshin";
var calibrationState = {
  offsetX: -0.0125,
  offsetY: 84e-4,
  offsetZ: 31e-4,
  samplesCollected: 512,
  noiseLevel: 19e-4,
  lastCalibrated: "2026-06-13 14:15:22"
};
var aiTunnelState = {
  isEnabled: false,
  activeAgent: "vlm_gemini",
  // vision_agent | vlm_gemini | reinforcement_rl
  tunnelStatus: "WAITING_FOR_CLIENT",
  // WAITING_FOR_CLIENT | TUNNEL_CONNECTED | AUTOPILOT_DRIVING
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
app.get("/api/daemon/status", (req, res) => {
  res.json(daemonState);
});
app.post("/api/daemon/control", (req, res) => {
  const { action, mode } = req.body;
  if (action === "start") {
    daemonState.daemonRunning = true;
    daemonState.status = mode === "desktop" ? "CONNECTED_ADB" : "CONNECTED_SHIZUKU";
    daemonState.logLines.push(`[INFO] [${(/* @__PURE__ */ new Date()).toISOString()}] Daemon start requested via ${mode} mode.`);
    daemonState.logLines.push(`[SUCCESS] Daemon spawned successfully. Binary decrypted & verified.`);
  } else if (action === "stop") {
    daemonState.daemonRunning = false;
    daemonState.status = "DISCONNECTED";
    daemonState.logLines.push(`[INFO] [${(/* @__PURE__ */ new Date()).toISOString()}] Daemon kill signal dispatch: shutting down /dev/uinput bindings.`);
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
app.post("/api/daemon/log", (req, res) => {
  const { message } = req.body;
  if (message) {
    daemonState.logLines.push(`[USER] [${(/* @__PURE__ */ new Date()).toLocaleTimeString()}] ${message}`);
    if (daemonState.logLines.length > 50) {
      daemonState.logLines.shift();
    }
  }
  res.json({ success: true });
});
app.post("/api/daemon/calibrate", (req, res) => {
  const { samples, offset } = req.body;
  calibrationState = {
    offsetX: offset ? offset.x : Math.random() * 0.02 - 0.01,
    offsetY: offset ? offset.y : Math.random() * 0.02 - 0.01,
    offsetZ: offset ? offset.z : Math.random() * 0.02 - 0.01,
    samplesCollected: samples || 500,
    noiseLevel: 1e-3 + Math.random() * 2e-3,
    lastCalibrated: (/* @__PURE__ */ new Date()).toISOString().replace("T", " ").substring(0, 19)
  };
  daemonState.logLines.push(`[CALIBRATE] Gyro auto-calibration finished. New biases: X[${calibrationState.offsetX.toFixed(4)}] Y[${calibrationState.offsetY.toFixed(4)}] Z[${calibrationState.offsetZ.toFixed(4)}]`);
  daemonState.logLines.push(`[CALIBRATE] Average sample variance: ${(calibrationState.noiseLevel * 1e3).toFixed(2)}mG. Applied to matrix filter.`);
  res.json(calibrationState);
});
app.get("/api/daemon/calibration", (req, res) => {
  res.json(calibrationState);
});
app.post("/api/daemon/inject", (req, res) => {
  const { command, x, y, duration } = req.body;
  if (command === "tap") {
    daemonState.logLines.push(`[INJECT-SIM] Executing physical tap at [${x}, ${y}]`);
  } else if (command === "swipe") {
    daemonState.logLines.push(`[INJECT-SIM] Executing physical swipe / drag`);
  } else if (command === "key") {
    daemonState.logLines.push(`[INJECT-SIM] Executing macro key press`);
  }
  if (daemonState.logLines.length > 50) daemonState.logLines.shift();
  res.json({ success: true, status: "injected_simulated" });
});
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
app.get("/api/ai/tunnel-status", (req, res) => {
  res.json(aiTunnelState);
});
app.post("/api/ai/tunnel-control", (req, res) => {
  const { isEnabled, activeAgent, allowAutonomousTap, allowMacroTriggers } = req.body;
  if (isEnabled !== void 0) {
    aiTunnelState.isEnabled = isEnabled;
    aiTunnelState.logs.push(`[AI-TUNNEL] Auto-pilot status toggled to: ${isEnabled ? "ACTIVE" : "INACTIVE"}`);
    daemonState.logLines.push(`[AI-COPILOT] ${isEnabled ? "Autonomous agent engaged" : "Autonomous agent disengaged"}`);
  }
  if (activeAgent !== void 0) {
    aiTunnelState.activeAgent = activeAgent;
    aiTunnelState.logs.push(`[AI-TUNNEL] Agent model changed to: ${activeAgent.toUpperCase()}`);
  }
  if (allowAutonomousTap !== void 0) aiTunnelState.allowAutonomousTap = allowAutonomousTap;
  if (allowMacroTriggers !== void 0) aiTunnelState.allowMacroTriggers = allowMacroTriggers;
  res.json(aiTunnelState);
});
app.post("/api/ai/kill-switch", (req, res) => {
  aiTunnelState.isEnabled = false;
  aiTunnelState.tunnelStatus = "DISENGAGED";
  const timestamp = (/* @__PURE__ */ new Date()).toLocaleTimeString();
  aiTunnelState.logs.push(`[${timestamp}] [EMERGENCY-KILL] !!! INSTANT TERMINATION TRIGGERED !!!`);
  aiTunnelState.logs.push(`[${timestamp}] [EMERGENCY-KILL] Disengaged all VLM/AI otonom drivers.`);
  if (aiTunnelState.logs.length > 50) aiTunnelState.logs.shift();
  daemonState.logLines.push(`[KILL-SWITCH] [${timestamp}] EMERGENCY STOP ACTIVATED. All virtual tactile uinput hooks have been wiped.`);
  daemonState.logLines.push(`[KILL-SWITCH] [${timestamp}] AI-driven automation & macro buffers forcibly purged.`);
  if (daemonState.logLines.length > 50) daemonState.logLines.shift();
  res.json({ success: true, aiTunnelState, daemonState });
});
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
  const timestamp = (/* @__PURE__ */ new Date()).toLocaleTimeString();
  const detailString = params ? JSON.stringify(params) : "None";
  const logMsg = `[COMMAND] Executed: ${command.toUpperCase()} | Params: ${detailString}`;
  aiTunnelState.logs.push(`[${timestamp}] ${logMsg}`);
  if (aiTunnelState.logs.length > 50) aiTunnelState.logs.shift();
  let formatDeviceLog = `[AI-INJECT] Injected ${command} driver signal (${detailString})`;
  daemonState.logLines.push(formatDeviceLog);
  if (daemonState.logLines.length > 50) daemonState.logLines.shift();
  res.json({
    success: true,
    totalExecuted: aiTunnelState.totalModelCommandsExecuted,
    targetStatus: aiTunnelState.tunnelStatus
  });
});
app.post("/api/ai/sim-vision", (req, res) => {
  if (!aiTunnelState.isEnabled) {
    return res.status(400).json({ error: "Please enable the AI Tunnel first." });
  }
  const { scenarioId, customPrompt, customGoal } = req.body;
  let customAction = { cmd: "tap", params: { x: 500, y: 500, label: "Simulasi default" } };
  const timestamp = (/* @__PURE__ */ new Date()).toLocaleTimeString();
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
  } else if (scenarioId === "boss_dodge") {
    const actions = [
      { cmd: "press_button", params: { key: "BUTTON_B", label: "Menghindari serangan AoE Merah dengan Dash Cepat" } },
      { cmd: "gyro_tilt", params: { x: -0.22, y: 0.1, label: "Gyro: Melacak laju proyektil Boss" } },
      { cmd: "press_button", params: { key: "BUTTON_Y", label: "Meluncurkan serangan Ultimate saat Boss stagger" } },
      { cmd: "drag", params: { fromX: 300, fromY: 500, toX: 100, toY: 500, label: "Analog kiri: Menarik diri ke zona aman" } }
    ];
    customAction = actions[Math.floor(Math.random() * actions.length)];
    logMsg = `[SIM-VLM] Skenario [Boss Dodge] - Status: ${customAction.params.label}. Mengirim driver ${customAction.cmd.toUpperCase()}`;
  } else if (scenarioId === "farm_ore") {
    const actions = [
      { cmd: "drag", params: { fromX: 250, fromY: 100, toX: 250, toY: 450, label: "Mengarahkan kamera ke bongkahan Magic Crystal Ore" } },
      { cmd: "press_button", params: { key: "BUTTON_A", label: "Serangan dasar menebas batu tambang" } },
      { cmd: "tap", params: { x: 800, y: 450, label: "Otomatisasi memungut ore jatuh ke inventori" } }
    ];
    customAction = actions[Math.floor(Math.random() * actions.length)];
    logMsg = `[SIM-VLM] Skenario [Farm Ore Material] - Deteksi: ${customAction.params.label}. Driver ${customAction.cmd.toUpperCase()} terinjeksi`;
  } else if (scenarioId === "custom_scenario") {
    const promptStr = customPrompt || "Melakukan penjelajahan peta luar";
    const goalStr = customGoal || "Mendapatkan penemuan baru";
    const actions = [
      { cmd: "tap", params: { x: 450, y: 350, label: `Analisis VLM: Mengidentifikasi target sesuai instruksi [${promptStr}]` } },
      { cmd: "drag", params: { fromX: 200, fromY: 200, toX: 400, toY: 200, label: `Bergerak menyusuri area demi mencapai target [${goalStr}]` } },
      { cmd: "press_button", params: { key: "BUTTON_A", label: `Injeksi trigger interaksi otonom untuk insturksi custom` } }
    ];
    customAction = actions[Math.floor(Math.random() * actions.length)];
    logMsg = `[SIM-CUSTOM] Skenario Kustom: "${promptStr}" - Evaluasi: ${customAction.params.label}. Mode ${customAction.cmd.toUpperCase()} aktif`;
  } else {
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
  aiTunnelState.confidenceScore = 0.89 + Math.random() * 0.1;
  aiTunnelState.responseDelayMs = Math.floor(60 + Math.random() * 40);
  aiTunnelState.logs.push(`[${timestamp}] ${logMsg}`);
  if (aiTunnelState.logs.length > 50) aiTunnelState.logs.shift();
  let formatDeviceLog = `[AI-INJECT] Driver Autopilot meluncurkan aksi (${customAction.cmd}): ${JSON.stringify(customAction.params)}`;
  daemonState.logLines.push(formatDeviceLog);
  if (daemonState.logLines.length > 50) daemonState.logLines.shift();
  res.json(aiTunnelState);
});
async function startServer() {
  if (process.env.NODE_ENV !== "production") {
    const vite = await (0, import_vite.createServer)({
      server: { middlewareMode: true },
      appType: "spa"
    });
    app.use(vite.middlewares);
  } else {
    const distPath = import_path.default.join(process.cwd(), "dist");
    app.use(import_express.default.static(distPath));
    app.get("*", (req, res) => {
      res.sendFile(import_path.default.join(distPath, "index.html"));
    });
  }
  app.listen(PORT, "0.0.0.0", () => {
    console.log(`Gamepad Mapper Mind \u2013 Nexion express server running on http://localhost:${PORT}`);
  });
}
startServer();
//# sourceMappingURL=server.cjs.map
