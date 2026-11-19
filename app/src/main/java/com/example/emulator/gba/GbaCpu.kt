package com.example.emulator.gba

import java.io.DataInputStream
import java.io.DataOutputStream

class GbaCpu(private val mem: GbaMemory, private val bios: GbaBiosHle) {
    companion object {
        const val MODE_USR = 0x10
        const val MODE_FIQ = 0x11
        const val MODE_IRQ = 0x12
        const val MODE_SVC = 0x13
        const val MODE_ABT = 0x17
        const val MODE_UND = 0x1B
        const val MODE_SYS = 0x1F

        const val FLAG_N = 1 shl 31
        const val FLAG_Z = 1 shl 30
        const val FLAG_C = 1 shl 29
        const val FLAG_V = 1 shl 28
        const val FLAG_I = 1 shl 7
        const val FLAG_F = 1 shl 6
        const val FLAG_T = 1 shl 5
    }

    val r = IntArray(16)
    var cpsr = MODE_SYS

    // Banked registers: 0=USR/SYS, 1=FIQ, 2=SVC, 3=ABT, 4=IRQ, 5=UND
    private val bankedSp = IntArray(6)
    private val bankedLr = IntArray(6)
    private val bankedSpsr = IntArray(6)
    private val fiqR8to12 = IntArray(5)
    private val usrR8to12 = IntArray(5)

    var branched = false
    var shifterCarry = false

    fun reset() {
        r.fill(0)
        bankedSp.fill(0)
        bankedLr.fill(0)
        bankedSpsr.fill(0)
        fiqR8to12.fill(0)
        usrR8to12.fill(0)
        cpsr = MODE_SYS
        // Standard GBA post-BIOS entry state: jump straight to ROM header entry point
        r[13] = 0x03007F00
        bankedSp[0] = 0x03007F00
        bankedSp[2] = 0x03007FE0 // SVC SP
        bankedSp[4] = 0x03007FA0 // IRQ SP
        r[15] = 0x08000000
        branched = false
    }

    fun softReset(bootToEwram: Boolean) {
        r.fill(0)
        cpsr = MODE_SYS
        r[13] = 0x03007F00
        bankedSp[0] = 0x03007F00
        bankedSp[2] = 0x03007FE0
        bankedSp[4] = 0x03007FA0
        r[15] = if (bootToEwram) 0x02000000 else 0x08000000
        branched = true
    }

    fun rewindAfterSwi() {
        r[15] -= if ((cpsr and FLAG_T) != 0) 4 else 8
        branched = true
    }

    private fun modeIndex(mode: Int): Int = when (mode and 0x1F) {
        MODE_FIQ -> 1
        MODE_SVC -> 2
        MODE_ABT -> 3
        MODE_IRQ -> 4
        MODE_UND -> 5
        else -> 0
    }

    fun switchMode(newMode: Int) {
        val oldIdx = modeIndex(cpsr)
        val newIdx = modeIndex(newMode)
        if (oldIdx != newIdx) {
            bankedSp[oldIdx] = r[13]
            bankedLr[oldIdx] = r[14]
            if (oldIdx == 1) {
                for (i in 0..4) fiqR8to12[i] = r[8 + i]
                for (i in 0..4) r[8 + i] = usrR8to12[i]
            } else if (newIdx == 1) {
                for (i in 0..4) usrR8to12[i] = r[8 + i]
                for (i in 0..4) r[8 + i] = fiqR8to12[i]
            }
            r[13] = bankedSp[newIdx]
            r[14] = bankedLr[newIdx]
        }
        cpsr = (cpsr and 0xFFFFFFE0.toInt()) or (newMode and 0x1F)
    }

    fun getSpsr(): Int = bankedSpsr[modeIndex(cpsr)]
    fun setSpsr(v: Int) {
        val idx = modeIndex(cpsr)
        if (idx != 0) bankedSpsr[idx] = v
    }

    fun restoreCpsrFromSpsr() {
        val spsr = getSpsr()
        if (modeIndex(cpsr) != 0) {
            val targetMode = spsr and 0x1F
            switchMode(targetMode)
            cpsr = spsr
        }
    }

    fun checkCondition(cond: Int): Boolean {
        val n = (cpsr and FLAG_N) != 0
        val z = (cpsr and FLAG_Z) != 0
        val c = (cpsr and FLAG_C) != 0
        val v = (cpsr and FLAG_V) != 0
        return when (cond) {
            0 -> z
            1 -> !z
            2 -> c
            3 -> !c
            4 -> n
            5 -> !n
            6 -> v
            7 -> !v
            8 -> c && !z
            9 -> !c || z
            10 -> n == v
            11 -> n != v
            12 -> !z && (n == v)
            13 -> z || (n != v)
            14 -> true
            else -> false
        }
    }

    fun triggerIrq() {
        if ((cpsr and FLAG_I) != 0) return
        val returnAddr = r[15] + 4
        val oldCpsr = cpsr
        switchMode(MODE_IRQ)
        bankedSpsr[4] = oldCpsr
        cpsr = (cpsr and FLAG_T.inv()) or FLAG_I
        r[14] = returnAddr
        r[15] = 0x00000018
    }

    fun triggerSwi(swiNum: Int) {
        // Use fast HLE BIOS execution directly while preserving ABI registers
        bios.executeSwi(swiNum, this, mem)
    }

    fun step(): Int {
        if (mem.ime != 0 && (mem.ie and mem.ifReg) != 0 && (cpsr and FLAG_I) == 0) {
            mem.halted = false
            triggerIrq()
            return 4
        }
        if (mem.halted) return 4

        return if ((cpsr and FLAG_T) != 0) {
            val instrAddr = r[15] and -2
            val op = mem.read16(instrAddr)
            r[15] = instrAddr + 4
            branched = false
            GbaThumbDecoder.exec(op, this, mem)
            if (!branched) r[15] = instrAddr + 2
            2
        } else {
            val instrAddr = r[15] and -4
            val op = mem.read32(instrAddr)
            r[15] = instrAddr + 8
            branched = false
            if (checkCondition((op ushr 28) and 0xF)) {
                execArm(op)
            }
            if (!branched) r[15] = instrAddr + 4
            2
        }
    }

    private fun execArm(op: Int) {
        // Branch and Exchange (BX)
        if ((op and 0x0FFFFFF0) == 0x012FFF10) {
            val target = r[op and 0xF]
            if ((target and 1) != 0) {
                cpsr = cpsr or FLAG_T
                r[15] = target and -2
            } else {
                cpsr = cpsr and FLAG_T.inv()
                r[15] = target and -4
            }
            branched = true
            return
        }

        val opBits27_25 = (op ushr 25) and 0x7
        when (opBits27_25) {
            0 -> {
                val bit4 = (op and 0x10) != 0
                val bit7 = (op and 0x80) != 0
                if (bit4 && bit7) {
                    if ((op and 0x0FB00FF0) == 0x01000090) {
                        execArmSwap(op)
                    } else if ((op and 0x0FC000F0) == 0x00000090) {
                        execArmMul(op)
                    } else if ((op and 0x0F8000F0) == 0x00800090) {
                        execArmMulLong(op)
                    } else {
                        execArmHalfwordTransfer(op)
                    }
                } else {
                    execArmDataProc(op, isImm = false)
                }
            }
            1 -> execArmDataProc(op, isImm = true)
            2, 3 -> execArmSingleTransfer(op, isRegOffset = (opBits27_25 == 3))
            4 -> execArmBlockTransfer(op)
            5 -> execArmBranch(op)
            7 -> {
                if ((op and 0x0F000000) == 0x0F000000) {
                    triggerSwi((op ushr 16) and 0xFF)
                }
            }
        }
    }

    private fun execArmBranch(op: Int) {
        val link = (op and (1 shl 24)) != 0
        if (link) r[14] = (r[15] - 4) and -4
        val imm24 = ((op and 0x00FFFFFF) shl 8) shr 6
        r[15] = r[15] + imm24
        branched = true
    }

    private fun execArmDataProc(op: Int, isImm: Boolean) {
        val opcode = (op ushr 21) and 0xF
        val sBit = (op and (1 shl 20)) != 0
        val rn = (op ushr 16) and 0xF
        val rd = (op ushr 12) and 0xF

        // MRS / MSR
        if (!sBit && opcode in 8..11) {
            if ((op and 0x0FBF0FFF) == 0x010F0000) { // MRS
                r[rd] = if ((op and (1 shl 22)) != 0) getSpsr() else cpsr
                return
            } else if ((op and 0x0DB0F000) == 0x0120F000) { // MSR
                val value = if (isImm) {
                    val imm = op and 0xFF
                    val rot = ((op ushr 8) and 0xF) * 2
                    Integer.rotateRight(imm, rot)
                } else {
                    r[op and 0xF]
                }
                var mask = 0
                if ((op and (1 shl 16)) != 0) mask = mask or 0x000000FF
                if ((op and (1 shl 17)) != 0) mask = mask or 0x0000FF00
                if ((op and (1 shl 18)) != 0) mask = mask or 0x00FF0000
                if ((op and (1 shl 19)) != 0) mask = mask or 0xFF000000.toInt()
                if ((cpsr and 0x1F) == MODE_USR) mask = mask and 0xFF000000.toInt()

                if ((op and (1 shl 22)) != 0) {
                    setSpsr((getSpsr() and mask.inv()) or (value and mask))
                } else {
                    val newCpsr = (cpsr and mask.inv()) or (value and mask)
                    if ((mask and 0x1F) != 0) switchMode(newCpsr and 0x1F)
                    cpsr = newCpsr
                }
                return
            }
        }

        shifterCarry = (cpsr and FLAG_C) != 0
        val op2 = if (isImm) {
            val imm = op and 0xFF
            val rot = ((op ushr 8) and 0xF) * 2
            val res = Integer.rotateRight(imm, rot)
            if (rot != 0) shifterCarry = (res ushr 31) != 0
            res
        } else {
            val rm = r[op and 0xF]
            val shiftType = (op ushr 5) and 0x3
            val byReg = (op and 0x10) != 0
            val shiftAmt = if (byReg) (r[(op ushr 8) and 0xF] and 0xFF) else ((op ushr 7) and 0x1F)
            val baseVal = if (byReg && (op and 0xF) == 15) rm + 4 else rm
            barrelShift(baseVal, shiftType, shiftAmt, !byReg)
        }

        val rnVal = if (!isImm && (op and 0x10) != 0 && rn == 15) r[15] + 4 else r[rn]
        var writeRd = true
        var result = 0
        val oldC = if ((cpsr and FLAG_C) != 0) 1 else 0

        when (opcode) {
            0 -> result = rnVal and op2
            1 -> result = rnVal xor op2
            2 -> { result = subWithFlags(rnVal, op2, sBit); writeRd = true }
            3 -> { result = subWithFlags(op2, rnVal, sBit); writeRd = true }
            4 -> { result = addWithFlags(rnVal, op2, sBit); writeRd = true }
            5 -> { result = adcWithFlags(rnVal, op2, oldC, sBit); writeRd = true }
            6 -> { result = sbcWithFlags(rnVal, op2, oldC, sBit); writeRd = true }
            7 -> { result = sbcWithFlags(op2, rnVal, oldC, sBit); writeRd = true }
            8 -> { result = rnVal and op2; writeRd = false }
            9 -> { result = rnVal xor op2; writeRd = false }
            10 -> { subWithFlags(rnVal, op2, true); writeRd = false; return }
            11 -> { addWithFlags(rnVal, op2, true); writeRd = false; return }
            12 -> result = rnVal or op2
            13 -> result = op2
            14 -> result = rnVal and op2.inv()
            15 -> result = op2.inv()
        }

        if (sBit && opcode !in listOf(2, 3, 4, 5, 6, 7, 10, 11)) {
            setNzFlags(result)
            if (shifterCarry) cpsr = cpsr or FLAG_C else cpsr = cpsr and FLAG_C.inv()
        }

        if (writeRd) {
            r[rd] = result
            if (rd == 15) {
                if (sBit) restoreCpsrFromSpsr()
                branched = true
            }
        }
    }

    fun barrelShift(value: Int, shiftType: Int, amount: Int, isImmShift: Boolean): Int {
        if (amount == 0 && isImmShift) {
            return when (shiftType) {
                0 -> value
                1 -> { shifterCarry = (value ushr 31) != 0; 0 }
                2 -> { shifterCarry = (value ushr 31) != 0; if (shifterCarry) -1 else 0 }
                else -> {
                    val oldC = if ((cpsr and FLAG_C) != 0) 1 else 0
                    shifterCarry = (value and 1) != 0
                    (value ushr 1) or (oldC shl 31)
                }
            }
        }
        if (amount == 0) return value
        return when (shiftType) {
            0 -> {
                if (amount < 32) {
                    shifterCarry = ((value ushr (32 - amount)) and 1) != 0
                    value shl amount
                } else if (amount == 32) {
                    shifterCarry = (value and 1) != 0; 0
                } else {
                    shifterCarry = false; 0
                }
            }
            1 -> {
                if (amount < 32) {
                    shifterCarry = ((value ushr (amount - 1)) and 1) != 0
                    value ushr amount
                } else if (amount == 32) {
                    shifterCarry = (value ushr 31) != 0; 0
                } else {
                    shifterCarry = false; 0
                }
            }
            2 -> {
                if (amount < 32) {
                    shifterCarry = ((value shr (amount - 1)) and 1) != 0
                    value shr amount
                } else {
                    shifterCarry = value < 0
                    if (value < 0) -1 else 0
                }
            }
            else -> {
                val rot = amount and 31
                if (rot == 0) {
                    shifterCarry = (value ushr 31) != 0
                    value
                } else {
                    val res = Integer.rotateRight(value, rot)
                    shifterCarry = (res ushr 31) != 0
                    res
                }
            }
        }
    }

    fun setNzFlags(res: Int) {
        cpsr = cpsr and (FLAG_N or FLAG_Z).inv()
        if (res < 0) cpsr = cpsr or FLAG_N
        if (res == 0) cpsr = cpsr or FLAG_Z
    }

    fun addWithFlags(a: Int, b: Int, updateFlags: Boolean): Int {
        val sum64 = (a.toLong() and 0xFFFFFFFFL) + (b.toLong() and 0xFFFFFFFFL)
        val res = sum64.toInt()
        if (updateFlags) {
            setNzFlags(res)
            if (sum64 > 0xFFFFFFFFL) cpsr = cpsr or FLAG_C else cpsr = cpsr and FLAG_C.inv()
            val overflow = ((a xor b) >= 0) && ((a xor res) < 0)
            if (overflow) cpsr = cpsr or FLAG_V else cpsr = cpsr and FLAG_V.inv()
        }
        return res
    }

    fun adcWithFlags(a: Int, b: Int, c: Int, updateFlags: Boolean): Int {
        val sum64 = (a.toLong() and 0xFFFFFFFFL) + (b.toLong() and 0xFFFFFFFFL) + c.toLong()
        val res = sum64.toInt()
        if (updateFlags) {
            setNzFlags(res)
            if (sum64 > 0xFFFFFFFFL) cpsr = cpsr or FLAG_C else cpsr = cpsr and FLAG_C.inv()
            val overflow = ((a xor b) >= 0) && ((a xor res) < 0)
            if (overflow) cpsr = cpsr or FLAG_V else cpsr = cpsr and FLAG_V.inv()
        }
        return res
    }

    fun subWithFlags(a: Int, b: Int, updateFlags: Boolean): Int {
        val res = a - b
        if (updateFlags) {
            setNzFlags(res)
            val borrow = (a.toLong() and 0xFFFFFFFFL) >= (b.toLong() and 0xFFFFFFFFL)
            if (borrow) cpsr = cpsr or FLAG_C else cpsr = cpsr and FLAG_C.inv()
            val overflow = ((a xor b) < 0) && ((a xor res) < 0)
            if (overflow) cpsr = cpsr or FLAG_V else cpsr = cpsr and FLAG_V.inv()
        }
        return res
    }

    fun sbcWithFlags(a: Int, b: Int, c: Int, updateFlags: Boolean): Int {
        val ua = a.toLong() and 0xFFFFFFFFL
        val ub = (b.toLong() and 0xFFFFFFFFL) + (1 - c).toLong()
        val res = (ua - ub).toInt()
        if (updateFlags) {
            setNzFlags(res)
            if (ua >= ub) cpsr = cpsr or FLAG_C else cpsr = cpsr and FLAG_C.inv()
            val overflow = ((a xor b) < 0) && ((a xor res) < 0)
            if (overflow) cpsr = cpsr or FLAG_V else cpsr = cpsr and FLAG_V.inv()
        }
        return res
    }

    private fun execArmMul(op: Int) {
        val acc = (op and (1 shl 21)) != 0
        val sBit = (op and (1 shl 20)) != 0
        val rd = (op ushr 16) and 0xF
        val rn = (op ushr 12) and 0xF
        val rs = (op ushr 8) and 0xF
        val rm = op and 0xF
        var res = r[rm] * r[rs]
        if (acc) res += r[rn]
        r[rd] = res
        if (sBit) setNzFlags(res)
    }

    private fun execArmMulLong(op: Int) {
        val signed = (op and (1 shl 22)) != 0
        val acc = (op and (1 shl 21)) != 0
        val sBit = (op and (1 shl 20)) != 0
        val rdHi = (op ushr 16) and 0xF
        val rdLo = (op ushr 12) and 0xF
        val rs = (op ushr 8) and 0xF
        val rm = op and 0xF
        var prod = if (signed) r[rm].toLong() * r[rs].toLong() else (r[rm].toLong() and 0xFFFFFFFFL) * (r[rs].toLong() and 0xFFFFFFFFL)
        if (acc) {
            val existing = ((r[rdHi].toLong() and 0xFFFFFFFFL) shl 32) or (r[rdLo].toLong() and 0xFFFFFFFFL)
            prod += existing
        }
        r[rdLo] = prod.toInt()
        r[rdHi] = (prod ushr 32).toInt()
        if (sBit) {
            cpsr = cpsr and (FLAG_N or FLAG_Z).inv()
            if (prod < 0 && signed || (r[rdHi] < 0)) cpsr = cpsr or FLAG_N
            if (prod == 0L) cpsr = cpsr or FLAG_Z
        }
    }

    private fun execArmSwap(op: Int) {
        val isByte = (op and (1 shl 22)) != 0
        val rn = (op ushr 16) and 0xF
        val rd = (op ushr 12) and 0xF
        val rm = op and 0xF
        val addr = r[rn]
        if (isByte) {
            val old = mem.read8(addr)
            mem.write8(addr, r[rm])
            r[rd] = old
        } else {
            val old = Integer.rotateRight(mem.read32(addr), (addr and 3) * 8)
            mem.write32(addr, r[rm])
            r[rd] = old
        }
    }

    private fun execArmSingleTransfer(op: Int, isRegOffset: Boolean) {
        val pre = (op and (1 shl 24)) != 0
        val up = (op and (1 shl 23)) != 0
        val isByte = (op and (1 shl 22)) != 0
        val writeBack = (op and (1 shl 21)) != 0
        val isLoad = (op and (1 shl 20)) != 0
        val rn = (op ushr 16) and 0xF
        val rd = (op ushr 12) and 0xF

        val offset = if (isRegOffset) {
            val rm = r[op and 0xF]
            val shiftType = (op ushr 5) and 0x3
            val shiftAmt = (op ushr 7) and 0x1F
            barrelShift(rm, shiftType, shiftAmt, true)
        } else {
            op and 0xFFF
        }

        val base = r[rn]
        val indexed = if (up) base + offset else base - offset
        val addr = if (pre) indexed else base

        if (!pre || writeBack) {
            if (rn != rd || !isLoad) r[rn] = indexed
        }

        if (isLoad) {
            val value = if (isByte) {
                mem.read8(addr)
            } else {
                Integer.rotateRight(mem.read32(addr), (addr and 3) * 8)
            }
            r[rd] = value
            if (rd == 15) {
                r[15] = value and -4
                branched = true
            }
        } else {
            val valToStore = if (rd == 15) r[15] + 4 else r[rd]
            if (isByte) mem.write8(addr, valToStore) else mem.write32(addr, valToStore)
        }
    }

    private fun execArmHalfwordTransfer(op: Int) {
        val pre = (op and (1 shl 24)) != 0
        val up = (op and (1 shl 23)) != 0
        val immFlag = (op and (1 shl 22)) != 0
        val writeBack = (op and (1 shl 21)) != 0
        val isLoad = (op and (1 shl 20)) != 0
        val rn = (op ushr 16) and 0xF
        val rd = (op ushr 12) and 0xF
        val sh = (op ushr 5) and 0x3

        val offset = if (immFlag) ((op and 0xF00) ushr 4) or (op and 0xF) else r[op and 0xF]
        val base = r[rn]
        val indexed = if (up) base + offset else base - offset
        val addr = if (pre) indexed else base

        if (!pre || writeBack) {
            if (rn != rd || !isLoad) r[rn] = indexed
        }

        if (isLoad) {
            r[rd] = when (sh) {
                1 -> mem.read16(addr)
                2 -> mem.read8(addr).toByte().toInt()
                3 -> if ((addr and 1) != 0) mem.read8(addr).toByte().toInt() else mem.read16(addr).toShort().toInt()
                else -> 0
            }
            if (rd == 15) branched = true
        } else {
            val v = if (rd == 15) r[15] + 4 else r[rd]
            if (sh == 1) mem.write16(addr, v)
        }
    }

    private fun execArmBlockTransfer(op: Int) {
        val pre = (op and (1 shl 24)) != 0
        val up = (op and (1 shl 23)) != 0
        val sBit = (op and (1 shl 22)) != 0
        val writeBack = (op and (1 shl 21)) != 0
        val isLoad = (op and (1 shl 20)) != 0
        val rn = (op ushr 16) and 0xF
        val regList = op and 0xFFFF
        val count = Integer.bitCount(regList)

        val base = r[rn]
        var addr = if (up) {
            if (pre) base + 4 else base
        } else {
            if (pre) base - count * 4 else base - count * 4 + 4
        }

        if (writeBack && (!isLoad || (regList and (1 shl rn)) == 0)) {
            r[rn] = if (up) base + count * 4 else base - count * 4
        }

        val oldMode = cpsr and 0x1F
        val userBankTransfer = sBit && (!isLoad || (regList and (1 shl 15)) == 0)
        if (userBankTransfer) switchMode(MODE_USR)

        for (i in 0..15) {
            if ((regList and (1 shl i)) != 0) {
                if (isLoad) {
                    val v = mem.read32(addr)
                    r[i] = v
                    if (i == 15) {
                        if (sBit) restoreCpsrFromSpsr()
                        r[15] = if ((cpsr and FLAG_T) != 0) v and -2 else v and -4
                        branched = true
                    }
                } else {
                    val v = if (i == 15) r[15] + 4 else r[i]
                    mem.write32(addr, v)
                }
                addr += 4
            }
        }

        if (userBankTransfer) switchMode(oldMode)
    }

    fun saveState(out: DataOutputStream) {
        for (v in r) out.writeInt(v)
        out.writeInt(cpsr)
        for (i in 0..5) { out.writeInt(bankedSp[i]); out.writeInt(bankedLr[i]); out.writeInt(bankedSpsr[i]) }
        for (i in 0..4) { out.writeInt(fiqR8to12[i]); out.writeInt(usrR8to12[i]) }
    }

    fun loadState(inp: DataInputStream) {
        for (i in 0..15) r[i] = inp.readInt()
        cpsr = inp.readInt()
        for (i in 0..5) { bankedSp[i] = inp.readInt(); bankedLr[i] = inp.readInt(); bankedSpsr[i] = inp.readInt() }
        for (i in 0..4) { fiqR8to12[i] = inp.readInt(); usrR8to12[i] = inp.readInt() }
    }
}
