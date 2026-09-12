package com.tabletgamepadbridge;

interface IUhidGamepadService {
    boolean createGamepad() = 1;
    void sendInput(in byte[] data) = 2;
    void destroy() = 16777114;
}
