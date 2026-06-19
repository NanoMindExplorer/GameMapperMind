package com.nanomindexplorer.gamemappermind;

interface ITouchService {
    boolean touchDown(int pointerId, float x, float y);
    boolean touchMove(int pointerId, float x, float y);
    boolean touchUp(int pointerId);
    boolean injectTap(float x, float y);
    boolean isAlive();
}
