package com.tabletgamepadbridge

data class GamepadState(
    val dpadUp: Boolean = false,
    val dpadDown: Boolean = false,
    val dpadLeft: Boolean = false,
    val dpadRight: Boolean = false,
    val start: Boolean = false,
    val back: Boolean = false,
    val leftStickClick: Boolean = false,
    val rightStickClick: Boolean = false,
    val leftBumper: Boolean = false,
    val rightBumper: Boolean = false,
    val guide: Boolean = false,
    val a: Boolean = false,
    val b: Boolean = false,
    val x: Boolean = false,
    val y: Boolean = false,
    val leftTrigger: Int = 0,
    val rightTrigger: Int = 0,
    val leftStickX: Int = 0,
    val leftStickY: Int = 0,
    val rightStickX: Int = 0,
    val rightStickY: Int = 0,
    val pollHz: Int = 250,
    val latencyMs: Long = 4,
    val batteryPercent: Int = 100
)
