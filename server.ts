import express, { Request, Response, NextFunction } from "express";
import path from "path";
import cors from "cors";
import fs from "fs/promises";
import crypto from "crypto";
import { z } from "zod";

const app = express();
const PORT: number = parseInt(process.env.PORT ?? "3000", 10);
const API_TOKEN = process.env.AI_TUNNEL_TOKEN ?? crypto.randomUUID();

app.use(cors({
  origin: [/localhost/, /capacitor/],
  credentials: true
}));

app.use(express.json({ limit: "1mb" }));

// Bug #21: trimLog
function trimLog<T>(arr: T[], maxSize: number = 50): T[] {
  return arr.length > maxSize ? arr.slice(arr.length - maxSize) : arr;
}

// Bug #14: State persistensi
const STATE_FILE = path.join(process.cwd(), "state.json");
interface AppState {
  logs: string[];
}
let appState: AppState = { logs: [] };

async function loadState() {
  try {
    const data = await fs.readFile(STATE_FILE, "utf-8");
    appState = JSON.parse(data);
  } catch (err: unknown) {
    console.log("No existing state file found or invalid JSON. Initializing new state.");
  }
}

async function saveState() {
  try {
    await fs.writeFile(STATE_FILE, JSON.stringify(appState, null, 2), "utf-8");
  } catch (err: unknown) {
    console.error("Failed to save state.");
  }
}

app.get("/api/health", (req: Request, res: Response) => {
  res.json({ status: "ok" });
});

// Bug #16: typo instruksi
const LogSchema = z.object({
  message: z.string(),
  instruksi: z.string().optional()
});

app.post("/api/log", async (req: Request, res: Response) => {
  try {
    const parsed = LogSchema.parse(req.body);
    const clientIp = req.ip || ""; // Bug #10
    
    appState.logs.push(`[${clientIp}] ${parsed.message} ${parsed.instruksi ? '- ' + parsed.instruksi : ''}`);
    appState.logs = trimLog(appState.logs);
    
    await saveState();
    res.json({ success: true, count: appState.logs.length });
  } catch (error: unknown) {
    res.status(400).json({ error: "Invalid request payload" });
  }
});

app.get("/api/logs", (req: Request, res: Response) => {
    res.json({ logs: appState.logs });
});

// Vite middleware for development
async function startServer() {
  await loadState();

  if (process.env.NODE_ENV !== "production") {
    // Bug #3: Dynamic import
    const { createServer: createViteServer } = await import("vite");
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: "spa",
    });
    app.use(vite.middlewares);
  } else {
    const distPath = path.join(process.cwd(), "dist");
    app.use(express.static(distPath));
    app.get("*", (req: Request, res: Response) => {
      res.sendFile(path.join(distPath, "index.html"));
    });
  }

  // Bug #17: Global error handler
  app.use((err: Error, req: Request, res: Response, next: NextFunction) => {
    console.error("Global Error:", err.message);
    res.status(500).json({ error: "Internal Server Error" });
  });

  app.listen(PORT, "0.0.0.0", () => {
    console.log(`Server running on port ${PORT}. API Token: ${API_TOKEN}`);
  });
}

// Bug #20: Catch start pattern
startServer().catch(err => {
  console.error("Failed to start server", err);
  process.exit(1);
});
