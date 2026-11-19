package com.example.emulator.gb

import java.io.DataInputStream
import java.io.DataOutputStream

class GbCpu(private val core: GbCore) {
    private var a = 0x01
    private var f = 0xB0
    private var b = 0x00
    private var c = 0x13
    private var d = 0x00
    private var e = 0xD8
    private var h = 0x01
    private var l = 0x4D
    var sp = 0xFFFE
    var pc = 0x0100

    private var ime = false
    private var eiPending = false
    private var halted = false

    fun reset(isCgb: Boolean) {
        a = if (isCgb) 0x11 else 0x01
        f = 0xB0
        b = 0x00
        c = 0x13
        d = 0x00
        e = 0xD8
        h = 0x01
        l = 0x4D
        sp = 0xFFFE
        pc = 0x0100
        ime = false
        eiPending = false
        halted = false
    }

    private inline fun af(): Int = (a shl 8) or (f and 0xF0)
    private inline fun bc(): Int = (b shl 8) or c
    private inline fun de(): Int = (d shl 8) or e
    private inline fun hl(): Int = (h shl 8) or l

    private inline fun setBc(v: Int) { b = (v shr 8) and 0xFF; c = v and 0xFF }
    private inline fun setDe(v: Int) { d = (v shr 8) and 0xFF; e = v and 0xFF }
    private inline fun setHl(v: Int) { h = (v shr 8) and 0xFF; l = v and 0xFF }

    private inline fun flagZ(): Boolean = (f and 0x80) != 0
    private inline fun flagN(): Boolean = (f and 0x40) != 0
    private inline fun flagH(): Boolean = (f and 0x20) != 0
    private inline fun flagC(): Boolean = (f and 0x10) != 0

    private fun setFlags(z: Boolean, n: Boolean, hFlag: Boolean, cFlag: Boolean) {
        f = (if (z) 0x80 else 0) or (if (n) 0x40 else 0) or (if (hFlag) 0x20 else 0) or (if (cFlag) 0x10 else 0)
    }

    private fun fetch8(): Int {
        val v = core.read8(pc)
        pc = (pc + 1) and 0xFFFF
        return v
    }

    private fun fetch16(): Int {
        val lo = fetch8()
        val hi = fetch8()
        return lo or (hi shl 8)
    }

    private fun push16(v: Int) {
        sp = (sp - 1) and 0xFFFF
        core.write8(sp, (v shr 8) and 0xFF)
        sp = (sp - 1) and 0xFFFF
        core.write8(sp, v and 0xFF)
    }

    private fun pop16(): Int {
        val lo = core.read8(sp)
        sp = (sp + 1) and 0xFFFF
        val hi = core.read8(sp)
        sp = (sp + 1) and 0xFFFF
        return lo or (hi shl 8)
    }

    private fun getReg8(idx: Int): Int = when (idx) {
        0 -> b; 1 -> c; 2 -> d; 3 -> e; 4 -> h; 5 -> l; 6 -> core.read8(hl()); else -> a
    }

    private fun setReg8(idx: Int, v: Int) {
        val bVal = v and 0xFF
        when (idx) {
            0 -> b = bVal; 1 -> c = bVal; 2 -> d = bVal; 3 -> e = bVal
            4 -> h = bVal; 5 -> l = bVal; 6 -> core.write8(hl(), bVal); else -> a = bVal
        }
    }

    fun step(): Int {
        val pending = core.ieReg and core.ifReg and 0x1F
        if (pending != 0) {
            halted = false
            if (ime) {
                ime = false
                eiPending = false
                for (bit in 0 until 5) {
                    if ((pending and (1 shl bit)) != 0) {
                        core.ifReg = core.ifReg and (1 shl bit).inv()
                        push16(pc)
                        pc = 0x0040 + (bit * 8)
                        return 20
                    }
                }
            }
        }

        if (halted) return 4

        val wasEiPending = eiPending
        eiPending = false

        val op = fetch8()
        val cycles = execOpcode(op)

        if (wasEiPending) ime = true
        return cycles
    }

    private fun execOpcode(op: Int): Int = when (op) {
        0x00 -> 4 // NOP
        0x01 -> { setBc(fetch16()); 12 }
        0x02 -> { core.write8(bc(), a); 8 }
        0x03 -> { setBc((bc() + 1) and 0xFFFF); 8 }
        0x04 -> { b = inc8(b); 4 }
        0x05 -> { b = dec8(b); 4 }
        0x06 -> { b = fetch8(); 8 }
        0x07 -> { // RLCA
            val carry = (a shr 7) and 1
            a = ((a shl 1) or carry) and 0xFF
            setFlags(false, false, false, carry != 0)
            4
        }
        0x08 -> { // LD (a16), SP
            val addr = fetch16()
            core.write8(addr, sp and 0xFF)
            core.write8((addr + 1) and 0xFFFF, (sp shr 8) and 0xFF)
            20
        }
        0x09 -> { addHl(bc()); 8 }
        0x0A -> { a = core.read8(bc()); 8 }
        0x0B -> { setBc((bc() - 1) and 0xFFFF); 8 }
        0x0C -> { c = inc8(c); 4 }
        0x0D -> { c = dec8(c); 4 }
        0x0E -> { c = fetch8(); 8 }
        0x0F -> { // RRCA
            val carry = a and 1
            a = ((a shr 1) or (carry shl 7)) and 0xFF
            setFlags(false, false, false, carry != 0)
            4
        }
        0x10 -> { // STOP / CGB Speed Switch
            fetch8()
            if (core.isCgb && core.key1Prepare) {
                core.doubleSpeed = !core.doubleSpeed
                core.key1Prepare = false
            }
            4
        }
        0x11 -> { setDe(fetch16()); 12 }
        0x12 -> { core.write8(de(), a); 8 }
        0x13 -> { setDe((de() + 1) and 0xFFFF); 8 }
        0x14 -> { d = inc8(d); 4 }
        0x15 -> { d = dec8(d); 4 }
        0x16 -> { d = fetch8(); 8 }
        0x17 -> { // RLA
            val oldC = if (flagC()) 1 else 0
            val newC = (a and 0x80) != 0
            a = ((a shl 1) or oldC) and 0xFF
            setFlags(false, false, false, newC)
            4
        }
        0x18 -> { jr(true); 12 }
        0x19 -> { addHl(de()); 8 }
        0x1A -> { a = core.read8(de()); 8 }
        0x1B -> { setDe((de() - 1) and 0xFFFF); 8 }
        0x1C -> { e = inc8(e); 4 }
        0x1D -> { e = dec8(e); 4 }
        0x1E -> { e = fetch8(); 8 }
        0x1F -> { // RRA
            val oldC = if (flagC()) 0x80 else 0
            val newC = (a and 1) != 0
            a = (a shr 1) or oldC
            setFlags(false, false, false, newC)
            4
        }
        0x20 -> if (jr(!flagZ())) 12 else 8
        0x21 -> { setHl(fetch16()); 12 }
        0x22 -> { val addr = hl(); core.write8(addr, a); setHl((addr + 1) and 0xFFFF); 8 }
        0x23 -> { setHl((hl() + 1) and 0xFFFF); 8 }
        0x24 -> { h = inc8(h); 4 }
        0x25 -> { h = dec8(h); 4 }
        0x26 -> { h = fetch8(); 8 }
        0x27 -> { daa(); 4 }
        0x28 -> if (jr(flagZ())) 12 else 8
        0x29 -> { addHl(hl()); 8 }
        0x2A -> { val addr = hl(); a = core.read8(addr); setHl((addr + 1) and 0xFFFF); 8 }
        0x2B -> { setHl((hl() - 1) and 0xFFFF); 8 }
        0x2C -> { l = inc8(l); 4 }
        0x2D -> { l = dec8(l); 4 }
        0x2E -> { l = fetch8(); 8 }
        0x2F -> { // CPL
            a = a xor 0xFF
            setFlags(flagZ(), true, true, flagC())
            4
        }
        0x30 -> if (jr(!flagC())) 12 else 8
        0x31 -> { sp = fetch16(); 12 }
        0x32 -> { val addr = hl(); core.write8(addr, a); setHl((addr - 1) and 0xFFFF); 8 }
        0x33 -> { sp = (sp + 1) and 0xFFFF; 8 }
        0x34 -> { val addr = hl(); core.write8(addr, inc8(core.read8(addr))); 12 }
        0x35 -> { val addr = hl(); core.write8(addr, dec8(core.read8(addr))); 12 }
        0x36 -> { core.write8(hl(), fetch8()); 12 }
        0x37 -> { setFlags(flagZ(), false, false, true); 4 } // SCF
        0x38 -> if (jr(flagC())) 12 else 8
        0x39 -> { addHl(sp); 8 }
        0x3A -> { val addr = hl(); a = core.read8(addr); setHl((addr - 1) and 0xFFFF); 8 }
        0x3B -> { sp = (sp - 1) and 0xFFFF; 8 }
        0x3C -> { a = inc8(a); 4 }
        0x3D -> { a = dec8(a); 4 }
        0x3E -> { a = fetch8(); 8 }
        0x3F -> { setFlags(flagZ(), false, false, !flagC()); 4 } // CCF

        in 0x40..0x7F -> {
            if (op == 0x76) {
                halted = true
                4
            } else {
                val dst = (op shr 3) and 7
                val src = op and 7
                setReg8(dst, getReg8(src))
                if (dst == 6 || src == 6) 8 else 4
            }
        }
        in 0x80..0xBF -> {
            val src = op and 7
            val v = getReg8(src)
            when ((op shr 3) and 7) {
                0 -> addA(v, false)
                1 -> addA(v, true)
                2 -> subA(v, false)
                3 -> subA(v, true)
                4 -> andA(v)
                5 -> xorA(v)
                6 -> orA(v)
                7 -> cpA(v)
            }
            if (src == 6) 8 else 4
        }

        0xC0 -> if (!flagZ()) { pc = pop16(); 20 } else 8
        0xC1 -> { setBc(pop16()); 12 }
        0xC2 -> { val addr = fetch16(); if (!flagZ()) { pc = addr; 16 } else 12 }
        0xC3 -> { pc = fetch16(); 16 }
        0xC4 -> { val addr = fetch16(); if (!flagZ()) { push16(pc); pc = addr; 24 } else 12 }
        0xC5 -> { push16(bc()); 16 }
        0xC6 -> { addA(fetch8(), false); 8 }
        0xC7 -> { push16(pc); pc = 0x00; 16 }
        0xC8 -> if (flagZ()) { pc = pop16(); 20 } else 8
        0xC9 -> { pc = pop16(); 16 }
        0xCA -> { val addr = fetch16(); if (flagZ()) { pc = addr; 16 } else 12 }
        0xCB -> execCb()
        0xCC -> { val addr = fetch16(); if (flagZ()) { push16(pc); pc = addr; 24 } else 12 }
        0xCD -> { val addr = fetch16(); push16(pc); pc = addr; 24 }
        0xCE -> { addA(fetch8(), true); 8 }
        0xCF -> { push16(pc); pc = 0x08; 16 }

        0xD0 -> if (!flagC()) { pc = pop16(); 20 } else 8
        0xD1 -> { setDe(pop16()); 12 }
        0xD2 -> { val addr = fetch16(); if (!flagC()) { pc = addr; 16 } else 12 }
        0xD4 -> { val addr = fetch16(); if (!flagC()) { push16(pc); pc = addr; 24 } else 12 }
        0xD5 -> { push16(de()); 16 }
        0xD6 -> { subA(fetch8(), false); 8 }
        0xD7 -> { push16(pc); pc = 0x10; 16 }
        0xD8 -> if (flagC()) { pc = pop16(); 20 } else 8
        0xD9 -> { pc = pop16(); ime = true; 16 } // RETI
        0xDA -> { val addr = fetch16(); if (flagC()) { pc = addr; 16 } else 12 }
        0xDC -> { val addr = fetch16(); if (flagC()) { push16(pc); pc = addr; 24 } else 12 }
        0xDE -> { subA(fetch8(), true); 8 }
        0xDF -> { push16(pc); pc = 0x18; 16 }

        0xE0 -> { core.write8(0xFF00 + fetch8(), a); 12 }
        0xE1 -> { setHl(pop16()); 12 }
        0xE2 -> { core.write8(0xFF00 + c, a); 8 }
        0xE5 -> { push16(hl()); 16 }
        0xE6 -> { andA(fetch8()); 8 }
        0xE7 -> { push16(pc); pc = 0x20; 16 }
        0xE8 -> { sp = addSpSigned(fetch8().toByte().toInt()); 16 }
        0xE9 -> { pc = hl(); 4 }
        0xEA -> { core.write8(fetch16(), a); 16 }
        0xEE -> { xorA(fetch8()); 8 }
        0xEF -> { push16(pc); pc = 0x28; 16 }

        0xF0 -> { a = core.read8(0xFF00 + fetch8()); 12 }
        0xF1 -> { val afv = pop16(); a = (afv shr 8) and 0xFF; f = afv and 0xF0; 12 }
        0xF2 -> { a = core.read8(0xFF00 + c); 8 }
        0xF3 -> { ime = false; eiPending = false; 4 } // DI
        0xF5 -> { push16(af()); 16 }
        0xF6 -> { orA(fetch8()); 8 }
        0xF7 -> { push16(pc); pc = 0x30; 16 }
        0xF8 -> { setHl(addSpSigned(fetch8().toByte().toInt())); 12 }
        0xF9 -> { sp = hl(); 8 }
        0xFA -> { a = core.read8(fetch16()); 16 }
        0xFB -> { eiPending = true; 4 } // EI
        0xFE -> { cpA(fetch8()); 8 }
        0xFF -> { push16(pc); pc = 0x38; 16 }
        else -> 4
    }

    private fun execCb(): Int {
        val cb = fetch8()
        val r = cb and 7
        val v = getReg8(r)
        val group = (cb shr 6) and 3
        val bit = (cb shr 3) and 7

        when (group) {
            0 -> {
                val res = when (bit) {
                    0 -> { val c = (v shr 7) and 1; val n = ((v shl 1) or c) and 0xFF; setFlags(n == 0, false, false, c != 0); n }
                    1 -> { val c = v and 1; val n = ((v shr 1) or (c shl 7)) and 0xFF; setFlags(n == 0, false, false, c != 0); n }
                    2 -> { val oldC = if (flagC()) 1 else 0; val newC = (v and 0x80) != 0; val n = ((v shl 1) or oldC) and 0xFF; setFlags(n == 0, false, false, newC); n }
                    3 -> { val oldC = if (flagC()) 0x80 else 0; val newC = (v and 1) != 0; val n = (v shr 1) or oldC; setFlags(n == 0, false, false, newC); n }
                    4 -> { val c = (v and 0x80) != 0; val n = (v shl 1) and 0xFF; setFlags(n == 0, false, false, c); n }
                    5 -> { val c = (v and 1) != 0; val n = (v shr 1) or (v and 0x80); setFlags(n == 0, false, false, c); n }
                    6 -> { val n = ((v and 0x0F) shl 4) or ((v shr 4) and 0x0F); setFlags(n == 0, false, false, false); n }
                    else -> { val c = (v and 1) != 0; val n = (v shr 1) and 0x7F; setFlags(n == 0, false, false, c); n }
                }
                setReg8(r, res)
                return if (r == 6) 16 else 8
            }
            1 -> { // BIT
                setFlags((v and (1 shl bit)) == 0, false, true, flagC())
                return if (r == 6) 12 else 8
            }
            2 -> { // RES
                setReg8(r, v and (1 shl bit).inv())
                return if (r == 6) 16 else 8
            }
            else -> { // SET
                setReg8(r, v or (1 shl bit))
                return if (r == 6) 16 else 8
            }
        }
    }

    private fun jr(cond: Boolean): Boolean {
        val offset = fetch8().toByte().toInt()
        if (cond) pc = (pc + offset) and 0xFFFF
        return cond
    }

    private fun inc8(v: Int): Int {
        val res = (v + 1) and 0xFF
        setFlags(res == 0, false, (v and 0x0F) + 1 > 0x0F, flagC())
        return res
    }

    private fun dec8(v: Int): Int {
        val res = (v - 1) and 0xFF
        setFlags(res == 0, true, (v and 0x0F) == 0, flagC())
        return res
    }

    private fun addA(v: Int, withCarry: Boolean) {
        val cIn = if (withCarry && flagC()) 1 else 0
        val sum = a + v + cIn
        val res = sum and 0xFF
        setFlags(res == 0, false, (a and 0x0F) + (v and 0x0F) + cIn > 0x0F, sum > 0xFF)
        a = res
    }

    private fun subA(v: Int, withCarry: Boolean) {
        val cIn = if (withCarry && flagC()) 1 else 0
        val diff = a - v - cIn
        val res = diff and 0xFF
        setFlags(res == 0, true, (a and 0x0F) - (v and 0x0F) - cIn < 0, diff < 0)
        a = res
    }

    private fun cpA(v: Int) {
        val diff = a - v
        setFlags((diff and 0xFF) == 0, true, (a and 0x0F) < (v and 0x0F), diff < 0)
    }

    private fun andA(v: Int) { a = a and v; setFlags(a == 0, false, true, false) }
    private fun xorA(v: Int) { a = a xor v; setFlags(a == 0, false, false, false) }
    private fun orA(v: Int) { a = a or v; setFlags(a == 0, false, false, false) }

    private fun addHl(v: Int) {
        val cur = hl()
        val sum = cur + v
        setFlags(flagZ(), false, (cur and 0x0FFF) + (v and 0x0FFF) > 0x0FFF, sum > 0xFFFF)
        setHl(sum and 0xFFFF)
    }

    private fun addSpSigned(offset: Int): Int {
        val sum = (sp + offset) and 0xFFFF
        val hFlag = ((sp and 0x0F) + (offset and 0x0F)) > 0x0F
        val cFlag = ((sp and 0xFF) + (offset and 0xFF)) > 0xFF
        setFlags(false, false, hFlag, cFlag)
        return sum
    }

    private fun daa() {
        var adjust = 0
        var cFlag = flagC()
        if (flagH() || (!flagN() && (a and 0x0F) > 9)) adjust = adjust or 0x06
        if (cFlag || (!flagN() && a > 0x99)) { adjust = adjust or 0x60; cFlag = true }
        a = (if (flagN()) a - adjust else a + adjust) and 0xFF
        setFlags(a == 0, flagN(), false, cFlag)
    }

    fun saveState(out: DataOutputStream) {
        out.writeInt(a); out.writeInt(f); out.writeInt(b); out.writeInt(c)
        out.writeInt(d); out.writeInt(e); out.writeInt(h); out.writeInt(l)
        out.writeInt(sp); out.writeInt(pc)
        out.writeBoolean(ime); out.writeBoolean(eiPending); out.writeBoolean(halted)
    }

    fun loadState(inp: DataInputStream) {
        a = inp.readInt(); f = inp.readInt(); b = inp.readInt(); c = inp.readInt()
        d = inp.readInt(); e = inp.readInt(); h = inp.readInt(); l = inp.readInt()
        sp = inp.readInt(); pc = inp.readInt()
        ime = inp.readBoolean(); eiPending = inp.readBoolean(); halted = inp.readBoolean()
    }
}
