/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
*
* XiangShan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package xiangshan.cache.mmu

import org.chipsalliance.cde.config.Parameters
import chisel3._
import chisel3.util._
import xiangshan._
import xiangshan.cache.{HasDCacheParameters, MemoryOpConstants}
import utils._
import utility._
import freechips.rocketchip.diplomacy.{LazyModule, LazyModuleImp}
import freechips.rocketchip.tilelink._
import xiangshan.backend.fu.{PMPReqBundle, PMPRespBundle}

/** Page Table Walk is divided into two parts
  * One,   PTW: page walk for pde, except for leaf entries, one by one
  * Two, LLPTW: page walk for pte, only the leaf entries(4KB), in parallel
  */


/** PTW : page table walker
  * a finite state machine
  * only take 1GB and 2MB page walks
  * or in other words, except the last level(leaf)
  **/
class PTWIO()(implicit p: Parameters) extends MMUIOBaseBundle with HasPtwConst {
  val req = Flipped(DecoupledIO(new Bundle {
    val req_info = new L2TlbInnerBundle()
    val l1Hit = Bool()
    val ppn = UInt(ppnLen.W)
  }))
  val resp = DecoupledIO(new Bundle {
    val source = UInt(bSourceWidth.W)
    val s2xlate = UInt(2.W) // 0 bit: has s2xlate, 1 bit: Only valid when 0 bit is 1. If 0, all stage; if 1, only stage 2
    val resp = new PtwMergeResp
    val h_resp = new HptwResp
  })

  val llptw = DecoupledIO(new LLPTWInBundle())
  // NOTE: llptw change from "connect to llptw" to "connect to page cache"
  // to avoid corner case that caused duplicate entries

  val hptw = new Bundle {
    val req = DecoupledIO(new Bundle {
      val id = UInt(log2Up(l2tlbParams.llptwsize).W)
      val gvpn = UInt(vpnLen.W)
    })
    val resp = Flipped(Valid(new Bundle {
      val h_resp = Output(new HptwResp)
    }))
  }
  val mem = new Bundle {
    val req = DecoupledIO(new L2TlbMemReqBundle())
    val resp = Flipped(ValidIO(UInt(XLEN.W)))
    val mask = Input(Bool())
  }
  val pmp = new Bundle {
    val req = ValidIO(new PMPReqBundle())
    val resp = Flipped(new PMPRespBundle())
  }

  val refill = Output(new Bundle {
    val req_info = new L2TlbInnerBundle()
    val level = UInt(log2Up(Level).W)
  })
}

class PTW()(implicit p: Parameters) extends XSModule with HasPtwConst with HasPerfEvents {
  val io = IO(new PTWIO)
  val sfence = io.sfence
  val mem = io.mem
  val req_s2xlate = Reg(UInt(2.W))
  val enableS2xlate = RegInit(false.B)
  val onlyS1xlate = RegInit(false.B)
  val onlyS2xlate = RegInit(false.B)

  val satp = Mux(enableS2xlate, io.csr.vsatp, io.csr.satp)
  val hgatp = io.csr.hgatp
  val flush = io.sfence.valid || satp.changed
  val s2xlate = enableS2xlate && !onlyS1xlate
  val level = RegInit(0.U(log2Up(Level).W))
  val af_level = RegInit(0.U(log2Up(Level).W)) // access fault return this level
  val ppn = Reg(UInt(ppnLen.W))
  val vpn = Reg(UInt(vpnLen.W)) // vpn or gvpn
  val levelNext = level + 1.U
  val l1Hit = Reg(Bool())
  val pte = mem.resp.bits.asTypeOf(new PteBundle().cloneType)

  // s/w register
  val s_pmp_check = RegInit(true.B)
  val s_mem_req = RegInit(true.B)
  val s_llptw_req = RegInit(true.B)
  val w_mem_resp = RegInit(true.B)
  val s_hptw_req = RegInit(true.B)
  val w_hptw_resp = RegInit(true.B)
  val s_last_hptw_req = RegInit(true.B)
  val w_last_hptw_resp = RegInit(true.B)
  // for updating "level"
  val mem_addr_update = RegInit(false.B)

  val idle = RegInit(true.B)
  val finish = WireInit(false.B)
  val sent_to_pmp = idle === false.B && (s_pmp_check === false.B || mem_addr_update) && !finish

  val pageFault = pte.isPf(level)
  val accessFault = RegEnable(io.pmp.resp.ld || io.pmp.resp.mmio, sent_to_pmp)

  val hptw_pageFault = RegInit(false.B)
  val hptw_accessFault = RegInit(false.B)
  val last_s2xlate = RegInit(false.B)

  val ppn_af = pte.isAf()
  val find_pte = pte.isLeaf() || ppn_af || pageFault
  val to_find_pte = level === 1.U && find_pte === false.B
  val source = RegEnable(io.req.bits.req_info.source, io.req.fire)

  val l1addr = MakeAddr(satp.ppn, getVpnn(vpn, 2))
  val l2addr = MakeAddr(Mux(l1Hit, ppn, pte.ppn), getVpnn(vpn, 1))
  val mem_addr = Mux(af_level === 0.U, l1addr, l2addr)

  val hptw_resp = io.hptw.resp.bits.h_resp
  val gpaddr = Mux(onlyS2xlate, Cat(vpn, 0.U(offLen.W)), mem_addr)
  val hpaddr = Cat(hptw_resp.entry.ppn, 0.U(offLen.W))

  io.req.ready := idle

  io.resp.valid := idle === false.B && mem_addr_update && !last_s2xlate && ((w_mem_resp && find_pte) || (s_pmp_check && accessFault) || onlyS2xlate)
  io.resp.bits.source := source
  io.resp.bits.resp.apply(pageFault && !accessFault && !ppn_af, accessFault || ppn_af, Mux(accessFault, af_level,level), pte, vpn, satp.asid, hgatp.asid, vpn(sectortlbwidth - 1, 0), not_super = false)
  io.resp.bits.h_resp := io.hptw.resp.bits.h_resp
  io.resp.bits.s2xlate := s2xlate

  io.llptw.valid := s_llptw_req === false.B && to_find_pte && !accessFault
  io.llptw.bits.req_info.source := source
  io.llptw.bits.req_info.vpn := vpn
  io.llptw.bits.req_info.s2xlate := req_s2xlate

  io.pmp.req.valid := DontCare // samecycle, do not use valid
  io.pmp.req.bits.addr := Mux(s2xlate, hpaddr, mem_addr)
  io.pmp.req.bits.size := 3.U // TODO: fix it
  io.pmp.req.bits.cmd := TlbCmd.read

  mem.req.valid := s_mem_req === false.B && !mem.mask && !accessFault && s_pmp_check
  mem.req.bits.addr := Mux(s2xlate, hpaddr, mem_addr)
  mem.req.bits.id := FsmReqID.U(bMemID.W)

  io.refill.req_info.vpn := vpn
  io.refill.level := level
  io.refill.req_info.source := source

  io.hptw.req.valid := !s_hptw_req || !s_last_hptw_req
  io.hptw.req.bits.id := FsmReqID.U(bMemID.W)
  io.hptw.req.bits.gvpn := get_pn(gpaddr)

  io.hptw.req.valid := !s_hptw_req || !s_last_hptw_req
  io.hptw.req.bits.id := FsmReqID.U(bMemID.W)
  io.hptw.req.bits.gvpn := gvpn

  when (io.req.fire){
    val req = io.req.bits
    level := Mux(req.l1Hit, 1.U, 0.U)
    af_level := Mux(req.l1Hit, 1.U, 0.U)
    ppn := Mux(req.l1Hit, io.req.bits.ppn, satp.ppn)
    vpn := io.req.bits.req_info.vpn
    enableS2xlate := io.req.bits.req_info.s2xlate =/= noS2xlate
    onlyS1xlate := io.req.bits.req_info.s2xlate === onlyS1xlate
    onlyS2xlate := io.req.bits.req_info.s2xlate === onlyS2xlate
    l1Hit := req.l1Hit
    accessFault := false.B
    s_pmp_check := false.B
    idle := false.B
    hptw_pageFault := false.B
    s2xlate := io.req.bits.req_info.s2xlate
    when(io.req.bits.req_info.s2xlate =/= noS2xlate && io.req.bits.req_info.s2xlate =/= onlyStage1){
      last_s2xlate := true.B
      s_hptw_req := false.B
    }.otherwise {
      s_pmp_check := false.B
    }
  }

  when(io.hptw.req.fire() && s_hptw_req === false.B){
    s_hptw_req := true.B
    w_hptw_resp := false.B
  }

  when(io.hptw.resp.fire() && w_hptw_resp === false.B) {
    hptw_pageFault := io.hptw.resp.bits.h_resp.gpf
    hptw_accessFault := io.hptw.resp.bits.h_resp.gaf
    w_hptw_resp := true.B
    when(onlyS2xlate){
      mem_addr_update := true.B
      last_s2xlate := false.B
    }.otherwise {
      s_pmp_check := false.B
    }
  }

  when(io.hptw.req.fire() && s_last_hptw_req === false.B) {
    w_last_hptw_resp := false.B
    s_last_hptw_req := true.B
  }

  when(io.hptw.resp.fire() && w_last_hptw_resp === false.B){
    hptw_pageFault := io.hptw.resp.bits.h_resp.gpf
    hptw_accessFault := io.hptw.resp.bits.h_resp.gaf
    w_last_hptw_resp := true.B
    mem_addr_update := true.B
    last_s2xlate := false.B
  }

  when(sent_to_pmp && mem_addr_update === false.B){
    s_mem_req := false.B
    s_pmp_check := true.B
  }

  when(accessFault && idle === false.B){
    s_pmp_check := true.B
    s_mem_req := true.B
    w_mem_resp := true.B
    s_llptw_req := true.B
    s_hptw_req := true.B
    w_hptw_resp := true.B
    s_last_hptw_req := true.B
    w_last_hptw_resp := true.B
    mem_addr_update := true.B
    last_s2xlate := false.B
  }

  when (mem.req.fire){
    s_mem_req := true.B
    w_mem_resp := false.B
  }

  when(mem.resp.fire && w_mem_resp === false.B){
    w_mem_resp := true.B
    af_level := af_level + 1.U
    s_llptw_req := false.B
    mem_addr_update := true.B
  }

  when(mem_addr_update){
    when(level === 0.U && !(find_pte || accessFault)){
      level := levelNext
      when(s2xlate){
        s_hptw_req := false.B
      }.otherwise{
        s_mem_req := false.B
      }
      s_llptw_req := true.B
      mem_addr_update := false.B
    }.elsewhen(io.llptw.valid){
      when(io.llptw.fire) {
        idle := true.B
        s_llptw_req := true.B
        mem_addr_update := false.B
        last_s2xlate := false.B
      }
      finish := true.B
    }.elsewhen(s2xlate && last_s2xlate === true.B) {
      s_last_hptw_req := false.B
      mem_addr_update := false.B
    }.elsewhen(io.resp.valid){
      when(io.resp.fire) {
        idle := true.B
        s_llptw_req := true.B
        mem_addr_update := false.B
        accessFault := false.B
      }
      finish := true.B
    }
  }


  when (sfence.valid) {
    idle := true.B
    s_pmp_check := true.B
    s_mem_req := true.B
    s_llptw_req := true.B
    w_mem_resp := true.B
    accessFault := false.B
    mem_addr_update := false.B
    s_hptw_req := true.B
    w_hptw_resp := true.B
    s_last_hptw_req := true.B
    w_last_hptw_resp := true.B
  }


  XSDebug(p"[ptw] level:${level} notFound:${pageFault}\n")

  // perf
  XSPerfAccumulate("fsm_count", io.req.fire)
  for (i <- 0 until PtwWidth) {
    XSPerfAccumulate(s"fsm_count_source${i}", io.req.fire && io.req.bits.req_info.source === i.U)
  }
  XSPerfAccumulate("fsm_busy", !idle)
  XSPerfAccumulate("fsm_idle", idle)
  XSPerfAccumulate("resp_blocked", io.resp.valid && !io.resp.ready)
  XSPerfAccumulate("ptw_ppn_af", io.resp.fire && ppn_af)
  XSPerfAccumulate("mem_count", mem.req.fire)
  XSPerfAccumulate("mem_cycle", BoolStopWatch(mem.req.fire, mem.resp.fire, true))
  XSPerfAccumulate("mem_blocked", mem.req.valid && !mem.req.ready)

  TimeOutAssert(!idle, timeOutThreshold, "page table walker time out")

  val perfEvents = Seq(
    ("fsm_count         ", io.req.fire                                     ),
    ("fsm_busy          ", !idle                                             ),
    ("fsm_idle          ", idle                                              ),
    ("resp_blocked      ", io.resp.valid && !io.resp.ready                   ),
    ("mem_count         ", mem.req.fire                                    ),
    ("mem_cycle         ", BoolStopWatch(mem.req.fire, mem.resp.fire, true)),
    ("mem_blocked       ", mem.req.valid && !mem.req.ready                   ),
  )
  generatePerfEvent()
}

/*========================= LLPTW ==============================*/

/** LLPTW : Last Level Page Table Walker
  * the page walker that only takes 4KB(last level) page walk.
  **/

class LLPTWInBundle(implicit p: Parameters) extends XSBundle with HasPtwConst {
  val req_info = Output(new L2TlbInnerBundle())
  val ppn = Output(if(HasHExtension) UInt(gvpnLen.W) else UInt(ppnLen.W))
}

class LLPTWIO(implicit p: Parameters) extends MMUIOBaseBundle with HasPtwConst {
  val in = Flipped(DecoupledIO(new LLPTWInBundle()))
  val out = DecoupledIO(new Bundle {
    val req_info = Output(new L2TlbInnerBundle())
    val id = Output(UInt(bMemID.W))
    val h_resp = Output(new HptwResp)
    val af = Output(Bool())
  })
  val mem = new Bundle {
    val req = DecoupledIO(new L2TlbMemReqBundle())
    val resp = Flipped(Valid(new Bundle {
      val id = Output(UInt(log2Up(l2tlbParams.llptwsize).W))
    }))
    val enq_ptr = Output(UInt(log2Ceil(l2tlbParams.llptwsize).W))
    val buffer_it = Output(Vec(l2tlbParams.llptwsize, Bool()))
    val refill = Output(new L2TlbInnerBundle())
    val req_mask = Input(Vec(l2tlbParams.llptwsize, Bool()))
  }
  val cache = DecoupledIO(new L2TlbInnerBundle())
  val pmp = new Bundle {
    val req = Valid(new PMPReqBundle())
    val resp = Flipped(new PMPRespBundle())
  }
  val hptw = new Bundle {
    val req = DecoupledIO(new Bundle{
      val id = UInt(log2Up(l2tlbParams.llptwsize).W)
      val gvpn = UInt(vpnLen.W)
    })
    val resp = Flipped(Valid(new Bundle {
      val id = Output(UInt(log2Up(l2tlbParams.llptwsize).W))
      val h_resp = Output(new HptwResp)
    }))
  }
}

class LLPTWEntry(implicit p: Parameters) extends XSBundle with HasPtwConst {
  val req_info = new L2TlbInnerBundle()
  val s2xlate = Bool()
  val ppn = UInt(ppnLen.W)
  val wait_id = UInt(log2Up(l2tlbParams.llptwsize).W)
  val af = Bool()
  val gaf = Bool()
  val gpf = Bool()
}


class LLPTW(implicit p: Parameters) extends XSModule with HasPtwConst with HasPerfEvents {
  val io = IO(new LLPTWIO())
  val enableS2xlate = io.in.bits.req_info.s2xlate =/= noS2xlate
  val satp = Mux(enableS2xlate, io.csr.vsatp, io.csr.satp)

  val flush = io.sfence.valid || satp.changed
  val entries = Reg(Vec(l2tlbParams.llptwsize, new LLPTWEntry()))
  val state_idle :: state_hptw_req :: state_hptw_resp :: state_addr_check :: state_mem_req :: state_mem_waiting :: state_mem_out :: state_last_hptw_req :: state_last_hptw_resp :: state_cache :: Nil = Enum(10)
  val state = RegInit(VecInit(Seq.fill(l2tlbParams.llptwsize)(state_idle)))

  val is_emptys = state.map(_ === state_idle)
  val is_mems = state.map(_ === state_mem_req)
  val is_waiting = state.map(_ === state_mem_waiting)
  val is_having = state.map(_ === state_mem_out)
  val is_cache = state.map(_ === state_cache)
  val is_hptw_req = state.map(_ === state_hptw_req)
  val is_last_hptw_req = state.map(_ === state_last_hptw_req)

  val full = !ParallelOR(is_emptys).asBool
  val enq_ptr = ParallelPriorityEncoder(is_emptys)

  val mem_ptr = ParallelPriorityEncoder(is_having) // TODO: optimize timing, bad: entries -> ptr -> entry
  val mem_arb = Module(new RRArbiter(new LLPTWEntry(), l2tlbParams.llptwsize))
  for (i <- 0 until l2tlbParams.llptwsize) {
    mem_arb.io.in(i).bits := entries(i)
    mem_arb.io.in(i).valid := is_mems(i) && !io.mem.req_mask(i)
  }
  val hyper_arb1 = Module(new RRArbiter(new LLPTWEntry(), l2tlbParams.llptwsize))
  for (i <- 0 until l2tlbParams.llptwsize) {
    hyper_arb1.io.in(i).bits := entries(i)
    hyper_arb1.io.in(i).valid := is_hptw_req(i)
  }
  val hyper_arb2 = Module(new RRArbiter(new LLPTWEntry(), l2tlbParams.llptwsize))
  for(i <- 0 until l2tlbParams.llptwsize) {
    hyper_arb2.io.in(i).bits := entries(i)
    hyper_arb2.io.in(i).valid := is_last_hptw_req(i)
  }

  val cache_ptr = ParallelMux(is_cache, (0 until l2tlbParams.llptwsize).map(_.U(log2Up(l2tlbParams.llptwsize).W)))

  // duplicate req
  // to_wait: wait for the last to access mem, set to mem_resp
  // to_cache: the last is back just right now, set to mem_cache
  val dup_vec = state.indices.map(i =>
    dup(io.in.bits.req_info.vpn, entries(i).req_info.vpn) && io.in.bits.req_info.hyperinst === entries(i).req_info.hyperinst
  )
  val dup_req_fire = mem_arb.io.out.fire && dup(io.in.bits.req_info.vpn, mem_arb.io.out.bits.req_info.vpn) && io.in.bits.req_info.hyperinst === entries(i).req_info.hyperinst // dup with the req fire entry
  val dup_vec_wait = dup_vec.zip(is_waiting).map{case (d, w) => d && w} // dup with "mem_waiting" entres, sending mem req already
  val dup_vec_having = dup_vec.zipWithIndex.map{case (d, i) => d && is_having(i)} // dup with the "mem_out" entry recv the data just now
  val wait_id = Mux(dup_req_fire, mem_arb.io.chosen, ParallelMux(dup_vec_wait zip entries.map(_.wait_id)))
  val dup_wait_resp = io.mem.resp.fire && VecInit(dup_vec_wait)(io.mem.resp.bits.id) // dup with the entry that data coming next cycle
  val to_wait = Cat(dup_vec_wait).orR || dup_req_fire
  val to_mem_out = dup_wait_resp
  val to_cache = Cat(dup_vec_having).orR
  XSError(RegNext(dup_req_fire && Cat(dup_vec_wait).orR, init = false.B), "mem req but some entries already waiting, should not happed")

  XSError(io.in.fire && ((to_mem_out && to_cache) || (to_wait && to_cache)), "llptw enq, to cache conflict with to mem")
  val mem_resp_hit = RegInit(VecInit(Seq.fill(l2tlbParams.llptwsize)(false.B)))
  val enq_state_normal = Mux(to_mem_out, state_mem_out, // same to the blew, but the mem resp now
    Mux(to_wait, state_mem_waiting,
    Mux(to_cache, state_cache, state_addr_check)))
  val enq_state = Mux(from_pre(io.in.bits.req_info.source) && enq_state_normal =/= state_addr_check, state_idle, enq_state_normal)
  when (io.in.fire) {
    // if prefetch req does not need mem access, just give it up.
    // so there will be at most 1 + FilterSize entries that needs re-access page cache
    // so 2 + FilterSize is enough to avoid dead-lock
    state(enq_ptr) := enq_state
    entries(enq_ptr).req_info := io.in.bits.req_info
    entries(enq_ptr).ppn := io.in.bits.ppn
    entries(enq_ptr).wait_id := Mux(to_wait, wait_id, enq_ptr)
    entries(enq_ptr).af := false.B
    entries(enq_ptr).gaf := false.B
    entries(enq_ptr).gpf := false.B
    entries(enq_ptr).s2xlate := enableS2xlate
    mem_resp_hit(enq_ptr) := to_mem_out
  }

  val enq_ptr_reg = RegNext(enq_ptr)
  val need_addr_check = RegNext(enq_state === state_addr_check && (io.in.fire() || io.hptw.resp.fire()) && !flush)

  val gpaddr = MakeGAddr(io.in.bits.ppn, getVpnn(io.in.bits.req_info.vpn, 0))
  val hpaddr = Cat(io.in.bits.ppn, gpaddr(offLen-1, 0))

  val addr = Mux(enableS2xlate, hpaddr, MakeAddr(io.in.bits.ppn, getVpnn(io.in.bits.req_info.vpn, 0)))

  io.pmp.req.valid := need_addr_check
  io.pmp.req.bits.addr := RegEnable(addr, io.in.fire)
  io.pmp.req.bits.cmd := TlbCmd.read
  io.pmp.req.bits.size := 3.U // TODO: fix it
  val pmp_resp_valid = io.pmp.req.valid // same cycle
  when (pmp_resp_valid) {
    // NOTE: when pmp resp but state is not addr check, then the entry is dup with other entry, the state was changed before
    //       when dup with the req-ing entry, set to mem_waiting (above codes), and the ld must be false, so dontcare
    val accessFault = io.pmp.resp.ld || io.pmp.resp.mmio
    entries(enq_ptr_reg).af := accessFault
    state(enq_ptr_reg) := Mux(accessFault, state_mem_out, state_mem_req)
  }

  when (mem_arb.io.out.fire) {
    for (i <- state.indices) {
      when (state(i) =/= state_idle && dup(entries(i).req_info.vpn, mem_arb.io.out.bits.req_info.vpn)) {
        // NOTE: "dup enq set state to mem_wait" -> "sending req set other dup entries to mem_wait"
        state(i) := state_mem_waiting
        entries(i).wait_id := mem_arb.io.chosen
      }
    }
  }
  when (io.mem.resp.fire) {
    state.indices.map{i =>
      when (state(i) === state_mem_waiting && io.mem.resp.bits.id === entries(i).wait_id) {
        state(i) := Mux(entries(i).s2xlate, state_last_hptw_req, state_mem_out)
        mem_resp_hit(i) := true.B
      }
    }
  }

  when (hyper_arb1.io.out.fire()) {
    for (i <- state.indices) {
      when (state(i) === state_hptw_req && entries(i).ppn === hyper_arb1.io.out.bits.ppn && entries(i).s2xlate) {
        state(i) := state_hptw_resp
        entries(i).wait_id := hyper_arb1.io.chosen
      }
    }
  }

  when (hyper_arb2.io.out.fire()) {
    for (i <- state.indices) {
      when (state(i) === state_last_hptw_req && entries(i).ppn === hyper_arb2.io.out.bits.ppn && entries(i).s2xlate) {
        state(i) := state_last_hptw_resp
        entries(i).wait_id := hyper_arb2.io.chosen
      }
    }
  }

  when (io.hptw.resp.fire()) {
    for (i <- state.indices) {
      when (state(i) === state_hptw_resp && io.hptw.resp.bits.id === entries(i).wait_id) {
        state(i) := state_addr_check
        entries(i).gpf := io.hptw.resp.bits.h_resp.gpf
        entries(i).gaf := io.hptw.resp.bits.h_resp.gaf
      }
      when (state(i) === state_last_hptw_resp && io.hptw.resp.bits.id === entries(i).wait_id) {
        state(i) := state_mem_out
        entries(i).gpf := io.hptw.resp.bits.h_resp.gpf
        entries(i).gaf := io.hptw.resp.bits.h_resp.gaf
      }
    }
  }

  when (io.out.fire) {
    assert(state(mem_ptr) === state_mem_out)
    state(mem_ptr) := state_idle
  }
  mem_resp_hit.map(a => when (a) { a := false.B } )

  when (io.cache.fire) {
    state(cache_ptr) := state_idle
  }
  XSError(io.out.fire && io.cache.fire && (mem_ptr === cache_ptr), "mem resp and cache fire at the same time at same entry")

  when (flush) {
    state.map(_ := state_idle)
  }

  io.in.ready := !full

  io.out.valid := ParallelOR(is_having).asBool
  io.out.bits.req_info := entries(mem_ptr).req_info
  io.out.bits.id := mem_ptr
  io.out.bits.af := entries(mem_ptr).af
  io.out.bits.h_resp := io.hptw.resp.bits.h_resp

  io.hptw.req.valid := (hyper_arb1.io.out.valid || hyper_arb2.io.out.valid) && !flush
  io.hptw.req.bits.gvpn := Mux(hyper_arb1.io.out.valid, hyper_arb1.io.out.bits.ppn, hyper_arb2.io.out.bits.ppn)
  io.hptw.req.bits.id := Mux(hyper_arb1.io.out.valid, hyper_arb1.io.chosen, hyper_arb2.io.chosen)
  hyper_arb1.io.out.ready := io.hptw.req.ready
  hyper_arb2.io.out.ready := io.hptw.req.ready

  io.mem.req.valid := mem_arb.io.out.valid && !flush
  io.mem.req.bits.addr := MakeAddr(mem_arb.io.out.bits.ppn, getVpnn(mem_arb.io.out.bits.req_info.vpn, 0))
  io.mem.req.bits.id := mem_arb.io.chosen
  mem_arb.io.out.ready := io.mem.req.ready
  io.mem.refill := entries(RegNext(io.mem.resp.bits.id(log2Up(l2tlbParams.llptwsize)-1, 0))).req_info
  io.mem.buffer_it := mem_resp_hit
  io.mem.enq_ptr := enq_ptr

  io.cache.valid := Cat(is_cache).orR
  io.cache.bits := ParallelMux(is_cache, entries.map(_.req_info))

  XSPerfAccumulate("llptw_in_count", io.in.fire)
  XSPerfAccumulate("llptw_in_block", io.in.valid && !io.in.ready)
  for (i <- 0 until 7) {
    XSPerfAccumulate(s"enq_state${i}", io.in.fire && enq_state === i.U)
  }
  for (i <- 0 until (l2tlbParams.llptwsize + 1)) {
    XSPerfAccumulate(s"util${i}", PopCount(is_emptys.map(!_)) === i.U)
    XSPerfAccumulate(s"mem_util${i}", PopCount(is_mems) === i.U)
    XSPerfAccumulate(s"waiting_util${i}", PopCount(is_waiting) === i.U)
  }
  XSPerfAccumulate("mem_count", io.mem.req.fire)
  XSPerfAccumulate("mem_cycle", PopCount(is_waiting) =/= 0.U)
  XSPerfAccumulate("blocked_in", io.in.valid && !io.in.ready)

  for (i <- 0 until l2tlbParams.llptwsize) {
    TimeOutAssert(state(i) =/= state_idle, timeOutThreshold, s"missqueue time out no out ${i}")
  }

  val perfEvents = Seq(
    ("tlbllptw_incount           ", io.in.fire               ),
    ("tlbllptw_inblock           ", io.in.valid && !io.in.ready),
    ("tlbllptw_memcount          ", io.mem.req.fire          ),
    ("tlbllptw_memcycle          ", PopCount(is_waiting)       ),
  )
  generatePerfEvent()
}

/*========================= HPTW ==============================*/

/** HPTW : Hypervisor Page Table Walker
  * the page walker take the virtual machine's page walk.
  * guest physical address translation, guest physical address -> host physical address
  **/
class HPTWIO()(implicit p: Parameters) extends MMUIOBaseBundle with HasPtwConst {
  val req = Flipped(DecoupledIO(new Bundle {
    val id = UInt(log2Up(l2tlbParams.llptwsize).W)
    val gvpn = UInt(vpnLen.W)
    val l1Hit = Bool()
    val l2Hit = Bool()
    val ppn = UInt(ppnLen.W)
  }))
  val resp = Valid(new Bundle {
    val resp = Output(new HptwResp())
    val id = Output(UInt(bMemID.W))
  })

  val mem = new Bundle {
    val req = DecoupledIO(new L2TlbMemReqBundle())
    val resp = Flipped(ValidIO(UInt(XLEN.W)))
    val mask = Input(Bool())
  }
  val refill = Output(new Bundle {
    val req_info = new L2TlbInnerBundle()
    val level = UInt(log2Up(Level).W)
  })
  val pmp = new Bundle {
    val req = ValidIO(new PMPReqBundle())
    val resp = Flipped(new PMPRespBundle())
  }
}

@chiselName
class HPTW()(implicit p: Parameters) extends XSModule with HasPtwConst {
  val io = IO(new HPTWIO)
  val hgatp = io.csr.hgatp
  val sfence = io.sfence
  val flush = sfence.valid || hgatp.changed

  val level = RegInit(0.U(log2Up(Level).W))
  val gpaddr = Reg(UInt(GPAddrBits.W))
  val vpn = gpaddr(GPAddrBits-1, offLen)
  val levelNext = level + 1.U
  val l1Hit = Reg(Bool())
  val l2Hit = Reg(Bool())
  val ppn = Reg(UInt(ppnLen.W))
  val pg_base = MakeAddr(hgatp.ppn, getGVpnn(vpn, 2.U))
//  val pte = io.mem.resp.bits.MergeRespToPte()
  val pte = io.mem.resp.bits.asTypeOf(new PteBundle().cloneType)
  val p_pte = MakeAddr(ppn, getVpnn(vpn, 2.U - level))
  val mem_addr = Mux(level === 0.U, pg_base, p_pte)

  //s/w register
  val s_pmp_check = RegInit(true.B)
  val s_mem_req = RegInit(true.B)
  val w_mem_resp = RegInit(true.B)
  val mem_addr_update = RegInit(true.B)
  val idle = RegInit(true.B)
  val finish = WireInit(false.B)

  val sent_to_pmp = !idle && (!s_pmp_check || mem_addr_update) && !finish
  val pageFault = pte.isPf(level)
  val accessFault = RegEnable(io.pmp.resp.ld || io.pmp.resp.mmio, sent_to_pmp)

  val ppn_af = pte.isAf()
  val find_pte = pte.isLeaf() || ppn_af || pageFault

  val resp_valid = !idle && mem_addr_update && ((w_mem_resp && find_pte) || (s_pmp_check && accessFault))
  val id = Reg(UInt(log2Up(l2tlbParams.llptwsize).W))
  io.req.ready := idle
  val resp = new HptwResp()
  resp.apply(pageFault && !accessFault && !ppn_af, accessFault || ppn_af, level, pte, vpn, hgatp.asid)
  io.resp.valid := resp_valid
  io.resp.bits.id := id
  io.resp.bits.resp := resp

  io.pmp.req.valid := DontCare
  io.pmp.req.bits.addr := mem_addr
  io.pmp.req.bits.size := 3.U
  io.pmp.req.bits.cmd := TlbCmd.read

  io.mem.req.valid := !s_mem_req && !io.mem.mask && !accessFault && s_pmp_check
  io.mem.req.bits.addr := mem_addr
  io.mem.req.bits.id := HptwReqId.U(bMemID.W)

  io.refill.req_info.vpn := vpn
  io.refill.level := level
  when (idle){
    when(io.req.fire()){
      level := Mux(io.req.bits.l2Hit, 2.U, Mux(io.req.bits.l1Hit, 1.U, 0.U))
      idle := false.B
      gpaddr := Cat(io.req.bits.gvpn, 0.U(offLen.W))
      accessFault := false.B
      s_pmp_check := false.B
      id := io.req.bits.id
      l1Hit := io.req.bits.l1Hit
      l2Hit := io.req.bits.l2Hit
      ppn := io.req.bits.ppn
    }
  }

  when(sent_to_pmp && !mem_addr_update){
    s_mem_req := false.B
    s_pmp_check := true.B
  }

  when(accessFault && !idle){
    s_pmp_check := true.B
    s_mem_req := true.B
    w_mem_resp := true.B
    mem_addr_update := true.B
  }

  when(io.mem.req.fire()){
    s_mem_req := true.B
    w_mem_resp := false.B
  }

  when(io.mem.resp.fire() && !w_mem_resp){
    ppn := pte.ppn
    w_mem_resp := true.B
    mem_addr_update := true.B
  }

  when(mem_addr_update){
    when(!(find_pte || accessFault)){
      level := levelNext
      s_mem_req := false.B
      mem_addr_update := false.B
    }.elsewhen(resp_valid){
      when(io.resp.fire()){
        idle := true.B
        mem_addr_update := false.B
        accessFault := false.B
      }
      finish := true.B
    }
  }
}