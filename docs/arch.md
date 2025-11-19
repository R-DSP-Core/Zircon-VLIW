# Zircon-VLIW 处理器设计

## 概述

Zircon-VLIW（以下简称Zircon）是一款基于RISC-V的VLIW处理器，目标是高效处理DSP任务。处理器使用8取指、8译码、8发射、8提交的结构来实现，能够处理由VLIW编译器生成的RISC-V imf程序。

在原型机阶段，Zircon使用哈佛架构，假设拥有单周期100%命中无限大Cache——即访存行为（包括取指、加载、存储）完全由软件环境Emulator在单周期内完成。

![RProject.drawio](./arch.assets/RProject.drawio.svg)

Zircon-VLIW原型机的设计模块关系如下：

* Frontend

  * NPC（下一PC生成）

  * Decoder *8（8路译码器）
  * FPRegfile（浮点寄存器堆）
  * GPRegfile（通用寄存器堆）

* Backend

  * FDiv-FPU Pipeline
  * ALU-FPU Pipeline * 2
  * ALU-iMD Pipeline * 2（ALU-整数乘除法）
  * ALU-LSU Pipeline * 2（ALU-访存）
  * ALU-Branch Pipeline * 2

* Hazard

## 指令传递范式

在Zircon中，指令以InstructionPackage类为基本单元，该类中包含了一条指令从取指到写回可能携带的所有信息，包括指令码、读写数据等。这个包会随着流水线向后传递，并在编译时借助Firrtl的死代码删除来裁剪掉不会在后续流水级使用的成员。

InstructionPackage的定义如下：

```scala
class InstructionPackage extends Bundle {
    /* IF Stage */
    val pc        = UInt(32.W)
    val inst      = UInt(32.W)
    /* ID Stage */
    // regfile
    val rs1       = UInt(6.W) // the highest bit to judge if it is float
    val rs2       = UInt(6.W) // the highest bit to judge if it is float
    val rs3       = UInt(6.W) // the highest bit to judge if it is float
    val rd        = UInt(6.W) // the highest bit to judge if it is float
    val rdValid   = Bool()
    val rs1Data   = UInt(32.W)
    val rs2Data   = UInt(32.W)
    val rs3Data   = UInt(32.W)
    // operation
    val op        = UInt(7.W) // [3:0]: operation; [4]: is branch or load or muldiv [5]: is store  [6]: is float (not fdiv) 
    val imm       = UInt(32.W)
    val src1Sel   = UInt(1.W) // 0: rs1; 1: pc; 
    val src2Sel   = UInt(1.W) // 0: rs2; 1: imm;
    /* EX Stage */
    val aluResult = UInt(32.W)
    val fpuResult = UInt(32.W)
    val branchTgt = UInt(32.W)
    val predFail  = Bool()
    val memResult = UInt(32.W)
    /* WB Stage */
    val rfWdata   = UInt(32.W)

    def IFUpdate(pc: UInt, inst: UInt): InstructionPackage = {
        val instPkg = WireDefault(this)
        instPkg.pc     := pc
        instPkg.inst   := inst
        instPkg
    }
    def IDUpdate(rs1: UInt, rs2: UInt, rs3: UInt, rd: UInt, rdValid: Bool, op: UInt, imm: UInt, src1Sel: UInt, src2Sel: UInt): InstructionPackage = {
        val instPkg = WireDefault(this)
        instPkg.rs1       := rs1
        instPkg.rs2       := rs2
        instPkg.rs3       := rs3
        instPkg.rd        := rd
        instPkg.rdValid   := rdValid
        instPkg.op        := op
        instPkg.imm       := imm
        instPkg.src1Sel   := src1Sel
        instPkg.src2Sel   := src2Sel
        instPkg
    }
    def IDUpdate(rs1Data: UInt, rs2Data: UInt, rs3Data: UInt): InstructionPackage = {
        val instPkg = WireDefault(this)
        instPkg.rs1Data   := rs1Data
        instPkg.rs2Data   := rs2Data
        instPkg.rs3Data   := rs3Data
        instPkg
    }
    def EX1Update(aluResult: UInt, branchTgt: UInt, predFail: Bool): InstructionPackage = {
        val instPkg = WireDefault(this)
        instPkg.aluResult := aluResult
        instPkg.branchTgt := branchTgt
        instPkg.predFail  := predFail
        instPkg
    }
    def EX2Update(memResult: UInt): InstructionPackage = {
        val instPkg = WireDefault(this)
        instPkg.memResult := memResult
        instPkg
    }
    def EX3Update(fpuResult: UInt): InstructionPackage = {
        val instPkg = WireDefault(this)
        instPkg.fpuResult := fpuResult
        instPkg
    }
    def WBUpdate(rfWdata: UInt): InstructionPackage = {
        val instPkg = WireDefault(this)
        instPkg.rfWdata := rfWdata
        instPkg
    }
}
```

其中，在最后的几个方法，是为了能够在流水线的不同流水级，从不同的模块中将成员逐渐“组装”到InstructionPackage中。除了Decoder会在元件内组装之外，其余的方法都是在Frontend.scala和Backend.scala按照流水级进行调用。



## Frontend

前端的代码目录结构如下：

```
Frontend/
├── Frontend.scala
├── Fetch/
│   └──NPC.scala
├── Decode/
│   ├── Decoder.scala
│   └── RVISA.scala
└── Regfile/
    └── Regfile.scala
```

### Frontend

前端由两个流水级组成：IF（取指）和ID（指令译码）。其中，NPC和PC寄存器（复位值0x80000000）位于IF段，而Decoder们和两个例化的寄存器堆位于ID段，流水级之间使用实现段间寄存器分割。除此之外，取指行为在IF端发起，当周期仿真环境就会根据PC值给回8条地址上连续的指令。

FrontendIO有3套接口：

* FrontendMemIO：将PC值发送给仿真环境，仿真环境一次给回以当前PC为起始地址的连续8条指令
* FrontendBackendIO：将ID段组装好的8个InstPkg发送给后端，接收后端来的重定向目标地址，并接收后端来的写回请求。
* FrontendHazardIO：Hazard给出的flush和stall信号，用来控制ShiftRegister的流动和NPC的生成

当Hazard给出flush信号时，IF-ID段间寄存器应当被冲刷，NPC应当重新基于后端的重定向目标地址来重定向PC。当Hazard给出stall信号时，IF-ID段间寄存器应当被阻塞，NPC应当保持PC不变

### NPC

NPC模块负责根据流水线的执行情况来计算下一个PC并实现PC的重定向，NPCIO拥有3套接口：

* NPCHazardIO：Hazard的冲刷和阻塞信号
* NPCBackendIO：后端计算单元给出的PC重定向地址
* NPCFrontendIO：前端中的PC值，并向前端发送npc（下一个PC值）

### Decoder

Decoder将指令进行译码，转换为InstructionPackage中的部分字段。由于每条流水线只能到来部分固定类型的指令，因此Decoder具有模板化设计（通过输入参数控制决定能译码哪些类型的指令）。8条流水线的功能部件如表所示：（需要注意的是，0号流水线的FPU不支持FPToInt和IntToFP转换，1和2支持）

| 流水线编号 | 0    | 1    | 2    | 3        | 4        | 5    | 6    | 7      |
| ---------- | ---- | ---- | ---- | -------- | -------- | ---- | ---- | ------ |
| 部件1      | FDiv | ALU  | ALU  | ALU      | ALU      | ALU  | ALU  | ALU    |
| 部件2      | FPU  | FPU  | FPU  | iMul/Div | iMul/Div | LSU  | LSU  | Branch |

具体部件的功能后文讲解。因此，我们为每一个功能部件可以执行的指令都创建了一个译码表（即指令码到操作微码的映射）。在执行时，Decoder会查询自己支持的所有译码表，并确定当前指令所需要的操作微码，并将其打包到InstPkgOut中并输出。

Decider只有一套IO：

* DecoderIO：输入inst，输出InstPkgOut

Decoder基于RVISA.scala中使用BitPat定义的指令集编码进行工作，具体操作微码编码在Config.scala中。

译码器实现有基础细节：

* 对于寄存器编号，指令集中是5位，但我们扩展了1位，如果是GPR，那么最高位为0；如果是FPR，那么最高位是1。
* 对于rd编号为0且是GPR的情况，rdValid将会被设置为false.B。这是为了简化Regfile的写优先设计：
  * 如果GPR写0，那么这个写入值不应该被写优先前递，因为0号寄存器不管怎么读都是0。所以当初始化为0时，GPR的写前递、读逻辑都不需要特判地址为0的问题
  * FPR并没有“0号寄存器一定是0”的规定

### Regfile

写优先、长度为32、宽度为32位的读、写端口可以参数化的寄存器堆模板，由Frontend例化为gpr和fpr。由于RISC-V寄存器编号在固定位置，因此读地址无需经过译码就可以直接由Frontend连接到Regfile中

前端并不是8个通道都需要同时读GPR和FPR，后端也并不是每一个流水线都有可能写回GPR和FPR，具体连线分配关系如下表所示：

| 流水线编号 | 0    | 1    | 2    | 3    | 4    | 5    | 6    | 7    |
| ---------- | ---- | ---- | ---- | ---- | ---- | ---- | ---- | ---- |
| 读GPR口    | 0    | 2    | 2    | 2    | 2    | 2    | 2    | 2    |
| 读FPR口    | 3    | 3    | 3    | 0    | 0    | 0    | 0    | 0    |
| 写GPR口    | 0    | 1    | 1    | 1    | 1    | 1    | 1    | 1    |
| 写FPR口    | 1    | 1    | 1    | 0    | 0    | 0    | 0    | 0    |

因此，GPR由14个读口和7个写口，FPR有9个读口和3个写口，每个寄存器堆内，所有读口对所有写口都需要判断写优先。

## Backend

后端代码目录结构如下：

```
Frontend/
├── Backend.scala
├── Pipeline/
│   ├── FDivFPUPipeline.scala
│		├── ALUFPUPipeline.scala
│		├── ALUiMDUPipeline.scala
│		├── ALULSUPipeline.scala
│		└── ALUBranchPipeline.scala
├── FunctionUnit/
│   ├── ALU.scala
│   ├── Adder.scala
│   ├── Branch.scala
│		├── FPU.scala
│		├── FDiv.scala
│		├── LSU.scala
│		├── Multiply.scala
│		├── Shifter.scala
│		└── SRT2.scala
└── Bypass/
    └── Forward.scala
```

### Backend

后端由多个Pipeline组成，在这些Pipeline之前，首先使用一个段间寄存器，接收Frontend给出的InstructionPackage——这也是ID和EX1阶段的段间寄存器：ID-EX1段间寄存器。每个Pipeline内部都有EX1、EX2、EX3、WB四个阶段，也就是拥有EX1-EX2、EX2-EX3、EX3-WB四个段间寄存器。

每个Pipeline中拥有的功能单元如下表所示：

| 流水线编号 | 0    | 1    | 2    | 3        | 4        | 5    | 6    | 7      |
| ---------- | ---- | ---- | ---- | -------- | -------- | ---- | ---- | ------ |
| 部件1      | FDiv | ALU  | ALU  | ALU      | ALU      | ALU  | ALU  | ALU    |
| 部件2      | FPU  | FPU  | FPU  | iMul/Div | iMul/Div | LSU  | LSU  | Branch |

BackendIO拥有3套接口：

* Flipped(FrontendBackendIO) ：把前面前端和后端的接口整个用Flipped反过来
* BackendHazardIO：向Hazard输出EX1和EX2阶段各个流水线的InstPkg用来做RAW判断，以及传递后端中可能出现的分支预测失败flush、除法器stall等，同时接收Hazard针对每个阶段寄存器的独立的flush和stall信号（即，任何停顿信号都应该送入Hazard统一调度）
* BackendMemIO：两个ALULSUPipeline中LSU和仿真环境数据交互接口，根据地址读、写数据

每个寄存器在Hazard给出属于这个寄存器的flush或stall时，都应该做出相应的操作，注意同时给出时优先响应flush（除了ID-EX1寄存器之外，其余都是封装在Pipeline内部的）

### FunctionUnit

#### Adder

adder中有多种经过测试的块间进位加法器，时序较好，供其他功能单元调用。

#### Shifter

桶形移位器实现

#### ALU

ALU占据EX1阶段，通过实例化Adder和Shifter，并补充一些辅助的逻辑，可以实现EXEOp中所有标出的非乘除整型运算。其两个源操作数的送入在Pipeline中由InstPkg中的Src1Sel和Src2Sel决定，其结果可以在EX阶段被前递，但是特别注意：

* 在执行JALR和JAL指令的时候，我们应该默认让他执行PC+4的操作
* 如果选择了RS1或者RS2作为源操作数，那么还需要看是否该数据正在由forward模块前递，以前递为准

#### Branch

branch占据EX1阶段，可以判断分支是否跳转+计算分支跳转地址。注意其输入应该也受到前递影响

#### Multiply

该乘法器采用3级流水，2bit Booth编码+Wallce Tree+全加器，占据EX1、EX2、EX3三级流水线，其结果必须要等到WB段才可以前递，注意它里面是独立的段间寄存器，应该同样需要响应Hazard给出的stall或者flush，注意其输入应该也受到前递影响

#### SRT2

SRT2除法器，占据EX1、EX2、EX3三级流水线，其结果必须要等到WB段才可以前递。其stall信号将会在EX3阶段产生，其计算周期不定，由被除数和除数的绝对值前导零之差决定，注意其输入应该也受到前递影响

#### FPU&FDiv

原型机阶段先不实现这个，默认他们需要EX1、EX2、EX3三级流水，其结果就是输入的rs1Data，但是为了模拟真实情况，结果依然需要等到WB才能前递

#### LSU

LSU占据EX2、EX3阶段，在原型机阶段非常简单：把op、由ALU计算并过了EX1-EX2寄存器的地址、在EX1经过前递修正的写数据（rs2Data）送出去，然后把读回来的数据，按照加载指令的定义，进行零扩展或者位扩展，送到后面。这过程在原型机阶段，事实上在EX1阶段就全部完成了，但是为了模拟真实Cache，我们要求它空过EX3阶段，只有到WB阶段才能前递。

### Pipeline

每个Pipeline都有基础的几套接口：

* PipelineForwardIO：包括EX1阶段的instPkg、EX2阶段的InstPkg、WB阶段的InstPkg，用来供Forward进行前递，并接收Forward的前递
* PipelineBackendIO：接受Backend从Frontend那里送来的InstPkg，每一个Pkg按照位置只会送入一个Pipeline
* PipelineFrontendIO：写回前端两个寄存器堆的数据和请求，包括地址、写数据和两个寄存器堆的写使能，以及ALUBranchPipeline特有的分支跳转地址
* PipelineHazardIO：将产生的stall和flush请求送到Hazard，由Hazard统一调度；同时需要将EX1和EX2阶段的instPkg也送出，让Hazard判断RAW冲突，并接收Hazard给出针对每个段间寄存器的stall和flush

流水线的WB段，需要一个多选器，基于op进行判断到底需要写回所有功能单元产生的哪个数据，并将更新后的InstPkg送到Forward中

#### FDivFPUPipeline

这个流水线因为在原型机阶段没什么已经实现好的元件，就直接简单实现就可以了

#### ALUFPUPipeline

这个流水线只有EX1阶段有ALU，然后可以在EX2阶段前递ALU数据

#### ALUiMDUPipeline

这个流水线只有EX1阶段有ALU，EX1、EX2、EX3阶段有Multiply和SRT2除法器。这个流水线需要将除法器产生的停顿信号送到PipelineHazardIO的stall中，ALU结果EX2前递，乘除法结果必须WB前递

#### ALULSUPipeline

这个流水线EX1阶段有ALU，EX2阶段有LSU，注意LSU要用ALU的加法结果来做访存，所以这里的ALU需要特殊判断一下当前如果是load或者store，默认执行加法。注意EX3阶段要空过，等到WB才可以前递LSU的结果，ALU的结果EX2就可以给出

#### ALUBranchPipeline

这个流水线EX1阶段有ALU和Branch，ALU结果EX2阶段前递，注意Branch结果不可以在EX1阶段直接送出，而是为了时序考虑打一拍，EX2阶段一起送出到PipelineHazardIO。注意，我们现在完全采用静态预测，不需要给出predOffset，如果发现需要跳转，就认为predFail

### Forward

Forward接受每条流水线在EX2或WB阶段给出的InstPkg，通过判断其写入使能和写入寄存器地址和EX1阶段的instPkg2-3个源寄存器（注意只有前三条流水线是3个源寄存器，后面的流水线只有2个源寄存器），判断是否需要将EX2或WB阶段的数据进行前递。

这里的逻辑比较复杂：每一个EX1阶段的数据，都需要和所有8条流水线的EX2、WB（如果可以前递的话）的rd进行比较（特别注意是带着第六位——判断是否浮点的寄存器编号），

* 当同一流水级，有多个数据同时可以前递的时候，以编号较大的流水线数据为准（因为最新）
* 当不同流水级，有多个数据可以同时前递的时候，以EX2为准（因为最新）

所有流水线的EX、WB是否可以前递如下表所示

| 流水线 | 0        | 1       | 2       | 3        | 4        | 5       | 6       | 7    |
| ------ | -------- | ------- | ------- | -------- | -------- | ------- | ------- | ---- |
| EX2    | No       | ALU     | ALU     | ALU      | ALU      | ALU     | ALU     | ALU  |
| WB     | FPU+FDiv | ALU+FPU | ALU+FPU | ALU+iMDU | ALU+iMDU | ALU+LSU | ALU+LSU | ALU  |

