package com.example.emulator.gb

class GbApu {
    companion object {
        const val SAMPLE_RATE = 44100
        private const val CPU_CLOCK = 4194304.0
        private const val CYCLES_PER_SAMPLE = CPU_CLOCK / SAMPLE_RATE
        private val DUTY_TABLE = arrayOf(
            intArrayOf(0, 0, 0, 0, 0, 0, 0, 1),
            intArrayOf(1, 0, 0, 0, 0, 0, 0, 1),
            intArrayOf(1, 0, 0, 0, 0, 1, 1, 1),
            intArrayOf(0, 1, 1, 1, 1, 1, 1, 0)
        )
    }

    private val regs = IntArray(0x30)
    val waveRam = IntArray(16)

    private var powerOn = true
    private var nr50 = 0x77
    private var nr51 = 0xF3

    // Channel 1
    private var ch1Enabled = false
    private var ch1Freq = 0
    private var ch1Timer = 0
    private var ch1DutyStep = 0
    private var ch1Duty = 2
    private var ch1Vol = 0
    private var ch1EnvDir = 0
    private var ch1EnvPeriod = 0
    private var ch1EnvTimer = 0
    private var ch1Len = 0
    private var ch1LenEnable = false
    private var ch1SweepPeriod = 0
    private var ch1SweepTimer = 0
    private var ch1SweepNeg = false
    private var ch1SweepShift = 0

    // Channel 2
    private var ch2Enabled = false
    private var ch2Freq = 0
    private var ch2Timer = 0
    private var ch2DutyStep = 0
    private var ch2Duty = 2
    private var ch2Vol = 0
    private var ch2EnvDir = 0
    private var ch2EnvPeriod = 0
    private var ch2EnvTimer = 0
    private var ch2Len = 0
    private var ch2LenEnable = false

    // Channel 3 (Wave)
    private var ch3Enabled = false
    private var ch3DacOn = false
    private var ch3Freq = 0
    private var ch3Timer = 0
    private var ch3Pos = 0
    private var ch3VolShift = 0
    private var ch3Len = 0
    private var ch3LenEnable = false

    // Channel 4 (Noise)
    private var ch4Enabled = false
    private var ch4Timer = 0
    private var ch4Lfsr = 0x7FFF
    private var ch4Vol = 0
    private var ch4EnvDir = 0
    private var ch4EnvPeriod = 0
    private var ch4EnvTimer = 0
    private var ch4Len = 0
    private var ch4LenEnable = false
    private var ch4ShortMode = false
    private var ch4DivShift = 0
    private var ch4DivCode = 0

    private var frameSeqCycles = 0
    private var frameSeqStep = 0
    private var sampleAccum = 0.0

    fun reset() {
        regs.fill(0)
        waveRam.fill(0)
        powerOn = true
        nr50 = 0x77
        nr51 = 0xF3
        ch1Enabled = false
        ch2Enabled = false
        ch3Enabled = false
        ch4Enabled = false
        ch4Lfsr = 0x7FFF
        frameSeqCycles = 0
        frameSeqStep = 0
        sampleAccum = 0.0
    }

    fun readRegister(addr: Int): Int {
        if (addr in 0xFF30..0xFF3F) return waveRam[addr - 0xFF30]
        if (addr == 0xFF26) {
            var status = if (powerOn) 0x80 else 0x00
            if (ch1Enabled) status = status or 0x01
            if (ch2Enabled) status = status or 0x02
            if (ch3Enabled) status = status or 0x04
            if (ch4Enabled) status = status or 0x08
            return status or 0x70
        }
        val idx = addr - 0xFF10
        return if (idx in regs.indices) regs[idx] else 0xFF
    }

    fun writeRegister(addr: Int, value: Int) {
        val v = value and 0xFF
        if (addr in 0xFF30..0xFF3F) {
            waveRam[addr - 0xFF30] = v
            return
        }
        if (addr == 0xFF26) {
            powerOn = (v and 0x80) != 0
            if (!powerOn) {
                regs.fill(0)
                ch1Enabled = false
                ch2Enabled = false
                ch3Enabled = false
                ch4Enabled = false
            }
            return
        }
        if (!powerOn) return
        val idx = addr - 0xFF10
        if (idx in regs.indices) regs[idx] = v

        when (addr) {
            0xFF10 -> {
                ch1SweepPeriod = (v shr 4) and 0x07
                ch1SweepNeg = (v and 0x08) != 0
                ch1SweepShift = v and 0x07
            }
            0xFF11 -> {
                ch1Duty = (v shr 6) and 0x03
                ch1Len = 64 - (v and 0x3F)
            }
            0xFF12 -> {
                ch1Vol = (v shr 4) and 0x0F
                ch1EnvDir = if ((v and 0x08) != 0) 1 else -1
                ch1EnvPeriod = v and 0x07
                if ((v and 0xF8) == 0) ch1Enabled = false
            }
            0xFF13 -> ch1Freq = (ch1Freq and 0x700) or v
            0xFF14 -> {
                ch1Freq = (ch1Freq and 0xFF) or ((v and 0x07) shl 8)
                ch1LenEnable = (v and 0x40) != 0
                if ((v and 0x80) != 0) {
                    ch1Enabled = (regs[2] and 0xF8) != 0
                    if (ch1Len == 0) ch1Len = 64
                    ch1Vol = (regs[2] shr 4) and 0x0F
                    ch1EnvTimer = ch1EnvPeriod
                    ch1SweepTimer = if (ch1SweepPeriod == 0) 8 else ch1SweepPeriod
                    ch1Timer = (2048 - ch1Freq) * 4
                }
            }
            0xFF16 -> {
                ch2Duty = (v shr 6) and 0x03
                ch2Len = 64 - (v and 0x3F)
            }
            0xFF17 -> {
                ch2Vol = (v shr 4) and 0x0F
                ch2EnvDir = if ((v and 0x08) != 0) 1 else -1
                ch2EnvPeriod = v and 0x07
                if ((v and 0xF8) == 0) ch2Enabled = false
            }
            0xFF18 -> ch2Freq = (ch2Freq and 0x700) or v
            0xFF19 -> {
                ch2Freq = (ch2Freq and 0xFF) or ((v and 0x07) shl 8)
                ch2LenEnable = (v and 0x40) != 0
                if ((v and 0x80) != 0) {
                    ch2Enabled = (regs[7] and 0xF8) != 0
                    if (ch2Len == 0) ch2Len = 64
                    ch2Vol = (regs[7] shr 4) and 0x0F
                    ch2EnvTimer = ch2EnvPeriod
                    ch2Timer = (2048 - ch2Freq) * 4
                }
            }
            0xFF1A -> {
                ch3DacOn = (v and 0x80) != 0
                if (!ch3DacOn) ch3Enabled = false
            }
            0xFF1B -> ch3Len = 256 - v
            0xFF1C -> ch3VolShift = (v shr 5) and 0x03
            0xFF1D -> ch3Freq = (ch3Freq and 0x700) or v
            0xFF1E -> {
                ch3Freq = (ch3Freq and 0xFF) or ((v and 0x07) shl 8)
                ch3LenEnable = (v and 0x40) != 0
                if ((v and 0x80) != 0) {
                    ch3Enabled = ch3DacOn
                    if (ch3Len == 0) ch3Len = 256
                    ch3Pos = 0
                    ch3Timer = (2048 - ch3Freq) * 2
                }
            }
            0xFF20 -> ch4Len = 64 - (v and 0x3F)
            0xFF21 -> {
                ch4Vol = (v shr 4) and 0x0F
                ch4EnvDir = if ((v and 0x08) != 0) 1 else -1
                ch4EnvPeriod = v and 0x07
                if ((v and 0xF8) == 0) ch4Enabled = false
            }
            0xFF22 -> {
                ch4DivShift = (v shr 4) and 0x0F
                ch4ShortMode = (v and 0x08) != 0
                ch4DivCode = v and 0x07
            }
            0xFF23 -> {
                ch4LenEnable = (v and 0x40) != 0
                if ((v and 0x80) != 0) {
                    ch4Enabled = (regs[0x11] and 0xF8) != 0
                    if (ch4Len == 0) ch4Len = 64
                    ch4Vol = (regs[0x11] shr 4) and 0x0F
                    ch4EnvTimer = ch4EnvPeriod
                    ch4Lfsr = 0x7FFF
                    ch4Timer = noisePeriod()
                }
            }
            0xFF24 -> nr50 = v
            0xFF25 -> nr51 = v
        }
    }

    private fun noisePeriod(): Int {
        val base = if (ch4DivCode == 0) 8 else ch4DivCode * 16
        return base shl ch4DivShift
    }

    fun step(cycles: Int, outAudio: ShortArray?, outPos: Int): Int {
        if (!powerOn) {
            if (outAudio == null) return outPos
            sampleAccum += cycles
            var pos = outPos
            while (sampleAccum >= CYCLES_PER_SAMPLE) {
                sampleAccum -= CYCLES_PER_SAMPLE
                if (pos + 1 < outAudio.size) {
                    outAudio[pos++] = 0
                    outAudio[pos++] = 0
                }
            }
            return pos
        }

        // Frame sequencer at 512 Hz (every 8192 T-cycles)
        frameSeqCycles += cycles
        while (frameSeqCycles >= 8192) {
            frameSeqCycles -= 8192
            tickFrameSequencer()
        }

        if (ch1Enabled) {
            ch1Timer -= cycles
            while (ch1Timer <= 0) {
                ch1Timer += maxOf(4, (2048 - ch1Freq) * 4)
                ch1DutyStep = (ch1DutyStep + 1) and 7
            }
        }
        if (ch2Enabled) {
            ch2Timer -= cycles
            while (ch2Timer <= 0) {
                ch2Timer += maxOf(4, (2048 - ch2Freq) * 4)
                ch2DutyStep = (ch2DutyStep + 1) and 7
            }
        }
        if (ch3Enabled) {
            ch3Timer -= cycles
            while (ch3Timer <= 0) {
                ch3Timer += maxOf(2, (2048 - ch3Freq) * 2)
                ch3Pos = (ch3Pos + 1) and 31
            }
        }
        if (ch4Enabled) {
            ch4Timer -= cycles
            while (ch4Timer <= 0) {
                ch4Timer += maxOf(8, noisePeriod())
                val xorBit = (ch4Lfsr and 1) xor ((ch4Lfsr shr 1) and 1)
                ch4Lfsr = (ch4Lfsr shr 1) or (xorBit shl 14)
                if (ch4ShortMode) {
                    ch4Lfsr = (ch4Lfsr and (1 shl 6).inv()) or (xorBit shl 6)
                }
            }
        }

        sampleAccum += cycles
        var pos = outPos
        while (sampleAccum >= CYCLES_PER_SAMPLE) {
            sampleAccum -= CYCLES_PER_SAMPLE
            if (outAudio != null && pos + 1 < outAudio.size) {
                val s1 = if (ch1Enabled && DUTY_TABLE[ch1Duty][ch1DutyStep] == 1) ch1Vol else 0
                val s2 = if (ch2Enabled && DUTY_TABLE[ch2Duty][ch2DutyStep] == 1) ch2Vol else 0
                val s3 = if (ch3Enabled && ch3VolShift != 0) {
                    val b = waveRam[ch3Pos shr 1]
                    val nib = if ((ch3Pos and 1) == 0) (b shr 4) and 0xF else b and 0xF
                    nib shr (ch3VolShift - 1)
                } else 0
                val s4 = if (ch4Enabled && (ch4Lfsr and 1) == 0) ch4Vol else 0

                var left = 0
                var right = 0
                if ((nr51 and 0x10) != 0) left += s1
                if ((nr51 and 0x20) != 0) left += s2
                if ((nr51 and 0x40) != 0) left += s3
                if ((nr51 and 0x80) != 0) left += s4

                if ((nr51 and 0x01) != 0) right += s1
                if ((nr51 and 0x02) != 0) right += s2
                if ((nr51 and 0x04) != 0) right += s3
                if ((nr51 and 0x08) != 0) right += s4

                val leftVol = ((nr50 shr 4) and 0x07) + 1
                val rightVol = (nr50 and 0x07) + 1
                outAudio[pos++] = ((left * leftVol * 32) - 4096).coerceIn(-28000, 28000).toShort()
                outAudio[pos++] = ((right * rightVol * 32) - 4096).coerceIn(-28000, 28000).toShort()
            }
        }
        return pos
    }

    private fun tickFrameSequencer() {
        when (frameSeqStep) {
            0, 2, 4, 6 -> {
                if (ch1LenEnable && ch1Len > 0 && --ch1Len == 0) ch1Enabled = false
                if (ch2LenEnable && ch2Len > 0 && --ch2Len == 0) ch2Enabled = false
                if (ch3LenEnable && ch3Len > 0 && --ch3Len == 0) ch3Enabled = false
                if (ch4LenEnable && ch4Len > 0 && --ch4Len == 0) ch4Enabled = false
                if (frameSeqStep == 2 || frameSeqStep == 6) {
                    if (ch1SweepPeriod > 0 && --ch1SweepTimer <= 0) {
                        ch1SweepTimer = ch1SweepPeriod
                        val delta = ch1Freq shr ch1SweepShift
                        val next = if (ch1SweepNeg) ch1Freq - delta else ch1Freq + delta
                        if (next > 2047) ch1Enabled = false else if (ch1SweepShift > 0 && next >= 0) ch1Freq = next
                    }
                }
            }
            7 -> {
                if (ch1EnvPeriod > 0 && --ch1EnvTimer <= 0) {
                    ch1EnvTimer = ch1EnvPeriod
                    ch1Vol = (ch1Vol + ch1EnvDir).coerceIn(0, 15)
                }
                if (ch2EnvPeriod > 0 && --ch2EnvTimer <= 0) {
                    ch2EnvTimer = ch2EnvPeriod
                    ch2Vol = (ch2Vol + ch2EnvDir).coerceIn(0, 15)
                }
                if (ch4EnvPeriod > 0 && --ch4EnvTimer <= 0) {
                    ch4EnvTimer = ch4EnvPeriod
                    ch4Vol = (ch4Vol + ch4EnvDir).coerceIn(0, 15)
                }
            }
        }
        frameSeqStep = (frameSeqStep + 1) and 7
    }
}
