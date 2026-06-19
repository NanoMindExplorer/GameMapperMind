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
var import_cors = __toESM(require("cors"), 1);
var import_promises = __toESM(require("fs/promises"), 1);
var import_zod = require("zod");
var app = (0, import_express.default)();
var PORT = parseInt(process.env.PORT ?? "3000", 10);
app.use((0, import_cors.default)({
  origin: [/localhost/, /capacitor/],
  credentials: true
}));
app.use(import_express.default.json({ limit: "1mb" }));
function trimLog(arr, maxSize = 50) {
  return arr.length > maxSize ? arr.slice(arr.length - maxSize) : arr;
}
var STATE_FILE = import_path.default.join(process.cwd(), "state.json");
var appState = { logs: [] };
async function loadState() {
  try {
    const data = await import_promises.default.readFile(STATE_FILE, "utf-8");
    appState = JSON.parse(data);
  } catch (err) {
    console.log("No existing state file found or invalid JSON. Initializing new state.");
  }
}
async function saveState() {
  try {
    await import_promises.default.writeFile(STATE_FILE, JSON.stringify(appState, null, 2), "utf-8");
  } catch (err) {
    console.error("Failed to save state.");
  }
}
app.get("/api/health", (req, res) => {
  res.json({ status: "ok" });
});
var LogSchema = import_zod.z.object({
  message: import_zod.z.string(),
  instruksi: import_zod.z.string().optional()
});
app.post("/api/log", async (req, res) => {
  try {
    const parsed = LogSchema.parse(req.body);
    const clientIp = req.ip || "";
    appState.logs.push(`[${clientIp}] ${parsed.message} ${parsed.instruksi ? "- " + parsed.instruksi : ""}`);
    appState.logs = trimLog(appState.logs);
    await saveState();
    res.json({ success: true, count: appState.logs.length });
  } catch (error) {
    res.status(400).json({ error: "Invalid request payload" });
  }
});
app.get("/api/logs", (req, res) => {
  res.json({ logs: appState.logs });
});
async function startServer() {
  await loadState();
  if (process.env.NODE_ENV !== "production") {
    const { createServer: createViteServer } = await import("vite");
    const vite = await createViteServer({
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
  app.use((err, req, res, next) => {
    console.error("Global Error:", err.message);
    res.status(500).json({ error: "Internal Server Error" });
  });
  app.listen(PORT, "0.0.0.0", () => {
    console.log(`Server running on port ${PORT}`);
  });
}
startServer().catch((err) => {
  console.error("Failed to start server", err);
  process.exit(1);
});
//# sourceMappingURL=server.cjs.map
