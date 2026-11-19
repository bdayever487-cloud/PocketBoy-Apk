package com.example.emulator.gba

class GbaApu {
    companion object {
        const val SAMPLE_RATE = 44100
        private const val CPU_CLOCK = 16777216.0
        private const val CYCLES_PER_SAMPLE = CPU_CLOCK / SAMPLE_RATE
    }

    private val fifoA = ByteArray(32)
    private var fifoAHead = 0
    private var fifoATail = 0
    private var fifoACount = 0
    private var currentSampleA = 0

    private val fifoB = ByteArray(32)
    private var fifoBHead = 0
    private var fifoBTail = 0
    private var fifoBCount = 0
    private var currentSampleB = 0

    var soundCntH = 0
    var soundCntX = 0
    private var sampleAccum = 0.0

    fun reset() {
        fifoA.fill(0)
        fifoB.fill(0)
        fifoAHead = 0; fifoATail = 0; fifoACount = 0; currentSampleA = 0
        fifoBHead = 0; fifoBTail = 0; fifoBCount = 0; currentSampleB = 0
        soundCntH = 0
        soundCntX = 0x80
        sampleAccum = 0.0
    }

    fun pushFifoA(word: Int) {
        for (i in 0 until 4) {
            if (fifoACount < 32) {
                fifoA[fifoATail] = ((word shr (i * 8)) and 0xFF).toByte()
                fifoATail = (fifoATail + 1) and 31
                fifoACount++
            }
        }
    }

    fun pushFifoB(word: Int) {
        for (i in 0 until 4) {
            if (fifoBCount < 32) {
                fifoB[fifoBTail] = ((word shr (i * 8)) and 0xFF).toByte()
                fifoBTail = (fifoBTail + 1) and 31
                fifoBCount++
            }
        }
    }

    fun writeSoundCntH(value: Int) {
        soundCntH = value and 0x770F
        if ((value and 0x0800) != 0) { fifoAHead = 0; fifoATail = 0; fifoACount = 0 }
        if ((value and 0x8000) != 0) { fifoBHead = 0; fifoBTail = 0; fifoBCount = 0 }
    }

    fun onTimerOverflow(timerIdx: Int, mem: GbaMemory) {
        if ((soundCntX and 0x80) == 0) return
        val timerA = if ((soundCntH and 0x0400) != 0) 1 else 0
        val timerB = if ((soundCntH and 0x4000) != 0) 1 else 0

        if (timerIdx == timerA) {
            if (fifoACount > 0) {
                currentSampleA = fifoA[fifoAHead].toInt()
                fifoAHead = (fifoAHead + 1) and 31
                fifoACount--
            }
            if (fifoACount <= 16) mem.triggerSoundDma(1)
        }
        if (timerIdx == timerB) {
            if (fifoBCount > 0) {
                currentSampleB = fifoB[fifoBHead].toInt()
                fifoBHead = (fifoBHead + 1) and 31
                fifoBCount--
            }
            if (fifoBCount <= 16) mem.triggerSoundDma(2)
        }
    }

    fun step(cycles: Int, outAudio: ShortArray?, outPos: Int): Int {
        sampleAccum += cycles
        var pos = outPos
        while (sampleAccum >= CYCLES_PER_SAMPLE) {
            sampleAccum -= CYCLES_PER_SAMPLE
            if (outAudio != null && pos + 1 < outAudio.size) {
                if ((soundCntX and 0x80) == 0) {
                    outAudio[pos++] = 0
                    outAudio[pos++] = 0
                } else {
                    val volA = if ((soundCntH and 0x0004) != 0) 2 else 1
                    val volB = if ((soundCntH and 0x0008) != 0) 2 else 1
                    val sA = currentSampleA * volA * 96
                    val sB = currentSampleB * volB * 96
                    var left = 0
                    var right = 0
                    if ((soundCntH and 0x0200) != 0) left += sA
                    if ((soundCntH and 0x0100) != 0) right += sA
                    if ((soundCntH and 0x2000) != 0) left += sB
                    if ((soundCntH and 0x1000) != 0) right += sB
                    outAudio[pos++] = left.coerceIn(-30000, 30000).toShort()
                    outAudio[pos++] = right.coerceIn(-30000, 30000).toShort()
                }
            }
        }
        return pos
    }
}
