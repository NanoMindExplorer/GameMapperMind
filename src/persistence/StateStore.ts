import fs from "fs";
import path from "path";

// BUG-04: Persistence Layer dengan JSON File Storage (Mandat 04)
const DATA_FILE = path.join(process.cwd(), "app_data.json");

interface PersistedState {
    logs: string[];
    macros: any[]; // will be strictly typed later
    apiToken: string | null;
}

const defaultState: PersistedState = {
    logs: [],
    macros: [],
    apiToken: null
};

export class StateStore {
    private static state: PersistedState = { ...defaultState };

    public static async load(): Promise<void> {
        try {
            if (fs.existsSync(DATA_FILE)) {
                const data = await fs.promises.readFile(DATA_FILE, 'utf-8');
                this.state = JSON.parse(data);
            }
        } catch (error) {
            console.error("Failed to load state from JSON, using defaults:", error);
            this.state = { ...defaultState };
        }
    }

    public static async save(): Promise<void> {
        try {
            await fs.promises.writeFile(DATA_FILE, JSON.stringify(this.state, null, 2), 'utf-8');
        } catch (error) {
            console.error("Failed to save state to JSON:", error);
        }
    }

    public static getState(): PersistedState {
        return this.state;
    }
}
