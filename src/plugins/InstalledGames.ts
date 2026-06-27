import { registerPlugin } from '@capacitor/core';

export interface InstalledGame {
  packageName: string;
  name: string;
  iconBase64: string;
  isGame?: boolean;
}

export interface InstalledGamesPluginType {
  listInstalledGames(): Promise<{ games: InstalledGame[]; count: number }>;
  listAllUserApps(): Promise<{ apps: InstalledGame[]; count: number }>;
  launchApp(options: { packageName: string }): Promise<void>;
}

const InstalledGames = registerPlugin<InstalledGamesPluginType>('InstalledGames');

export default InstalledGames;
