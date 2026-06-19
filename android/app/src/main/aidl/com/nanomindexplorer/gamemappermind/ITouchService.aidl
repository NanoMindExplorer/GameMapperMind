package com.nanomindexplorer.gamemappermind;

interface ITouchService {
    void touchDown(int pointerId, float x, float y);
    void touchMove(int pointerId, float x, float y);
    void touchUp(int pointerId);
    void injectTap(float x, float y);
    boolean isAlive();
}
