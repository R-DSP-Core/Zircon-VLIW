import chisel3._
import chisel3.util._

class ALULSUPipelineIO extends Bundle {
    val forward = new PipelineForwardIO
    val backend = new PipelineBackendIO
    val frontend = new PipelineFrontendIO
    val hazard = new PipelineHazardIO
    val mem = new LSUMemIO  // LSU的内存接口
}

class ALULSUPipeline extends Module {
    val io = IO(new ALULSUPipelineIO)
    
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
    
    // ALU实例化（用于计算访存地址）
    val alu = Module(new ALU)
    val aluSrc1 = Mux(ex1Pkg.src1Sel === 0.U, ex1Rs1Data, ex1Pkg.pc)
    val aluSrc2 = Mux(ex1Pkg.src2Sel === 0.U, ex1Rs2Data, ex1Pkg.imm)
    alu.io.src1 := aluSrc1
    alu.io.src2 := aluSrc2
    alu.io.op := ex1Pkg.op(4, 0)
    
    // LSU实例化
    val lsu = Module(new LSU)
    lsu.io.op := ex1Pkg.op
    lsu.io.addr := alu.io.res  // 访存地址由ALU计算
    lsu.io.wdata := ex1Rs2Data  // store指令的数据来自rs2
    
    // 连接LSU的内存接口
    io.mem <> lsu.io.mem
    
    // EX1阶段更新InstPkg
    val ex1PkgOut = ex1Pkg.EX1Update(alu.io.res, 0.U, false.B)
    
    // ========== EX2阶段 ==========
    val ex2Pkg = RegInit(0.U.asTypeOf(new InstructionPackage))
    when(io.hazard.ex2Flush) {
        ex2Pkg := 0.U.asTypeOf(new InstructionPackage)
    }.elsewhen(!io.hazard.ex2Stall) {
        // EX2阶段更新memResult
        ex2Pkg := ex1PkgOut.EX2Update(lsu.io.res)
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
    
    // WB阶段：选择写回数据（load指令用memResult，其他用aluResult）
    val isLoad = wbPkg.op === ZirconConfig.EXEOp.LB || wbPkg.op === ZirconConfig.EXEOp.LH || 
                 wbPkg.op === ZirconConfig.EXEOp.LW || wbPkg.op === ZirconConfig.EXEOp.LBU || 
                 wbPkg.op === ZirconConfig.EXEOp.LHU || wbPkg.op === ZirconConfig.EXEOp.FLW
    val wbData = Mux(isLoad, wbPkg.memResult, wbPkg.aluResult)
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

