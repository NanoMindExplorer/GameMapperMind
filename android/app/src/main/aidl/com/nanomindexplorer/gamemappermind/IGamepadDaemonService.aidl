package com.nanomindexplorer.gamemappermind;

interface IGamepadDaemonService {
    void injectTouchEvent(int action, int pointerId, float x, float y);
    void destroy();
}
