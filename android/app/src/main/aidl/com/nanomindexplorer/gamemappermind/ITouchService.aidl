package com.nanomindexplorer.gamemappermind;

import com.nanomindexplorer.gamemappermind.ICommandOutputListener;

interface ITouchService {
    boolean touchDown(int pointerId, float x, float y);
    boolean touchMove(int pointerId, float x, float y);
    boolean touchUp(int pointerId);
    boolean injectTap(float x, float y);
    boolean isAlive();
    boolean releaseAllPointers();
    String executeShellCommand(String command);
    void executeStreamCommand(String command, ICommandOutputListener listener);
    void stopStreamCommand();
    void destroy() = 16777114;
}
