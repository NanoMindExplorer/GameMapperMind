package com.nanomindexplorer.gamemappermind;

interface ICommandOutputListener {
    void onOutputLine(String line);
    void onExit(int code);
}
