import chisel3._
import chisel3.util._

class ALUiMDPipelineHazardIO extends PipelineHazardIO {
    // 除法器busy信号，传递给Hazard做阻塞判断
    val divBusy = Output(Bool())
}

class ALUiMDPipelineIO extends Bundle {
    val forward = new PipelineForwardIO
    val backend = new PipelineBackendIO
    val frontend = new PipelineFrontendIO
    val hazard = new ALUiMDPipelineHazardIO
}

class ALUiMDPipeline extends Module {
    val io = IO(new ALUiMDPipelineIO)
    
    // ========== EX1阶段 ==========
    val ex1Pkg = RegInit(0.U.asTypeOf(new InstructionPackage))
    when(io.hazard.ex1Flush) {
        ex1Pkg := 0.U.asTypeOf(new InstructionPackage)
    }.elsewhen(!io.hazard.ex1Stall) {
        ex1Pkg := io.backend.instPkgIn
    }
    
    // 应用Forward前递
    val ex1Rs1Data = io.forward.fwdRs1Data
    val ex1Rs2Data = io.forward.fwdRs2Data
    
    // ALU实例化
    val alu = Module(new ALU)
    val aluSrc1 = Mux(ex1Pkg.src1Sel === 0.U, ex1Rs1Data, ex1Pkg.pc)
    val aluSrc2 = Mux(ex1Pkg.src2Sel === 0.U, ex1Rs2Data, ex1Pkg.imm)
    alu.io.src1 := aluSrc1
    alu.io.src2 := aluSrc2
    alu.io.op := ex1Pkg.op(4, 0)
    
    // Multiply实例化
    val multiply = Module(new MulBooth2Wallce)
    multiply.io.src1 := ex1Rs1Data
    multiply.io.src2 := ex1Rs2Data
    multiply.io.op := ex1Pkg.op(4, 0)
    multiply.io.divBusy := srt2.io.busy
    
    // SRT2除法器实例化
    val srt2 = Module(new SRT2)
    srt2.io.src1 := ex1Rs1Data
    srt2.io.src2 := ex1Rs2Data
    srt2.io.op := ex1Pkg.op(4, 0)
    
    // 选择乘法或除法结果
    val isMulDiv = ex1Pkg.op(4)  // op[4]用于区分是否是mul/div
    val isDiv = ex1Pkg.op(2)     // op[2]用于区分mul和div
    val mulDivRes = Mux(isDiv, srt2.io.res, multiply.io.res)
    
    // EX1阶段更新InstPkg，选择ALU或MulDiv结果
    val ex1Result = Mux(isMulDiv, mulDivRes, alu.io.res)
    val ex1PkgOut = ex1Pkg.EX1Update(ex1Result, 0.U, false.B)
    
    // 输出divBusy信号给Hazard
    io.hazard.divBusy := srt2.io.busy
    
    // ========== EX2阶段 ==========
    val ex2Pkg = RegInit(0.U.asTypeOf(new InstructionPackage))
    when(io.hazard.ex2Flush) {
        ex2Pkg := 0.U.asTypeOf(new InstructionPackage)
    }.elsewhen(!io.hazard.ex2Stall) {
        ex2Pkg := ex1PkgOut
    }
    
    // ========== EX3阶段 ==========
    val ex3Pkg = RegInit(0.U.asTypeOf(new InstructionPackage))
    when(io.hazard.ex3Flush) {
        ex3Pkg := 0.U.asTypeOf(new InstructionPackage)
    }.elsewhen(!io.hazard.ex3Stall) {
        ex3Pkg := ex2Pkg
    }
    
    // ========== WB阶段 ==========
    val wbPkg = RegInit(0.U.asTypeOf(new InstructionPackage))
    when(io.hazard.wbFlush) {
        wbPkg := 0.U.asTypeOf(new InstructionPackage)
    }.elsewhen(!io.hazard.wbStall) {
        wbPkg := ex3Pkg
    }
    
    // WB阶段：写回ALU/MulDiv结果
    val wbData = wbPkg.aluResult
    val wbPkgOut = wbPkg.WBUpdate(wbData)
    
    // 写回到寄存器堆
    io.frontend.gprWen := wbPkgOut.rdValid && !wbPkgOut.rd(5)
    io.frontend.gprWaddr := wbPkgOut.rd(4, 0)
    io.frontend.gprWdata := wbPkgOut.rfWdata
    io.frontend.fprWen := wbPkgOut.rdValid && wbPkgOut.rd(5)
    io.frontend.fprWaddr := wbPkgOut.rd(4, 0)
    io.frontend.fprWdata := wbPkgOut.rfWdata
    
    // 输出到Forward和Hazard
    io.forward.ex1Pkg := ex1Pkg
    io.forward.ex2Pkg := ex2Pkg
    io.forward.wbPkg := wbPkgOut
    io.hazard.ex1Pkg := ex1Pkg
    io.hazard.ex2Pkg := ex2Pkg
}

