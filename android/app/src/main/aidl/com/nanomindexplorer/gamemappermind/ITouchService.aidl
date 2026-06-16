package com.nanomindexplorer.gamemappermind;

interface ITouchService {
    void touchDown(int pointerId, float x, float y);
    void touchMove(int pointerId, float x, float y);
    void touchUp(int pointerId);
    void injectTap(float x, float y);
    boolean isAlive();

    // Anti-ban configuration — pushed from JS whenever the active
    // profile changes (or antiBanEnabled is toggled).
    void setAntiBanConfig(
        boolean enabled,
        float coordinateJitter,
        int timingJitter,
        float pressureVariance,
        float sizeVariance,
        int strokeDurationJitter,
        float microPauseProbability,
        int microPauseMaxMs
    );
}
