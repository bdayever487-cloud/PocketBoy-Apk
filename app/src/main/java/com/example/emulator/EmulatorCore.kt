package com.example.emulator

import com.example.data.model.RomSystem

object EmulatorButtons {
    const val A = 1 shl 0
    const val B = 1 shl 1
    const val SELECT = 1 shl 2
    const val START = 1 shl 3
    const val RIGHT = 1 shl 4
    const val LEFT = 1 shl 5
    const val UP = 1 shl 6
    const val DOWN = 1 shl 7
    const val R = 1 shl 8
    const val L = 1 shl 9
}

interface EmulatorCore {
    val system: RomSystem
    val screenWidth: Int
    val screenHeight: Int

    fun reset()

    /**
     * Advances emulation by one video frame (~59.7 Hz for GB/GBA),
     * writing ARGB8888 pixels into [framebuffer] and optional stereo PCM
     * samples into [audioBuffer]. Returns the number of stereo sample frames written
     * (or total shorts written = frames * 2).
     */
    fun stepFrame(framebuffer: IntArray, audioBuffer: ShortArray?): Int

    fun setInput(buttonMask: Int)

    fun exportBatteryRam(): ByteArray?
    fun importBatteryRam(data: ByteArray)
    fun isBatteryDirty(): Boolean
    fun clearBatteryDirty()

    fun saveState(): ByteArray
    fun loadState(data: ByteArray): Boolean
}
