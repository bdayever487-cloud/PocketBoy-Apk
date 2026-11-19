package com.example.emulator.gba

import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class GbaBiosHle {
    val biosRom = ByteArray(0x4000)

    init {
        buildBiosTrampoline()
    }

    private fun writeWord(offset: Int, word: Int) {
        biosRom[offset] = (word and 0xFF).toByte()
        biosRom[offset + 1] = ((word shr 8) and 0xFF).toByte()
        biosRom[offset + 2] = ((word shr 16) and 0xFF).toByte()
        biosRom[offset + 3] = ((word shr 24) and 0xFF).toByte()
    }

    private fun buildBiosTrampoline() {
        // Exception vectors:
        // 0x00 Reset: b 0x00E0 (NOP loop / boot)
        writeWord(0x00, 0xEA000036.toInt())
        // 0x18 IRQ vector: b 0x0128
        writeWord(0x18, 0xEA000042.toInt())

        // Standard Nintendo GBA BIOS IRQ wrapper at 0x0128:
        // 0x0128: stmdb sp!, {r0-r3, r12, lr}
        writeWord(0x128, 0xE92D500F.toInt())
        // 0x012C: mov r0, #0x04000000
        writeWord(0x12C, 0xE3A00301.toInt())
        // 0x0130: add lr, pc, #0 (sets lr = 0x0138)
        writeWord(0x130, 0xE28FE000.toInt())
        // 0x0134: ldr pc, [r0, #-4]  (reads 0x03FFFFFC -> mirror of 0x03007FFC user IRQ handler!)
        writeWord(0x134, 0xE510F004.toInt())
        // 0x0138: ldmia sp!, {r0-r3, r12, lr}
        writeWord(0x138, 0xE8BD500F.toInt())
        // 0x013C: subs pc, lr, #4
        writeWord(0x13C, 0xE25EF004.toInt())
    }

    fun executeSwi(swiNum: Int, cpu: GbaCpu, mem: GbaMemory) {
        when (swiNum and 0xFF) {
            0x00 -> { // SoftReset
                val flag = mem.read8(0x03007FFA)
                for (a in 0x03007E00 until 0x03008000 step 4) mem.write32(a, 0)
                cpu.softReset(bootToEwram = flag != 0)
            }
            0x01 -> { // RegisterRamReset
                val flags = cpu.r[0]
                if ((flags and 0x01) != 0) mem.ewram.fill(0)
                if ((flags and 0x02) != 0) mem.iwram.fill(0)
                if ((flags and 0x04) != 0) mem.palette.fill(0)
                if ((flags and 0x08) != 0) mem.vram.fill(0)
                if ((flags and 0x10) != 0) mem.oam.fill(0)
            }
            0x02 -> { // Halt
                mem.halted = true
            }
            0x03 -> { // Stop
                mem.halted = true
            }
            0x04 -> { // IntrWait
                val discardOld = cpu.r[0] != 0
                val irqMask = cpu.r[1] and 0x3FFF
                if (discardOld) {
                    val ack = mem.read16(0x03007FF8) and irqMask.inv()
                    mem.write16(0x03007FF8, ack)
                }
                mem.ime = 1
                val biosIf = mem.read16(0x03007FF8)
                if ((biosIf and irqMask) != 0) {
                    mem.write16(0x03007FF8, biosIf and irqMask.inv())
                } else {
                    mem.intrWaitMask = irqMask
                    mem.halted = true
                    cpu.rewindAfterSwi()
                }
            }
            0x05 -> { // VBlankIntrWait
                mem.ime = 1
                val biosIf = mem.read16(0x03007FF8)
                if ((biosIf and 0x0001) != 0) {
                    mem.write16(0x03007FF8, biosIf and 0xFFFE)
                } else {
                    mem.intrWaitMask = 0x0001
                    mem.halted = true
                    cpu.rewindAfterSwi()
                }
            }
            0x06 -> { // Div
                val num = cpu.r[0]
                val den = cpu.r[1]
                if (den != 0) {
                    val q = num / den
                    val rem = num % den
                    cpu.r[0] = q
                    cpu.r[1] = rem
                    cpu.r[3] = kotlin.math.abs(q)
                }
            }
            0x07 -> { // DivArm
                val den = cpu.r[0]
                val num = cpu.r[1]
                if (den != 0) {
                    val q = num / den
                    val rem = num % den
                    cpu.r[0] = q
                    cpu.r[1] = rem
                    cpu.r[3] = kotlin.math.abs(q)
                }
            }
            0x08 -> { // Sqrt
                val v = cpu.r[0].toLong() and 0xFFFFFFFFL
                cpu.r[0] = sqrt(v.toDouble()).toInt()
            }
            0x09 -> { // ArcTan
                val tanVal = cpu.r[0].toShort().toDouble() / 16384.0
                val rad = atan(tanVal)
                cpu.r[0] = ((rad / (Math.PI / 2.0)) * 0x4000).toInt()
            }
            0x0A -> { // ArcTan2
                val x = cpu.r[0].toShort().toDouble()
                val y = cpu.r[1].toShort().toDouble()
                var rad = atan2(y, x)
                if (rad < 0) rad += 2.0 * Math.PI
                cpu.r[0] = ((rad / (2.0 * Math.PI)) * 0x10000).toInt() and 0xFFFF
            }
            0x0B -> { // CpuSet
                val src = cpu.r[0]
                val dst = cpu.r[1]
                val mode = cpu.r[2]
                val count = mode and 0x1FFFFF
                val fixed = (mode and (1 shl 24)) != 0
                val is32 = (mode and (1 shl 26)) != 0
                if (is32) {
                    val fillVal = mem.read32(src)
                    for (i in 0 until count) {
                        val v = if (fixed) fillVal else mem.read32(src + i * 4)
                        mem.write32(dst + i * 4, v)
                    }
                } else {
                    val fillVal = mem.read16(src)
                    for (i in 0 until count) {
                        val v = if (fixed) fillVal else mem.read16(src + i * 2)
                        mem.write16(dst + i * 2, v)
                    }
                }
            }
            0x0C -> { // CpuFastSet
                val src = cpu.r[0] and -4
                val dst = cpu.r[1] and -4
                val mode = cpu.r[2]
                val count = ((mode and 0x1FFFFF) + 7) and -8
                val fixed = (mode and (1 shl 24)) != 0
                val fillVal = mem.read32(src)
                for (i in 0 until count) {
                    val v = if (fixed) fillVal else mem.read32(src + i * 4)
                    mem.write32(dst + i * 4, v)
                }
            }
            0x0E -> { // BgAffineSet
                var src = cpu.r[0]
                var dst = cpu.r[1]
                val count = cpu.r[2]
                for (i in 0 until count) {
                    val origX = mem.read32(src) / 256.0
                    val origY = mem.read32(src + 4) / 256.0
                    val dispX = mem.read16(src + 8).toShort().toDouble()
                    val dispY = mem.read16(src + 10).toShort().toDouble()
                    val sx = mem.read16(src + 12).toShort().toDouble() / 256.0
                    val sy = mem.read16(src + 14).toShort().toDouble() / 256.0
                    val angle = (mem.read16(src + 16) / 65536.0) * 2.0 * Math.PI
                    src += 20

                    val c = cos(angle)
                    val s = sin(angle)
                    val pa = (c * sx * 256.0).toInt()
                    val pb = (-s * sx * 256.0).toInt()
                    val pc = (s * sy * 256.0).toInt()
                    val pd = (c * sy * 256.0).toInt()
                    val startX = ((origX - (pa * dispX + pb * dispY) / 256.0) * 256.0).toInt()
                    val startY = ((origY - (pc * dispX + pd * dispY) / 256.0) * 256.0).toInt()

                    mem.write16(dst, pa); mem.write16(dst + 2, pb)
                    mem.write16(dst + 4, pc); mem.write16(dst + 6, pd)
                    mem.write32(dst + 8, startX); mem.write32(dst + 12, startY)
                    dst += 16
                }
            }
            0x0F -> { // ObjAffineSet
                var src = cpu.r[0]
                var dst = cpu.r[1]
                val count = cpu.r[2]
                val offset = cpu.r[3]
                for (i in 0 until count) {
                    val sx = mem.read16(src).toShort().toDouble() / 256.0
                    val sy = mem.read16(src + 2).toShort().toDouble() / 256.0
                    val angle = (mem.read16(src + 4) / 65536.0) * 2.0 * Math.PI
                    src += 8
                    val c = cos(angle)
                    val s = sin(angle)
                    mem.write16(dst, (c * sx * 256.0).toInt())
                    mem.write16(dst + offset, (-s * sx * 256.0).toInt())
                    mem.write16(dst + offset * 2, (s * sy * 256.0).toInt())
                    mem.write16(dst + offset * 3, (c * sy * 256.0).toInt())
                    dst += offset * 4
                }
            }
            0x11, 0x12 -> { // LZ77UnCompWram / Vram
                decompressLz77(cpu.r[0], cpu.r[1], mem, is16Bit = (swiNum and 0xFF) == 0x12)
            }
            0x13, 0x14 -> { // RLUnCompWram / Vram
                decompressRl(cpu.r[0], cpu.r[1], mem, is16Bit = (swiNum and 0xFF) == 0x14)
            }
            0x19 -> { // SoundBias
                // No-op in HLE audio
            }
        }
    }

    private fun decompressLz77(srcAddr: Int, dstAddr: Int, mem: GbaMemory, is16Bit: Boolean) {
        val header = mem.read32(srcAddr)
        var size = (header ushr 8) and 0xFFFFFF
        if (size <= 0) return
        val outBuf = ByteArray(size)
        var src = srcAddr + 4
        var outPos = 0

        while (outPos < size) {
            val flags = mem.read8(src++)
            for (bit in 7 downTo 0) {
                if (outPos >= size) break
                if ((flags and (1 shl bit)) == 0) {
                    outBuf[outPos++] = mem.read8(src++).toByte()
                } else {
                    val b1 = mem.read8(src++)
                    val b2 = mem.read8(src++)
                    val len = ((b1 shr 4) and 0xF) + 3
                    val disp = (((b1 and 0xF) shl 8) or b2) + 1
                    for (k in 0 until len) {
                        if (outPos >= size) break
                        val srcIdx = outPos - disp
                        outBuf[outPos++] = if (srcIdx >= 0) outBuf[srcIdx] else 0
                    }
                }
            }
        }

        if (is16Bit) {
            for (i in 0 until size step 2) {
                val lo = outBuf[i].toInt() and 0xFF
                val hi = if (i + 1 < size) outBuf[i + 1].toInt() and 0xFF else 0
                mem.write16(dstAddr + i, lo or (hi shl 8))
            }
        } else {
            for (i in 0 until size) {
                mem.write8(dstAddr + i, outBuf[i].toInt() and 0xFF)
            }
        }
    }

    private fun decompressRl(srcAddr: Int, dstAddr: Int, mem: GbaMemory, is16Bit: Boolean) {
        val header = mem.read32(srcAddr)
        val size = (header ushr 8) and 0xFFFFFF
        if (size <= 0) return
        val outBuf = ByteArray(size)
        var src = srcAddr + 4
        var outPos = 0

        while (outPos < size) {
            val flag = mem.read8(src++)
            if ((flag and 0x80) == 0) {
                val len = (flag and 0x7F) + 1
                for (i in 0 until len) {
                    if (outPos >= size) break
                    outBuf[outPos++] = mem.read8(src++).toByte()
                }
            } else {
                val len = (flag and 0x7F) + 3
                val b = mem.read8(src++).toByte()
                for (i in 0 until len) {
                    if (outPos >= size) break
                    outBuf[outPos++] = b
                }
            }
        }

        if (is16Bit) {
            for (i in 0 until size step 2) {
                val lo = outBuf[i].toInt() and 0xFF
                val hi = if (i + 1 < size) outBuf[i + 1].toInt() and 0xFF else 0
                mem.write16(dstAddr + i, lo or (hi shl 8))
            }
        } else {
            for (i in 0 until size) {
                mem.write8(dstAddr + i, outBuf[i].toInt() and 0xFF)
            }
        }
    }
}
