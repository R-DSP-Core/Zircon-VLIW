import chisel3._
import chisel3.util._

// CPU的顶层IO：对外提供仿真环境的内存接口
class CPUIO extends Bundle {
    val imem = new FrontendMemIO     // 取指内存接口
    val dmem = new BackendMemIO      // 数据内存接口（两个LSU）
}

class CPU extends Module {
    val io = IO(new CPUIO)
    
    // ========== 实例化三大模块 ==========
    val frontend = Module(new Frontend)
    val backend = Module(new Backend)
    val hazard = Module(new Hazard)
    
    // ========== 连接Frontend和Backend ==========
    // Frontend -> Backend: 指令包
    backend.io.frontend.instPkgs := frontend.io.backend.instPkg
    
    // Backend -> Frontend: 写回信号
    frontend.io.backend.gprWen := backend.io.frontend.gprWen
    frontend.io.backend.gprWaddr := backend.io.frontend.gprWaddr
    frontend.io.backend.gprWdata := backend.io.frontend.gprWdata
    frontend.io.backend.fprWen := backend.io.frontend.fprWen
    frontend.io.backend.fprWaddr := backend.io.frontend.fprWaddr
    frontend.io.backend.fprWdata := backend.io.frontend.fprWdata
    
    // Backend -> Frontend: 分支重定向
    frontend.io.backend.predFail := backend.io.frontend.predFail
    frontend.io.backend.branchTgt := backend.io.frontend.branchTgt
    
    // ========== 连接Frontend和Hazard ==========
    frontend.io.hazard <> hazard.io.frontend
    
    // ========== 连接Backend和Hazard ==========
    backend.io.hazard <> hazard.io.backend
    
    // ========== 连接仿真环境内存接口 ==========
    // 取指接口
    io.imem <> frontend.io.mem
    
    // 数据访存接口（两个LSU）
    io.dmem <> backend.io.mem
}
