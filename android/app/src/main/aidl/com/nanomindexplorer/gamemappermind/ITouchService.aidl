package com.nanomindexplorer.gamemappermind;

interface ITouchService {
    void destroy() = 16777114;
    void touchDown(int pointerId, float x, float y) = 1;
    void touchMove(int pointerId, float x, float y) = 2;
    void touchUp(int pointerId) = 3;
    void injectTap(float x, float y) = 4;
    boolean isAlive() = 5;
    void setAntiBanConfig(boolean enabled, float coordinateJitter, int timingJitter, float pressureVariance, float sizeVariance, int strokeDurationJitter, float microPauseProbability, int microPauseMaxMs) = 6;
    boolean startEvdevCapture() = 7;
    boolean stopEvdevCapture() = 8;
}
