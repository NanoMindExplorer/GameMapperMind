package com.nanomindexplorer.gamemappermind;

interface ITouchService {
    void injectTap(int x, int y);
    void injectSwipe(int x1, int y1, int x2, int y2, int duration);
    void touchDown(int x, int y, int pointerId);
    void touchMove(int x, int y, int pointerId);
    void touchUp(int pointerId);
    boolean isAlive();
}
