package com.example.emulator.gba

object GbaThumbDecoder {
    fun exec(op: Int, cpu: GbaCpu, mem: GbaMemory) {
        val top3 = (op ushr 13) and 0x7
        when (top3) {
            0 -> { // Move shifted register or Add/Sub
                val sub = (op ushr 11) and 0x3
                if (sub == 3) {
                    val isImm = (op and (1 shl 10)) != 0
                    val isSub = (op and (1 shl 9)) != 0
                    val rnOrImm = (op ushr 6) and 0x7
                    val rs = (op ushr 3) and 0x7
                    val rd = op and 0x7
                    val b = if (isImm) rnOrImm else cpu.r[rnOrImm]
                    cpu.r[rd] = if (isSub) cpu.subWithFlags(cpu.r[rs], b, true) else cpu.addWithFlags(cpu.r[rs], b, true)
                } else {
                    val offset = (op ushr 6) and 0x1F
                    val rs = (op ushr 3) and 0x7
                    val rd = op and 0x7
                    cpu.shifterCarry = (cpu.cpsr and GbaCpu.FLAG_C) != 0
                    val res = cpu.barrelShift(cpu.r[rs], sub, offset, true)
                    cpu.r[rd] = res
                    cpu.setNzFlags(res)
                    if (cpu.shifterCarry) cpu.cpsr = cpu.cpsr or GbaCpu.FLAG_C else cpu.cpsr = cpu.cpsr and GbaCpu.FLAG_C.inv()
                }
            }
            1 -> { // MOV/CMP/ADD/SUB immediate
                val sub = (op ushr 11) and 0x3
                val rd = (op ushr 8) and 0x7
                val imm = op and 0xFF
                when (sub) {
                    0 -> { cpu.r[rd] = imm; cpu.setNzFlags(imm) }
                    1 -> cpu.subWithFlags(cpu.r[rd], imm, true)
                    2 -> cpu.r[rd] = cpu.addWithFlags(cpu.r[rd], imm, true)
                    3 -> cpu.r[rd] = cpu.subWithFlags(cpu.r[rd], imm, true)
                }
            }
            2 -> {
                val sub = (op ushr 10) and 0x7
                when {
                    sub == 0 -> execThumbAlu(op, cpu)
                    sub == 1 -> execThumbHiRegBx(op, cpu)
                    (op and 0xF800) == 0x4800 -> { // PC-relative load
                        val rd = (op ushr 8) and 0x7
                        val imm = (op and 0xFF) shl 2
                        val addr = (cpu.r[15] and -4) + imm
                        cpu.r[rd] = mem.read32(addr)
                    }
                    else -> { // Load/Store with register offset
                        val ro = (op ushr 6) and 0x7
                        val rb = (op ushr 3) and 0x7
                        val rd = op and 0x7
                        val addr = cpu.r[rb] + cpu.r[ro]
                        val type = (op ushr 9) and 0x7
                        when (type) {
                            0 -> mem.write32(addr, cpu.r[rd])
                            1 -> mem.write16(addr, cpu.r[rd])
                            2 -> mem.write8(addr, cpu.r[rd])
                            3 -> cpu.r[rd] = mem.read8(addr).toByte().toInt()
                            4 -> cpu.r[rd] = Integer.rotateRight(mem.read32(addr), (addr and 3) * 8)
                            5 -> cpu.r[rd] = mem.read16(addr)
                            6 -> cpu.r[rd] = mem.read8(addr)
                            7 -> cpu.r[rd] = if ((addr and 1) != 0) mem.read8(addr).toByte().toInt() else mem.read16(addr).toShort().toInt()
                        }
                    }
                }
            }
            3 -> { // Load/Store with immediate offset (word/byte)
                val isByte = (op and (1 shl 12)) != 0
                val isLoad = (op and (1 shl 11)) != 0
                val offset = ((op ushr 6) and 0x1F) shl (if (isByte) 0 else 2)
                val rb = (op ushr 3) and 0x7
                val rd = op and 0x7
                val addr = cpu.r[rb] + offset
                if (isLoad) {
                    cpu.r[rd] = if (isByte) mem.read8(addr) else Integer.rotateRight(mem.read32(addr), (addr and 3) * 8)
                } else {
                    if (isByte) mem.write8(addr, cpu.r[rd]) else mem.write32(addr, cpu.r[rd])
                }
            }
            4 -> {
                if ((op and (1 shl 12)) != 0) { // SP-relative load/store
                    val isLoad = (op and (1 shl 11)) != 0
                    val rd = (op ushr 8) and 0x7
                    val addr = cpu.r[13] + ((op and 0xFF) shl 2)
                    if (isLoad) cpu.r[rd] = Integer.rotateRight(mem.read32(addr), (addr and 3) * 8)
                    else mem.write32(addr, cpu.r[rd])
                } else { // Load/Store halfword
                    val isLoad = (op and (1 shl 11)) != 0
                    val offset = ((op ushr 6) and 0x1F) shl 1
                    val rb = (op ushr 3) and 0x7
                    val rd = op and 0x7
                    val addr = cpu.r[rb] + offset
                    if (isLoad) cpu.r[rd] = mem.read16(addr) else mem.write16(addr, cpu.r[rd])
                }
            }
            5 -> {
                if ((op and (1 shl 12)) == 0) { // Load address (ADD Rd, PC/SP, #imm)
                    val useSp = (op and (1 shl 11)) != 0
                    val rd = (op ushr 8) and 0x7
                    val imm = (op and 0xFF) shl 2
                    cpu.r[rd] = (if (useSp) cpu.r[13] else (cpu.r[15] and -4)) + imm
                } else {
                    val sub = (op ushr 8) and 0xF
                    when {
                        sub == 0 -> { // Add offset to SP
                            val imm = (op and 0x7F) shl 2
                            cpu.r[13] = if ((op and 0x80) != 0) cpu.r[13] - imm else cpu.r[13] + imm
                        }
                        (sub and 0x6) == 0x4 -> { // PUSH / POP
                            val isPop = (op and (1 shl 11)) != 0
                            val pclr = (op and (1 shl 8)) != 0
                            val rlist = op and 0xFF
                            if (isPop) {
                                var sp = cpu.r[13]
                                for (i in 0..7) {
                                    if ((rlist and (1 shl i)) != 0) {
                                        cpu.r[i] = mem.read32(sp); sp += 4
                                    }
                                }
                                if (pclr) {
                                    val target = mem.read32(sp); sp += 4
                                    cpu.r[15] = target and -2
                                    cpu.branched = true
                                }
                                cpu.r[13] = sp
                            } else {
                                var sp = cpu.r[13]
                                if (pclr) { sp -= 4; mem.write32(sp, cpu.r[14]) }
                                for (i in 7 downTo 0) {
                                    if ((rlist and (1 shl i)) != 0) {
                                        sp -= 4; mem.write32(sp, cpu.r[i])
                                    }
                                }
                                cpu.r[13] = sp
                            }
                        }
                    }
                }
            }
            6 -> {
                if ((op and (1 shl 12)) == 0) { // LDMIA / STMIA
                    val isLoad = (op and (1 shl 11)) != 0
                    val rb = (op ushr 8) and 0x7
                    val rlist = op and 0xFF
                    var addr = cpu.r[rb]
                    for (i in 0..7) {
                        if ((rlist and (1 shl i)) != 0) {
                            if (isLoad) cpu.r[i] = mem.read32(addr) else mem.write32(addr, cpu.r[i])
                            addr += 4
                        }
                    }
                    if (!isLoad || (rlist and (1 shl rb)) == 0) cpu.r[rb] = addr
                } else {
                    val cond = (op ushr 8) and 0xF
                    if (cond == 0xF) { // SWI
                        cpu.triggerSwi(op and 0xFF)
                    } else if (cond < 14 && cpu.checkCondition(cond)) { // Conditional branch
                        val off = ((op and 0xFF).toByte().toInt()) shl 1
                        cpu.r[15] = cpu.r[15] + off
                        cpu.branched = true
                    }
                }
            }
            7 -> {
                val sub = (op ushr 11) and 0x3
                when (sub) {
                    0 -> { // Unconditional branch
                        val off = (((op and 0x7FF) shl 21) shr 20)
                        cpu.r[15] = cpu.r[15] + off
                        cpu.branched = true
                    }
                    2 -> { // BL prefix (high)
                        val off = (((op and 0x7FF) shl 21) shr 9)
                        cpu.r[14] = cpu.r[15] + off
                    }
                    3 -> { // BL suffix (low)
                        val nextPc = (cpu.r[15] - 2) or 1
                        cpu.r[15] = cpu.r[14] + ((op and 0x7FF) shl 1)
                        cpu.r[14] = nextPc
                        cpu.branched = true
                    }
                }
            }
        }
    }

    private fun execThumbAlu(op: Int, cpu: GbaCpu) {
        val aluOp = (op ushr 6) and 0xF
        val rs = (op ushr 3) and 0x7
        val rd = op and 0x7
        val a = cpu.r[rd]
        val b = cpu.r[rs]
        val oldC = if ((cpu.cpsr and GbaCpu.FLAG_C) != 0) 1 else 0

        when (aluOp) {
            0 -> { cpu.r[rd] = a and b; cpu.setNzFlags(cpu.r[rd]) }
            1 -> { cpu.r[rd] = a xor b; cpu.setNzFlags(cpu.r[rd]) }
            2 -> {
                cpu.shifterCarry = (cpu.cpsr and GbaCpu.FLAG_C) != 0
                cpu.r[rd] = cpu.barrelShift(a, 0, b and 0xFF, false)
                cpu.setNzFlags(cpu.r[rd])
                if (cpu.shifterCarry) cpu.cpsr = cpu.cpsr or GbaCpu.FLAG_C else cpu.cpsr = cpu.cpsr and GbaCpu.FLAG_C.inv()
            }
            3 -> {
                cpu.shifterCarry = (cpu.cpsr and GbaCpu.FLAG_C) != 0
                cpu.r[rd] = cpu.barrelShift(a, 1, b and 0xFF, false)
                cpu.setNzFlags(cpu.r[rd])
                if (cpu.shifterCarry) cpu.cpsr = cpu.cpsr or GbaCpu.FLAG_C else cpu.cpsr = cpu.cpsr and GbaCpu.FLAG_C.inv()
            }
            4 -> {
                cpu.shifterCarry = (cpu.cpsr and GbaCpu.FLAG_C) != 0
                cpu.r[rd] = cpu.barrelShift(a, 2, b and 0xFF, false)
                cpu.setNzFlags(cpu.r[rd])
                if (cpu.shifterCarry) cpu.cpsr = cpu.cpsr or GbaCpu.FLAG_C else cpu.cpsr = cpu.cpsr and GbaCpu.FLAG_C.inv()
            }
            5 -> cpu.r[rd] = cpu.adcWithFlags(a, b, oldC, true)
            6 -> cpu.r[rd] = cpu.sbcWithFlags(a, b, oldC, true)
            7 -> {
                cpu.shifterCarry = (cpu.cpsr and GbaCpu.FLAG_C) != 0
                cpu.r[rd] = cpu.barrelShift(a, 3, b and 0xFF, false)
                cpu.setNzFlags(cpu.r[rd])
                if (cpu.shifterCarry) cpu.cpsr = cpu.cpsr or GbaCpu.FLAG_C else cpu.cpsr = cpu.cpsr and GbaCpu.FLAG_C.inv()
            }
            8 -> cpu.setNzFlags(a and b)
            9 -> cpu.r[rd] = cpu.subWithFlags(0, b, true)
            10 -> cpu.subWithFlags(a, b, true)
            11 -> cpu.addWithFlags(a, b, true)
            12 -> { cpu.r[rd] = a or b; cpu.setNzFlags(cpu.r[rd]) }
            13 -> { cpu.r[rd] = a * b; cpu.setNzFlags(cpu.r[rd]) }
            14 -> { cpu.r[rd] = a and b.inv(); cpu.setNzFlags(cpu.r[rd]) }
            15 -> { cpu.r[rd] = b.inv(); cpu.setNzFlags(cpu.r[rd]) }
        }
    }

    private fun execThumbHiRegBx(op: Int, cpu: GbaCpu) {
        val sub = (op ushr 8) and 0x3
        val h1 = (op ushr 7) and 1
        val h2 = (op ushr 6) and 1
        val rs = ((op ushr 3) and 0x7) or (h2 shl 3)
        val rd = (op and 0x7) or (h1 shl 3)
        val srcVal = cpu.r[rs]

        when (sub) {
            0 -> {
                cpu.r[rd] = cpu.r[rd] + srcVal
                if (rd == 15) { cpu.r[15] = cpu.r[15] and -2; cpu.branched = true }
            }
            1 -> cpu.subWithFlags(cpu.r[rd], srcVal, true)
            2 -> {
                cpu.r[rd] = srcVal
                if (rd == 15) { cpu.r[15] = cpu.r[15] and -2; cpu.branched = true }
            }
            3 -> {
                if ((srcVal and 1) != 0) {
                    cpu.cpsr = cpu.cpsr or GbaCpu.FLAG_T
                    cpu.r[15] = srcVal and -2
                } else {
                    cpu.cpsr = cpu.cpsr and GbaCpu.FLAG_T.inv()
                    cpu.r[15] = srcVal and -4
                }
                cpu.branched = true
            }
        }
    }
}
