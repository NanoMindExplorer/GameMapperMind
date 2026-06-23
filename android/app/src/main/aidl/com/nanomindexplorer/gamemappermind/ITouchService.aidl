package com.nanomindexplorer.gamemappermind;

import com.nanomindexplorer.gamemappermind.ICommandOutputListener;

interface ITouchService {
    boolean touchDown(int pointerId, float x, float y) = 1;
    boolean touchMove(int pointerId, float x, float y) = 2;
    boolean touchUp(int pointerId) = 3;
    boolean injectTap(float x, float y, long duration) = 4;
    boolean isAlive() = 5;
    boolean releaseAllPointers() = 6;
    String executeShellCommand(String command) = 7;
    void executeStreamCommand(String command, ICommandOutputListener listener) = 8;
    void stopStreamCommand() = 9;
    void updateConfig(String json) = 10;
    void destroy() = 16777114;
}
