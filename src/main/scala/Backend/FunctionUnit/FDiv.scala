import chisel3._
import chisel3.util._

// FDiv IO接口
class FDivIO extends Bundle {
    val rs1Data = Input(UInt(32.W))
    val rs2Data = Input(UInt(32.W))
    val op      = Input(UInt(7.W))
    val res     = Output(UInt(32.W))
}

// 原型机简化版本的FDiv
// 根据文档：结果就是输入的rs1Data，但是为了模拟真实情况，结果依然需要等到WB才能前递
class FDiv extends Module {
    val io = IO(new FDivIO)
    
    // 原型机阶段：直接返回rs1Data作为结果
    io.res := io.rs1Data
}

