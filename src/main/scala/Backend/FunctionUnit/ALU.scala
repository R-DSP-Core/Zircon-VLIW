import chisel3._
import chisel3.util._
import ZirconConfig.EXEOp._
import Shifter._


class ALUIO extends Bundle {
    val src1 = Input(UInt(32.W))
    val src2 = Input(UInt(32.W))
    val op   = Input(UInt(7.W))  // 扩展到7位以区分 load/store
    val res  = Output(UInt(32.W))
}

class ALU extends Module {
    val io = IO(new ALUIO)

    // 判断是否是 load/store 操作
    // load: LB=0x10, LH=0x11, LW=0x12, LBU=0x14, LHU=0x15 (0x10-0x17, bit[4]=1, bit[3]=0, bit[5]=0)
    // FLW=0x54 (bit[6]=1, bit[4]=1)
    // store: SB=0x20, SH=0x21, SW=0x22 (0x20-0x27, bit[5]=1, bit[6]=0)
    // FSW=0x62 (bit[6]=1, bit[5]=1)
    // 注意区分 branch 指令 (0x18-0x1f, bit[4]=1, bit[3]=1)
    val isLoad = (io.op(4) && !io.op(3) && !io.op(5) && !io.op(6)) ||  // LB-LHU
                 (io.op(6) && io.op(4) && !io.op(5))                    // FLW
    val isStore = (io.op(5) && !io.op(6)) ||                           // SB-SW
                  (io.op(6) && io.op(5))                                // FSW
    val isLoadStore = isLoad || isStore
    
    // adder
    val adderSrc1 = WireDefault(io.src1)
    val adderSrc2 = WireDefault(io.src2)
    val adderCin  = WireDefault(0.U)

    val adder     = BLevelPAdder32(adderSrc1, adderSrc2, adderCin)

    val adderRes  = adder.io.res
    val adderCout = adder.io.cout

    // shifter
    val aluOp = io.op(4, 0)  // 低5位用于ALU操作选择
    val sfterSrc = Mux(aluOp === SLL, Reverse(io.src1), io.src1)
    val sfterShf = io.src2(4, 0)
    val sfterSgn = aluOp === SRA

    val shifter  = Shifter(sfterSrc, sfterShf, sfterSgn)

    val sfterRes = shifter.io.res

    // 默认结果是加法结果（用于 load/store 地址计算）
    io.res := adderRes
    
    // 仅当不是 load/store 时才执行其他 ALU 操作
    when(!isLoadStore) {
        switch(aluOp){
            is(ADD){
                adderSrc2 := io.src2
            }
            is(SUB){
                adderSrc2 := ~io.src2
                adderCin  := 1.U
            }
            is(SLTU){
                adderSrc2 := ~io.src2
                adderCin  := 1.U
                io.res    := !adderCout
            }
            is(SLT){
                adderSrc2 := ~io.src2
                adderCin  := 1.U
                io.res    := io.src1(31) && !io.src2(31) || !(io.src1(31) ^ io.src2(31)) && adderRes(31)
            }
            is(AND){
                io.res := io.src1 & io.src2
            }
            is(OR){
                io.res := io.src1 | io.src2
            }
            is(XOR){
                io.res := io.src1 ^ io.src2
            }
            is(SLL){
                io.res := Reverse(shifter.io.res)
            }
            is(SRL){
                io.res := sfterRes
            }
            is(SRA){
                io.res := sfterRes
            }
            is(LUI){
                io.res := io.src2  // LUI: 直接返回立即数
            }
            is(JAL){
                adderSrc2 := 4.U
            }
            is(JALR){
                adderSrc2 := 4.U
            }
        }
    }
}