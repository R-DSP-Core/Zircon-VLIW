import chisel3._
import chisel3.util._

class FDivFPUPipelineIO extends Bundle {
    val forward = new PipelineForwardIO
    val backend = new PipelineBackendIO
    val frontend = new PipelineFrontendIO
    val hazard = new PipelineHazardIO
}

class FDivFPUPipeline extends Module {
    val io = IO(new FDivFPUPipelineIO)
    
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
    val ex1Rs3Data = io.forward.fwdRs3Data
    
    // FDiv实例化
    val fdiv = Module(new FDiv)
    fdiv.io.rs1Data := ex1Rs1Data
    fdiv.io.rs2Data := ex1Rs2Data
    fdiv.io.op := ex1Pkg.op
    
    // FPU实例化
    val fpu = Module(new FPU)
    fpu.io.rs1Data := ex1Rs1Data
    fpu.io.rs2Data := ex1Rs2Data
    fpu.io.rs3Data := ex1Rs3Data
    fpu.io.op := ex1Pkg.op
    
    // EX1阶段更新InstPkg（FDiv和FPU的结果先不区分，统一用fpuResult）
    val ex1PkgOut = ex1Pkg.EX1Update(0.U, 0.U, false.B)
    
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
        // EX3阶段更新fpuResult（根据是否是FDiv选择结果）
        // FDiv: FDIV_S, FSQRT_S
        val isFDiv = ex2Pkg.op === ZirconConfig.EXEOp.FDIV_S || ex2Pkg.op === ZirconConfig.EXEOp.FSQRT_S
        val fpuRes = Mux(isFDiv, fdiv.io.res, fpu.io.res)
        ex3Pkg := ex2Pkg.EX3Update(fpuRes)
    }
    
    // ========== WB阶段 ==========
    val wbPkg = RegInit(0.U.asTypeOf(new InstructionPackage))
    when(io.hazard.wbFlush) {
        wbPkg := 0.U.asTypeOf(new InstructionPackage)
    }.elsewhen(!io.hazard.wbStall) {
        wbPkg := ex3Pkg
    }
    
    // WB阶段：写回FPU结果
    val wbData = wbPkg.fpuResult
    val wbPkgOut = wbPkg.WBUpdate(wbData)
    
    // 写回到寄存器堆（FDiv/FPU总是写FPR）
    io.frontend.gprWen := false.B
    io.frontend.gprWaddr := 0.U
    io.frontend.gprWdata := 0.U
    io.frontend.fprWen := wbPkgOut.rdValid
    io.frontend.fprWaddr := wbPkgOut.rd(4, 0)
    io.frontend.fprWdata := wbPkgOut.rfWdata
    
    // 输出到Forward和Hazard
    // 注意：FDivFPUPipeline在EX2阶段不能前递（根据文档表格）
    io.forward.ex1Pkg := ex1Pkg
    io.forward.ex2Pkg := ex2Pkg
    io.forward.wbPkg := wbPkgOut
    io.hazard.ex1Pkg := ex1Pkg
    io.hazard.ex2Pkg := ex2Pkg
}

