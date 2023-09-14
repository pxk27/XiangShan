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

/* ptw cache caches the page table of all the three layers
 * ptw cache resp at next cycle
 * the cache should not be blocked
 * when miss queue if full, just block req outside
 */

class PageCachePerPespBundle(implicit p: Parameters) extends PtwBundle {
  val hit = Bool()
  val pre = Bool()
  val ppn = if (HasHExtension) UInt((vpnLen max ppnLen).W) else UInt(ppnLen.W)
  val perm = new PtePermBundle()
  val ecc = Bool()
  val level = UInt(2.W)
  val v = Bool()

  def apply(hit: Bool, pre: Bool, ppn: UInt, perm: PtePermBundle = 0.U.asTypeOf(new PtePermBundle()),
            ecc: Bool = false.B, level: UInt = 0.U, valid: Bool = true.B) {
    this.hit := hit && !ecc
    this.pre := pre
    this.ppn := ppn
    this.perm := perm
    this.ecc := ecc && hit
    this.level := level
    this.v := valid
  }
}

class PageCacheMergePespBundle(implicit p: Parameters) extends PtwBundle {
  assert(tlbcontiguous == 8, "Only support tlbcontiguous = 8!")
  val hit = Bool()
  val pre = Bool()
  val ppn = Vec(tlbcontiguous, if(HasHExtension) UInt((vpnLen max ppnLen).W) else UInt(ppnLen.W))
  val perm = Vec(tlbcontiguous, new PtePermBundle())
  val ecc = Bool()
  val level = UInt(2.W)
  val v = Vec(tlbcontiguous, Bool())

  def apply(hit: Bool, pre: Bool, ppn: Vec[UInt], perm: Vec[PtePermBundle] = Vec(tlbcontiguous, 0.U.asTypeOf(new PtePermBundle())),
            ecc: Bool = false.B, level: UInt = 0.U, valid: Vec[Bool] = Vec(tlbcontiguous, true.B)) {
    this.hit := hit && !ecc
    this.pre := pre
    this.ppn := ppn
    this.perm := perm
    this.ecc := ecc && hit
    this.level := level
    this.v := valid
  }
}

class PageCacheRespBundle(implicit p: Parameters) extends PtwBundle {
  val l1 = new PageCachePerPespBundle
  val l2 = new PageCachePerPespBundle
  val l3 = new PageCacheMergePespBundle
  val sp = new PageCachePerPespBundle
}

class PtwCacheReq(implicit p: Parameters) extends PtwBundle {
  val req_info = new L2TlbInnerBundle()
  val isFirst = Bool()
  val bypassed = Vec(3, Bool())
  val isHptw = Bool()
  val hptwId = UInt(log2Up(l2tlbParams.llptwsize).W)
}

class PtwCacheIO()(implicit p: Parameters) extends MMUIOBaseBundle with HasPtwConst {
  val req = Flipped(DecoupledIO(new PtwCacheReq()))
  val resp = DecoupledIO(new Bundle {
    val req_info = new L2TlbInnerBundle()
    val isFirst = Bool()
    val hit = Bool()
    val prefetch = Bool() // is the entry fetched by prefetch
    val bypassed = Bool()
    val toFsm = new Bundle {
      val l1Hit = Bool()
      val l2Hit = Bool()
      val ppn = if(HasHExtension) UInt((vpnLen.max(ppnLen)).W) else UInt(ppnLen.W)
    }
    val toTlb = new PtwMergeResp()
    val isHptw = Bool()
    val toHptw = new Bundle {
      val l1Hit = Bool()
      val l2Hit = Bool()
      val ppn = UInt(ppnLen.W)
      val id = UInt(log2Up(l2tlbParams.llptwsize).W)
      val resp = new HptwResp() // used if hit
    }
  })
  val refill = Flipped(ValidIO(new Bundle {
    val ptes = UInt(blockBits.W)
    val levelOH = new Bundle {
      // NOTE: levelOH has (Level+1) bits, each stands for page cache entries
      val sp = Bool()
      val l3 = Bool()
      val l2 = Bool()
      val l1 = Bool()
      def apply(levelUInt: UInt, valid: Bool) = {
        sp := RegNext((levelUInt === 0.U || levelUInt === 1.U) && valid, false.B)
        l3 := RegNext((levelUInt === 2.U) & valid, false.B)
        l2 := RegNext((levelUInt === 1.U) & valid, false.B)
        l1 := RegNext((levelUInt === 0.U) & valid, false.B)
      }
    }
    // duplicate level and sel_pte for each page caches, for better fanout
    val req_info_dup = Vec(3, new L2TlbInnerBundle())
    val level_dup = Vec(3, UInt(log2Up(Level).W))
    val sel_pte_dup = Vec(3, UInt(XLEN.W))
  }))
  val sfence_dup = Vec(4, Input(new SfenceBundle()))
  val csr_dup = Vec(3, Input(new TlbCsrBundle()))
}

class PtwCache()(implicit p: Parameters) extends XSModule with HasPtwConst with HasPerfEvents {
  val io = IO(new PtwCacheIO)
  val ecc = Code.fromString(l2tlbParams.ecc)
  val l2EntryType = new PTWEntriesWithEcc(ecc, num = PtwL2SectorSize, tagLen = PtwL2TagLen, level = 1, hasPerm = false)
  val l3EntryType = new PTWEntriesWithEcc(ecc, num = PtwL3SectorSize, tagLen = PtwL3TagLen, level = 2, hasPerm = true)

  // TODO: four caches make the codes dirty, think about how to deal with it

  val sfence_dup = io.sfence_dup
  val refill = io.refill.bits
  val refill_prefetch_dup = io.refill.bits.req_info_dup.map(a => from_pre(a.source))
  val flush_dup = sfence_dup.zip(io.csr_dup).map(f => f._1.valid || f._2.satp.changed || f._2.vsatp.changed || f._2.hgatp.changed)
  val flush = flush_dup(0)

  // when refill, refuce to accept new req
  val rwHarzad = if (sramSinglePort) io.refill.valid else false.B

  // handle hand signal and req_info
  // TODO: replace with FlushableQueue
  val stageReq = Wire(Decoupled(new PtwCacheReq()))         // enq stage & read page cache valid
  val stageDelay = Wire(Vec(2, Decoupled(new PtwCacheReq()))) // page cache resp
  val stageCheck = Wire(Vec(2, Decoupled(new PtwCacheReq()))) // check hit & check ecc
  val stageResp = Wire(Decoupled(new PtwCacheReq()))         // deq stage

  val stageDelay_valid_1cycle = OneCycleValid(stageReq.fire, flush)      // catch ram data
  val stageCheck_valid_1cycle = OneCycleValid(stageDelay(1).fire, flush) // replace & perf counter
  val stageResp_valid_1cycle_dup = Wire(Vec(2, Bool()))
  stageResp_valid_1cycle_dup.map(_ := OneCycleValid(stageCheck(1).fire, flush))  // ecc flush

  stageReq <> io.req
  PipelineConnect(stageReq, stageDelay(0), stageDelay(1).ready, flush, rwHarzad)
  InsideStageConnect(stageDelay(0), stageDelay(1), stageDelay_valid_1cycle)
  PipelineConnect(stageDelay(1), stageCheck(0), stageCheck(1).ready, flush)
  InsideStageConnect(stageCheck(0), stageCheck(1), stageCheck_valid_1cycle)
  PipelineConnect(stageCheck(1), stageResp, io.resp.ready, flush)
  stageResp.ready := !stageResp.valid || io.resp.ready

  // l1: level 0 non-leaf pte
  val l1 = Reg(Vec(l2tlbParams.l1Size, new PtwEntry(tagLen = PtwL1TagLen)))
  val l1v = RegInit(0.U(l2tlbParams.l1Size.W))
  val l1g = Reg(UInt(l2tlbParams.l1Size.W))
  val l1asids = l1.map(_.asid)
  val l1vmids = l1.map(_.vmid)
  val l1h = Reg(Vec(l2tlbParams.l1Size, UInt(2.W))) // 0 bit: s2xlate, 1 bit: stage 1 or stage 2

  // l2: level 1 non-leaf pte
  val l2 = Module(new SRAMTemplate(
    l2EntryType,
    set = l2tlbParams.l2nSets,
    way = l2tlbParams.l2nWays,
    singlePort = sramSinglePort
  ))
  val l2v = RegInit(0.U((l2tlbParams.l2nSets * l2tlbParams.l2nWays).W))
  val l2g = Reg(UInt((l2tlbParams.l2nSets * l2tlbParams.l2nWays).W))
  val l2h = Reg(Vec(l2tlbParams.l2nSets, Vec(l2tlbParams.l2nWays, UInt(2.W))))
  def getl2vSet(vpn: UInt) = {
    require(log2Up(l2tlbParams.l2nWays) == log2Down(l2tlbParams.l2nWays))
    val set = genPtwL2SetIdx(vpn)
    require(set.getWidth == log2Up(l2tlbParams.l2nSets))
    val l2vVec = l2v.asTypeOf(Vec(l2tlbParams.l2nSets, UInt(l2tlbParams.l2nWays.W)))
    l2vVec(set)
  }
  def getl2hSet(vpn: UInt) = {
    require(log2Up(l2tlbParams.l2nWays) == log2Down(l2tlbParams.l2nWays))
    val set = genPtwL2SetIdx(vpn)
    require(set.getWidth == log2Up(l2tlbParams.l2nSets))
    l2h(set)
  }



  // l3: level 2 leaf pte of 4KB pages
  val l3 = Module(new SRAMTemplate(
    l3EntryType,
    set = l2tlbParams.l3nSets,
    way = l2tlbParams.l3nWays,
    singlePort = sramSinglePort
  ))
  val l3v = RegInit(0.U((l2tlbParams.l3nSets * l2tlbParams.l3nWays).W))
  val l3g = Reg(UInt((l2tlbParams.l3nSets * l2tlbParams.l3nWays).W))
  val l3h = Reg(Vec(l2tlbParams.l3nSets, Vec(l2tlbParams.l3nWays, UInt(2.W))))
  def getl3vSet(vpn: UInt) = {
    require(log2Up(l2tlbParams.l3nWays) == log2Down(l2tlbParams.l3nWays))
    val set = genPtwL3SetIdx(vpn)
    require(set.getWidth == log2Up(l2tlbParams.l3nSets))
    val l3vVec = l3v.asTypeOf(Vec(l2tlbParams.l3nSets, UInt(l2tlbParams.l3nWays.W)))
    l3vVec(set)
  }
  def getl3hSet(vpn: UInt) = {
    require(log2Up(l2tlbParams.l3nWays) == log2Down(l2tlbParams.l3nWays))
    val set = genPtwL3SetIdx(vpn)
    require(set.getWidth == log2Up(l2tlbParams.l3nSets))
    l3h(set)
  }

  // sp: level 0/1 leaf pte of 1GB/2MB super pages
  val sp = Reg(Vec(l2tlbParams.spSize, new PtwEntry(tagLen = SPTagLen, hasPerm = true, hasLevel = true)))
  val spv = RegInit(0.U(l2tlbParams.spSize.W))
  val spg = Reg(UInt(l2tlbParams.spSize.W))
  val spasids = sp.map(_.asid)
  val spvmids = sp.map(_.vmid)
  val sph = Reg(Vec(l2tlbParams.spSize, UInt(2.W)))

  // Access Perf
  val l1AccessPerf = Wire(Vec(l2tlbParams.l1Size, Bool()))
  val l2AccessPerf = Wire(Vec(l2tlbParams.l2nWays, Bool()))
  val l3AccessPerf = Wire(Vec(l2tlbParams.l3nWays, Bool()))
  val spAccessPerf = Wire(Vec(l2tlbParams.spSize, Bool()))
  l1AccessPerf.map(_ := false.B)
  l2AccessPerf.map(_ := false.B)
  l3AccessPerf.map(_ := false.B)
  spAccessPerf.map(_ := false.B)



  def vpn_match(vpn1: UInt, vpn2: UInt, level: Int) = {
    (vpn1(vpnLen-1, vpnnLen*(2-level)+3) === vpn2(vpnLen-1, vpnnLen*(2-level)+3))
  }
  // NOTE: not actually bypassed, just check if hit, re-access the page cache
  def refill_bypass(vpn: UInt, level: Int, h_search: UInt) = {
    val refill_vpn = io.refill.bits.req_info_dup(0).vpn
    io.refill.valid && (level.U === io.refill.bits.level_dup(0)) && vpn_match(refill_vpn, vpn, level) && h_search === io.refill.bits.req_info_dup(0).s2xlate
  }

  val vpn_search = stageReq.bits.req_info.vpn
  val h_search = stageReq.bits.req_info.s2xlate
  // l1
  val ptwl1replace = ReplacementPolicy.fromString(l2tlbParams.l1Replacer, l2tlbParams.l1Size)
  val (l1Hit, l1HitPPN, l1Pre) = {
    val hitVecT = l1.zipWithIndex.map {
      case (e, i) => (e.hit(vpn_search, io.csr_dup(0).satp.asid, io.csr_dup(0).hgatp.asid, s2xlate = h_search =/= noS2xlate)
        && l1v(i) && h_search === l1h(i))
    }
    val hitVec = hitVecT.map(RegEnable(_, stageReq.fire))

    // stageDelay, but check for l1
    val hitPPN = DataHoldBypass(ParallelPriorityMux(hitVec zip l1.map(_.ppn)), stageDelay_valid_1cycle)
    val hitPre = DataHoldBypass(ParallelPriorityMux(hitVec zip l1.map(_.prefetch)), stageDelay_valid_1cycle)
    val hit = DataHoldBypass(ParallelOR(hitVec), stageDelay_valid_1cycle)

    when (hit && stageDelay_valid_1cycle) { ptwl1replace.access(OHToUInt(hitVec)) }

    l1AccessPerf.zip(hitVec).map{ case (l, h) => l := h && stageDelay_valid_1cycle}
    for (i <- 0 until l2tlbParams.l1Size) {
      XSDebug(stageReq.fire, p"[l1] l1(${i.U}) ${l1(i)} hit:${l1(i).hit(vpn_search, Mux(h_search =/= noS2xlate, io.csr_dup(0).vsatp.asid, io.csr_dup(0).satp.asid), io.csr_dup(0).hgatp.asid, s2xlate = h_search =/= noS2xlate)}\n")
    }
    XSDebug(stageReq.fire, p"[l1] l1v:${Binary(l1v)} hitVecT:${Binary(VecInit(hitVecT).asUInt)}\n")
    XSDebug(stageDelay(0).valid, p"[l1] l1Hit:${hit} l1HitPPN:0x${Hexadecimal(hitPPN)} hitVec:${VecInit(hitVec).asUInt}\n")

    VecInit(hitVecT).suggestName(s"l1_hitVecT")
    VecInit(hitVec).suggestName(s"l1_hitVec")

    // synchronize with other entries with RegEnable
    (RegEnable(hit, stageDelay(1).fire),
     RegEnable(hitPPN, stageDelay(1).fire),
     RegEnable(hitPre, stageDelay(1).fire))
  }

  // l2
  val ptwl2replace = ReplacementPolicy.fromString(l2tlbParams.l2Replacer,l2tlbParams.l2nWays,l2tlbParams.l2nSets)
  val (l2Hit, l2HitPPN, l2Pre, l2eccError) = {
    val ridx = genPtwL2SetIdx(vpn_search)
    l2.io.r.req.valid := stageReq.fire
    l2.io.r.req.bits.apply(setIdx = ridx)
    val vVec_req = getl2vSet(vpn_search)
    val hVec_req = getl2hSet(vpn_search)

    // delay one cycle after sram read
    val delay_vpn = stageDelay(0).bits.req_info.vpn
    val delay_h = stageDelay(0).bits.req_info.s2xlate
    val data_resp = DataHoldBypass(l2.io.r.resp.data, stageDelay_valid_1cycle)
    val vVec_delay = RegEnable(vVec_req, stageReq.fire)
    val hVec_delay = RegEnable(hVec_req, stageReq.fire)
    val hitVec_delay = VecInit(data_resp.zip(vVec_delay.asBools).zip(hVec_delay).map { case ((wayData, v), h) =>
      wayData.entries.hit(delay_vpn, io.csr_dup(1).satp.asid, io.csr_dup(1).hgatp.asid, s2xlate = delay_h =/= noS2xlate) && v && (delay_h === h)})

    // check hit and ecc
    val check_vpn = stageCheck(0).bits.req_info.vpn
    val ramDatas = RegEnable(data_resp, stageDelay(1).fire)
    val vVec = RegEnable(vVec_delay, stageDelay(1).fire).asBools

    val hitVec = RegEnable(hitVec_delay, stageDelay(1).fire)
    val hitWayEntry = ParallelPriorityMux(hitVec zip ramDatas)
    val hitWayData = hitWayEntry.entries
    val hit = ParallelOR(hitVec)
    val hitWay = ParallelPriorityMux(hitVec zip (0 until l2tlbParams.l2nWays).map(_.U(log2Up(l2tlbParams.l2nWays).W)))
    val eccError = hitWayEntry.decode()

    ridx.suggestName(s"l2_ridx")
    ramDatas.suggestName(s"l2_ramDatas")
    hitVec.suggestName(s"l2_hitVec")
    hitWayData.suggestName(s"l2_hitWayData")
    hitWay.suggestName(s"l2_hitWay")

    when (hit && stageCheck_valid_1cycle) { ptwl2replace.access(genPtwL2SetIdx(check_vpn), hitWay) }

    l2AccessPerf.zip(hitVec).map{ case (l, h) => l := h && stageCheck_valid_1cycle }
    XSDebug(stageDelay_valid_1cycle, p"[l2] ridx:0x${Hexadecimal(ridx)}\n")
    for (i <- 0 until l2tlbParams.l2nWays) {
      XSDebug(stageCheck_valid_1cycle, p"[l2] ramDatas(${i.U}) ${ramDatas(i)}  l2v:${vVec(i)}  hit:${hit}\n")
    }
    XSDebug(stageCheck_valid_1cycle, p"[l2] l2Hit:${hit} l2HitPPN:0x${Hexadecimal(hitWayData.ppns(genPtwL2SectorIdx(check_vpn)))} hitVec:${Binary(hitVec.asUInt)} hitWay:${hitWay} vidx:${vVec}\n")

    (hit, hitWayData.ppns(genPtwL2SectorIdx(check_vpn)), hitWayData.prefetch, eccError)
  }

  // l3
  val ptwl3replace = ReplacementPolicy.fromString(l2tlbParams.l3Replacer,l2tlbParams.l3nWays,l2tlbParams.l3nSets)
  val (l3Hit, l3HitData, l3Pre, l3eccError) = {
    val ridx = genPtwL3SetIdx(vpn_search)
    l3.io.r.req.valid := stageReq.fire
    l3.io.r.req.bits.apply(setIdx = ridx)
    val vVec_req = getl3vSet(vpn_search)
    val hVec_req = getl3hSet(vpn_search)

    // delay one cycle after sram read
    val delay_vpn = stageDelay(0).bits.req_info.vpn
    val delay_h = stageDelay(0).bits.req_info.s2xlate
    val data_resp = DataHoldBypass(l3.io.r.resp.data, stageDelay_valid_1cycle)
    val vVec_delay = RegEnable(vVec_req, stageReq.fire)
    val hVec_delay = RegEnable(hVec_req, stageReq.fire)
    val hitVec_delay = VecInit(data_resp.zip(vVec_delay.asBools).zip(hVec_delay).map { case ((wayData, v), h) =>
      wayData.entries.hit(delay_vpn, io.csr_dup(2).satp.asid, io.csr_dup(2).hgatp.asid, s2xlate = delay_h =/= noS2xlate) && v && (delay_h === h)})

    // check hit and ecc
    val check_vpn = stageCheck(0).bits.req_info.vpn
    val ramDatas = RegEnable(data_resp, stageDelay(1).fire)
    val vVec = RegEnable(vVec_delay, stageDelay(1).fire).asBools

    val hitVec = RegEnable(hitVec_delay, stageDelay(1).fire)
    val hitWayEntry = ParallelPriorityMux(hitVec zip ramDatas)
    val hitWayData = hitWayEntry.entries
    val hitWayEcc = hitWayEntry.ecc
    val hit = ParallelOR(hitVec)
    val hitWay = ParallelPriorityMux(hitVec zip (0 until l2tlbParams.l3nWays).map(_.U(log2Up(l2tlbParams.l3nWays).W)))
    val eccError = hitWayEntry.decode()

    when (hit && stageCheck_valid_1cycle) { ptwl3replace.access(genPtwL3SetIdx(check_vpn), hitWay) }

    l3AccessPerf.zip(hitVec).map{ case (l, h) => l := h && stageCheck_valid_1cycle }
    XSDebug(stageReq.fire, p"[l3] ridx:0x${Hexadecimal(ridx)}\n")
    for (i <- 0 until l2tlbParams.l3nWays) {
      XSDebug(stageCheck_valid_1cycle, p"[l3] ramDatas(${i.U}) ${ramDatas(i)}  l3v:${vVec(i)}  hit:${hitVec(i)}\n")
    }
    XSDebug(stageCheck_valid_1cycle, p"[l3] l3Hit:${hit} l3HitData:${hitWayData} hitVec:${Binary(hitVec.asUInt)} hitWay:${hitWay} v:${vVec}\n")

    ridx.suggestName(s"l3_ridx")
    ramDatas.suggestName(s"l3_ramDatas")
    hitVec.suggestName(s"l3_hitVec")
    hitWay.suggestName(s"l3_hitWay")

    (hit, hitWayData, hitWayData.prefetch, eccError)
  }
  val l3HitPPN = l3HitData.ppns
  val l3HitPerm = l3HitData.perms.getOrElse(0.U.asTypeOf(Vec(PtwL3SectorSize, new PtePermBundle)))
  val l3HitValid = l3HitData.vs

  // super page
  val spreplace = ReplacementPolicy.fromString(l2tlbParams.spReplacer, l2tlbParams.spSize)
  val (spHit, spHitData, spPre, spValid) = {
    val hitVecT = sp.zipWithIndex.map { case (e, i) => e.hit(vpn_search, Mux(h_search =/= noS2xlate, io.csr_dup(0).vsatp.asid, io.csr_dup(0).satp.asid), io.csr_dup(0).hgatp.asid, s2xlate = h_search =/= noS2xlate) && spv(i) && (sph(i) === h_search) }
    val hitVec = hitVecT.map(RegEnable(_, stageReq.fire))
    val hitData = ParallelPriorityMux(hitVec zip sp)
    val hit = ParallelOR(hitVec)

    when (hit && stageDelay_valid_1cycle) { spreplace.access(OHToUInt(hitVec)) }

    spAccessPerf.zip(hitVec).map{ case (s, h) => s := h && stageDelay_valid_1cycle }
    for (i <- 0 until l2tlbParams.spSize) {
      XSDebug(stageReq.fire, p"[sp] sp(${i.U}) ${sp(i)} hit:${sp(i).hit(vpn_search, Mux(h_search =/= noS2xlate, io.csr_dup(0).vsatp.asid, io.csr_dup(0).satp.asid), io.csr_dup(0).hgatp.asid, s2xlate = h_search =/= noS2xlate)} spv:${spv(i)}\n")
    }
    XSDebug(stageDelay_valid_1cycle, p"[sp] spHit:${hit} spHitData:${hitData} hitVec:${Binary(VecInit(hitVec).asUInt)}\n")

    VecInit(hitVecT).suggestName(s"sp_hitVecT")
    VecInit(hitVec).suggestName(s"sp_hitVec")

    (RegEnable(hit, stageDelay(1).fire),
     RegEnable(hitData, stageDelay(1).fire),
     RegEnable(hitData.prefetch, stageDelay(1).fire),
     RegEnable(hitData.v, stageDelay(1).fire))
  }
  val spHitPerm = spHitData.perm.getOrElse(0.U.asTypeOf(new PtePermBundle))
  val spHitLevel = spHitData.level.getOrElse(0.U)

  val check_res = Wire(new PageCacheRespBundle)
  check_res.l1.apply(l1Hit, l1Pre, l1HitPPN)
  check_res.l2.apply(l2Hit, l2Pre, l2HitPPN, ecc = l2eccError)
  check_res.l3.apply(l3Hit, l3Pre, l3HitPPN, l3HitPerm, l3eccError, valid = l3HitValid)
  check_res.sp.apply(spHit, spPre, spHitData.ppn, spHitPerm, false.B, spHitLevel, spValid)

  val resp_res = Reg(new PageCacheRespBundle)
  when (stageCheck(1).fire) { resp_res := check_res }

  // stageResp bypass
  val bypassed = Wire(Vec(3, Bool()))
  bypassed.indices.foreach(i =>
    bypassed(i) := stageResp.bits.bypassed(i) ||
      ValidHoldBypass(refill_bypass(vpn_search, i, h_search),
        OneCycleValid(stageCheck(1).fire, false.B) || io.refill.valid)
  )

  io.resp.bits.req_info   := stageResp.bits.req_info
  io.resp.bits.isFirst  := stageResp.bits.isFirst
  io.resp.bits.hit      := resp_res.l3.hit || resp_res.sp.hit
  io.resp.bits.bypassed := bypassed(2) || (bypassed(1) && !resp_res.l2.hit) || (bypassed(0) && !resp_res.l1.hit)
  io.resp.bits.prefetch := resp_res.l3.pre && resp_res.l3.hit || resp_res.sp.pre && resp_res.sp.hit
  io.resp.bits.toFsm.l1Hit := resp_res.l1.hit
  io.resp.bits.toFsm.l2Hit := resp_res.l2.hit
  io.resp.bits.toFsm.ppn   := Mux(resp_res.l2.hit, resp_res.l2.ppn, resp_res.l1.ppn)

  io.resp.bits.isHptw := stageResp.bits.isHptw
  io.resp.bits.toHptw.id := stageResp.bits.hptwId
  io.resp.bits.toHptw.l1Hit := resp_res.l1.hit
  io.resp.bits.toHptw.l2Hit := resp_res.l2.hit
  io.resp.bits.toHptw.ppn := Mux(resp_res.l2.hit, resp_res.l2.ppn, resp_res.l1.ppn)
  val idx = stageResp.bits.req_info.vpn(2, 0)
  io.resp.bits.toHptw.resp.entry.tag := stageResp.bits.req_info.vpn
  io.resp.bits.toHptw.resp.entry.asid := DontCare
  io.resp.bits.toHptw.resp.entry.vmid.map(_ := io.csr_dup(0).hgatp.asid)
  io.resp.bits.toHptw.resp.entry.level.map(_ := Mux(resp_res.l3.hit, 2.U, resp_res.sp.level))
  io.resp.bits.toHptw.resp.entry.prefetch := from_pre(stageResp.bits.req_info.source)
  io.resp.bits.toHptw.resp.entry.ppn := Mux(resp_res.l3.hit, resp_res.l3.ppn(idx), resp_res.sp.ppn)
  io.resp.bits.toHptw.resp.entry.perm.map(_ := Mux(resp_res.l3.hit, resp_res.l3.perm(idx), resp_res.sp.perm))
  io.resp.bits.toHptw.resp.entry.v := Mux(resp_res.l3.hit, resp_res.l3.v(idx), resp_res.sp.v)
  io.resp.bits.toHptw.resp.gpf := !io.resp.bits.toHptw.resp.entry.v
  io.resp.bits.toHptw.resp.gaf := false.B

  io.resp.bits.toTlb.entry.map(_.tag := stageResp.bits.req_info.vpn(vpnLen - 1, 3))
  io.resp.bits.toTlb.entry.map(_.asid := Mux(stageResp.bits.req_info.hasS2xlate(), io.csr_dup(0).vsatp.asid, io.csr_dup(0).satp.asid)) // DontCare
  io.resp.bits.toTlb.entry.map(_.vmid.map(_ := io.csr_dup(0).hgatp.asid))
  io.resp.bits.toTlb.entry.map(_.level.map(_ := Mux(resp_res.l3.hit, 2.U, resp_res.sp.level)))
  io.resp.bits.toTlb.entry.map(_.prefetch := from_pre(stageResp.bits.req_info.source))
  for (i <- 0 until tlbcontiguous) {
    io.resp.bits.toTlb.entry(i).ppn := Mux(resp_res.l3.hit, resp_res.l3.ppn(i)(ppnLen - 1, sectortlbwidth), resp_res.sp.ppn(ppnLen - 1, sectortlbwidth))
    io.resp.bits.toTlb.entry(i).ppn_low := Mux(resp_res.l3.hit, resp_res.l3.ppn(i)(sectortlbwidth - 1, 0), resp_res.sp.ppn(sectortlbwidth - 1, 0))
    io.resp.bits.toTlb.entry(i).perm.map(_ := Mux(resp_res.l3.hit, resp_res.l3.perm(i), resp_res.sp.perm))
    io.resp.bits.toTlb.entry(i).v := Mux(resp_res.l3.hit, resp_res.l3.v(i), resp_res.sp.v)
    io.resp.bits.toTlb.entry(i).pf := !io.resp.bits.toTlb.entry(i).v
    io.resp.bits.toTlb.entry(i).af := false.B
  }
  io.resp.bits.toTlb.pteidx := UIntToOH(stageResp.bits.req_info.vpn(2, 0)).asBools
  io.resp.bits.toTlb.not_super := Mux(resp_res.l3.hit, true.B, false.B)
  io.resp.valid := stageResp.valid
  XSError(stageResp.valid && resp_res.l3.hit && resp_res.sp.hit, "normal page and super page both hit")
  XSError(stageResp.valid && io.resp.bits.hit && bypassed(2), "page cache, bypassed but hit")

  // refill Perf
  val l1RefillPerf = Wire(Vec(l2tlbParams.l1Size, Bool()))
  val l2RefillPerf = Wire(Vec(l2tlbParams.l2nWays, Bool()))
  val l3RefillPerf = Wire(Vec(l2tlbParams.l3nWays, Bool()))
  val spRefillPerf = Wire(Vec(l2tlbParams.spSize, Bool()))
  l1RefillPerf.map(_ := false.B)
  l2RefillPerf.map(_ := false.B)
  l3RefillPerf.map(_ := false.B)
  spRefillPerf.map(_ := false.B)

  // refill
  l2.io.w.req <> DontCare
  l3.io.w.req <> DontCare
  l2.io.w.req.valid := false.B
  l3.io.w.req.valid := false.B

  val memRdata = refill.ptes
  val memPtes = (0 until (l2tlbParams.blockBytes/(XLEN/8))).map(i => memRdata((i+1)*XLEN-1, i*XLEN).asTypeOf(new PteBundle))
  val memSelData = io.refill.bits.sel_pte_dup
  val memPte = memSelData.map(a => a.asTypeOf(new PteBundle))

  // TODO: handle sfenceLatch outsize
  when (!flush_dup(0) && refill.levelOH.l1 && !memPte(0).isLeaf() && !memPte(0).isPf(refill.level_dup(0)) && !memPte(0).isAf()) {
    // val refillIdx = LFSR64()(log2Up(l2tlbParams.l1Size)-1,0) // TODO: may be LRU
    val refillIdx = replaceWrapper(l1v, ptwl1replace.way)
    refillIdx.suggestName(s"PtwL1RefillIdx")
    val rfOH = UIntToOH(refillIdx)
    l1(refillIdx).refill(
      refill.req_info_dup(0).vpn,
      Mux(refill.req_info_dup(0).s2xlate(0).asBool(), io.csr_dup(0).vsatp.asid, io.csr_dup(0).satp.asid),
      io.csr_dup(0).hgatp.asid,
      memSelData(0),
      0.U,
      refill_prefetch_dup(0)
    )
    ptwl1replace.access(refillIdx)
    l1v := l1v | rfOH
    l1g := (l1g & ~rfOH) | Mux(memPte(0).perm.g, rfOH, 0.U)
    l1h(refillIdx) := refill.req_info_dup(0).s2xlate

    for (i <- 0 until l2tlbParams.l1Size) {
      l1RefillPerf(i) := i.U === refillIdx
    }

    XSDebug(p"[l1 refill] refillIdx:${refillIdx} refillEntry:${l1(refillIdx).genPtwEntry(refill.req_info_dup(0).vpn, io.csr_dup(0).satp.asid, memSelData(0), 0.U, prefetch = refill_prefetch_dup(0))}\n")
    XSDebug(p"[l1 refill] l1v:${Binary(l1v)}->${Binary(l1v | rfOH)} l1g:${Binary(l1g)}->${Binary((l1g & ~rfOH) | Mux(memPte(0).perm.g, rfOH, 0.U))}\n")

    refillIdx.suggestName(s"l1_refillIdx")
    rfOH.suggestName(s"l1_rfOH")
  }

  when (!flush_dup(1) && refill.levelOH.l2 && !memPte(1).isLeaf() && !memPte(1).isPf(refill.level_dup(1)) && !memPte(1).isAf()) {
    val refillIdx = genPtwL2SetIdx(refill.req_info_dup(1).vpn)
    val victimWay = replaceWrapper(getl2vSet(refill.req_info_dup(1).vpn), ptwl2replace.way(refillIdx))
    val victimWayOH = UIntToOH(victimWay)
    val rfvOH = UIntToOH(Cat(refillIdx, victimWay))
    val wdata = Wire(l2EntryType)
    wdata.gen(
      vpn = refill.req_info_dup(1).vpn,
      asid = Mux(refill.req_info_dup(1).s2xlate =/= noS2xlate, io.csr_dup(1).vsatp.asid, io.csr_dup(1).satp.asid),
      vmid = io.csr_dup(1).hgatp.asid,
      data = memRdata,
      levelUInt = 1.U,
      refill_prefetch_dup(1)
    )
    l2.io.w.apply(
      valid = true.B,
      setIdx = refillIdx,
      data = wdata,
      waymask = victimWayOH
    )
    ptwl2replace.access(refillIdx, victimWay)
    l2v := l2v | rfvOH
    l2g := l2g & ~rfvOH | Mux(Cat(memPtes.map(_.perm.g)).andR, rfvOH, 0.U)
    l2h(refillIdx)(victimWay) := refill.req_info_dup(0).s2xlate

    for (i <- 0 until l2tlbParams.l2nWays) {
      l2RefillPerf(i) := i.U === victimWay
    }

    XSDebug(p"[l2 refill] refillIdx:0x${Hexadecimal(refillIdx)} victimWay:${victimWay} victimWayOH:${Binary(victimWayOH)} rfvOH(in UInt):${Cat(refillIdx, victimWay)}\n")
    XSDebug(p"[l2 refill] refilldata:0x${wdata}\n")
    XSDebug(p"[l2 refill] l2v:${Binary(l2v)} -> ${Binary(l2v | rfvOH)}\n")
    XSDebug(p"[l2 refill] l2g:${Binary(l2g)} -> ${Binary(l2g & ~rfvOH | Mux(Cat(memPtes.map(_.perm.g)).andR, rfvOH, 0.U))}\n")

    refillIdx.suggestName(s"l2_refillIdx")
    victimWay.suggestName(s"l2_victimWay")
    victimWayOH.suggestName(s"l2_victimWayOH")
    rfvOH.suggestName(s"l2_rfvOH")
  }

  when (!flush_dup(2) && refill.levelOH.l3 && !memPte(2).isAf()) {
    val refillIdx = genPtwL3SetIdx(refill.req_info_dup(2).vpn)
    val victimWay = replaceWrapper(getl3vSet(refill.req_info_dup(2).vpn), ptwl3replace.way(refillIdx))
    val victimWayOH = UIntToOH(victimWay)
    val rfvOH = UIntToOH(Cat(refillIdx, victimWay))
    val wdata = Wire(l3EntryType)
    wdata.gen(
      vpn =  refill.req_info_dup(2).vpn,
      asid = Mux(refill.req_info_dup(2).s2xlate =/= noS2xlate, io.csr_dup(2).vsatp.asid, io.csr_dup(2).satp.asid),
      vmid = io.csr_dup(2).hgatp.asid,
      data = memRdata,
      levelUInt = 2.U,
      refill_prefetch_dup(2)
    )
    l3.io.w.apply(
      valid = true.B,
      setIdx = refillIdx,
      data = wdata,
      waymask = victimWayOH
    )
    ptwl3replace.access(refillIdx, victimWay)
    l3v := l3v | rfvOH
    l3g := l3g & ~rfvOH | Mux(Cat(memPtes.map(_.perm.g)).andR, rfvOH, 0.U)
    l3h(refillIdx)(victimWay) := refill.req_info_dup(2).s2xlate

    for (i <- 0 until l2tlbParams.l3nWays) {
      l3RefillPerf(i) := i.U === victimWay
    }

    XSDebug(p"[l3 refill] refillIdx:0x${Hexadecimal(refillIdx)} victimWay:${victimWay} victimWayOH:${Binary(victimWayOH)} rfvOH(in UInt):${Cat(refillIdx, victimWay)}\n")
    XSDebug(p"[l3 refill] refilldata:0x${wdata}\n")
    XSDebug(p"[l3 refill] l3v:${Binary(l3v)} -> ${Binary(l3v | rfvOH)}\n")
    XSDebug(p"[l3 refill] l3g:${Binary(l3g)} -> ${Binary(l3g & ~rfvOH | Mux(Cat(memPtes.map(_.perm.g)).andR, rfvOH, 0.U))}\n")

    refillIdx.suggestName(s"l3_refillIdx")
    victimWay.suggestName(s"l3_victimWay")
    victimWayOH.suggestName(s"l3_victimWayOH")
    rfvOH.suggestName(s"l3_rfvOH")
  }


  // misc entries: super & invalid
  when (!flush_dup(0) && refill.levelOH.sp && (memPte(0).isLeaf() || memPte(0).isPf(refill.level_dup(0))) && !memPte(0).isAf()) {
    val refillIdx = spreplace.way// LFSR64()(log2Up(l2tlbParams.spSize)-1,0) // TODO: may be LRU
    val rfOH = UIntToOH(refillIdx)
    sp(refillIdx).refill(
      refill.req_info_dup(0).vpn,
      io.csr_dup(0).satp.asid,
      io.csr_dup(0).hgatp.asid,
      memSelData(0),
      refill.level_dup(2),
      refill_prefetch_dup(0),
      !memPte(0).isPf(refill.level_dup(0)),
    )
    spreplace.access(refillIdx)
    spv := spv | rfOH
    spg := spg & ~rfOH | Mux(memPte(0).perm.g, rfOH, 0.U)
    sph(refillIdx) := refill.req_info_dup(0).s2xlate

    for (i <- 0 until l2tlbParams.spSize) {
      spRefillPerf(i) := i.U === refillIdx
    }

    XSDebug(p"[sp refill] refillIdx:${refillIdx} refillEntry:${sp(refillIdx).genPtwEntry(refill.req_info_dup(0).vpn, io.csr_dup(0).satp.asid, memSelData(0), refill.level_dup(0), refill_prefetch_dup(0))}\n")
    XSDebug(p"[sp refill] spv:${Binary(spv)}->${Binary(spv | rfOH)} spg:${Binary(spg)}->${Binary(spg & ~rfOH | Mux(memPte(0).perm.g, rfOH, 0.U))}\n")

    refillIdx.suggestName(s"sp_refillIdx")
    rfOH.suggestName(s"sp_rfOH")
  }

  val l2eccFlush = resp_res.l2.ecc && stageResp_valid_1cycle_dup(0) // RegNext(l2eccError, init = false.B)
  val l3eccFlush = resp_res.l3.ecc && stageResp_valid_1cycle_dup(1) // RegNext(l3eccError, init = false.B)
  val eccVpn = stageResp.bits.req_info.vpn

  XSError(l2eccFlush, "l2tlb.cache.l2 ecc error. Should not happen at sim stage")
  XSError(l3eccFlush, "l2tlb.cache.l3 ecc error. Should not happen at sim stage")
  when (l2eccFlush) {
    val flushSetIdxOH = UIntToOH(genPtwL2SetIdx(eccVpn))
    val flushMask = VecInit(flushSetIdxOH.asBools.map { a => Fill(l2tlbParams.l2nWays, a.asUInt) }).asUInt
    l2v := l2v & ~flushMask
    l2g := l2g & ~flushMask
  }

  when (l3eccFlush) {
    val flushSetIdxOH = UIntToOH(genPtwL3SetIdx(eccVpn))
    val flushMask = VecInit(flushSetIdxOH.asBools.map { a => Fill(l2tlbParams.l3nWays, a.asUInt) }).asUInt
    l3v := l3v & ~flushMask
    l3g := l3g & ~flushMask
  }

  // sfence for not-virtualization for l3、l2
  val sfence_valid_l3 = sfence_dup(3).valid && !sfence_dup(3).bits.hg && !sfence_dup(3).bits.hv
  when (sfence_valid_l3 && io.csr_dup(2).priv.virt === false.B) {
    val sfence_vpn = sfence_dup(3).bits.addr(sfence_dup(3).bits.addr.getWidth-1, offLen)
    when (sfence_dup(3).bits.rs1/*va*/) {
      when (sfence_dup(3).bits.rs2) {
        // all va && all asid
        l3v := 0.U
      } .otherwise {
        // all va && specific asid except global
        l3v := l3v & l3g
      }
    } .otherwise {
      // val flushMask = UIntToOH(genTlbL2Idx(sfence.bits.addr(sfence.bits.addr.getWidth-1, offLen)))
      val flushSetIdxOH = UIntToOH(genPtwL3SetIdx(sfence_vpn))
      // val flushMask = VecInit(flushSetIdxOH.asBools.map(Fill(l2tlbParams.l3nWays, _.asUInt))).asUInt
      val flushMask = VecInit(flushSetIdxOH.asBools.map { a => Fill(l2tlbParams.l3nWays, a.asUInt) }).asUInt
      flushSetIdxOH.suggestName(s"sfence_nrs1_flushSetIdxOH")
      flushMask.suggestName(s"sfence_nrs1_flushMask")

      when (sfence_dup(3).bits.rs2) {
        // specific leaf of addr && all asid
        l3v := l3v & ~flushMask
      } .otherwise {
        // specific leaf of addr && specific asid
        l3v := l3v & (~flushMask | l3g)
      }
    }
  }

  // sfence for virtualization and hfencev, simple implementation for l3、l2
  val hfencev_valid_l3 = sfence_dup(3).valid && sfence_dup(3).bits.hv
  when((hfencev_valid_l3 && io.csr_dup(2).priv.virt) || hfencev_valid_l3) {
    val flushMask = VecInit(l3h.flatMap(_.map(_  === onlyStage1))).asUInt
    l3v := l3v & ~flushMask // all VS-stage l3 pte
  }

  // hfenceg, simple implementation for l3
  val hfenceg_valid_l3 = sfence_dup(3).valid && sfence_dup(3).bits.hg
  when(hfenceg_valid_l3) {
    val flushMask = VecInit(l3h.flatMap(_.map(_ === onlyStage2))).asUInt
    l3v := l3v & ~flushMask // all G-stage l3 pte
  }


  val l1asidhit = VecInit(l1asids.map(_ === sfence_dup(0).bits.id)).asUInt
  val spasidhit = VecInit(spasids.map(_ === sfence_dup(0).bits.id)).asUInt
  val sfence_valid = sfence_dup(0).valid && !sfence_dup(0).bits.hg && !sfence_dup(0).bits.hv
  when (sfence_valid) {
    val l1vmidhit = VecInit(l1vmids.map(_.getOrElse(0.U) === io.csr_dup(0).hgatp.asid)).asUInt
    val spvmidhit = VecInit(spvmids.map(_.getOrElse(0.U) === io.csr_dup(0).hgatp.asid)).asUInt
    val l1hhit = VecInit(l1h.map(_ === onlyStage1 && io.csr_dup(0).priv.virt)).asUInt
    val sphhit = VecInit(sph.map(_ === onlyStage1 && io.csr_dup(0).priv.virt)).asUInt
    val l2hhit = VecInit(l2h.flatMap(_.map(_ === onlyStage1 && io.csr_dup(0).priv.virt))).asUInt
    val sfence_vpn = sfence_dup(0).bits.addr(sfence_dup(0).bits.addr.getWidth-1, offLen)
    val l2h_set = getl2hSet(sfence_vpn)

    when (sfence_dup(0).bits.rs1/*va*/) {
      when (sfence_dup(0).bits.rs2) {
        // all va && all asid
        l2v := l2v & ~l2hhit
        l1v := l1v & ~(l1hhit & VecInit(UIntToOH(l1vmidhit).asBools().map{a => io.csr_dup(0).priv.virt && a || !io.csr_dup(0).priv.virt}).asUInt())
        spv := spv & ~(l2hhit & VecInit(UIntToOH(spvmidhit).asBools().map{a => io.csr_dup(0).priv.virt && a || !io.csr_dup(0).priv.virt}).asUInt())
      } .otherwise {
        // all va && specific asid except global
        l2v := l2v & (l2g | ~l2hhit)
        l1v := l1v & ~(~l1g & l1hhit & l1asidhit & VecInit(UIntToOH(l1vmidhit).asBools().map{a => io.csr_dup(0).priv.virt && a || !io.csr_dup(0).priv.virt}).asUInt())
        spv := spv & ~(~spg & sphhit & spasidhit & VecInit(UIntToOH(spvmidhit).asBools().map{a => io.csr_dup(0).priv.virt && a || !io.csr_dup(0).priv.virt}).asUInt())
      }
    } .otherwise {
      when (sfence_dup(0).bits.rs2) {
        // specific leaf of addr && all asid
        spv := spv & (~VecInit(sp.map(_.hit(sfence_vpn, sfence_dup(0).bits.id, io.csr_dup(0).hgatp.asid, ignoreAsid = true, s2xlate = io.csr_dup(0).priv.virt))).asUInt)
      } .otherwise {
        // specific leaf of addr && specific asid
        spv := spv & (~VecInit(sp.map(_.hit(sfence_vpn, sfence_dup(0).bits.id, io.csr_dup(0).hgatp.asid, s2xlate = io.csr_dup(0).priv.virt))).asUInt | spg)
      }
    }
  }

  val hfencev_valid = sfence_dup(0).valid && sfence_dup(0).bits.hv
  when (hfencev_valid) {
    val l1vmidhit = VecInit(l1vmids.map(_.getOrElse(0.U) === io.csr_dup(0).hgatp.asid)).asUInt
    val spvmidhit = VecInit(spvmids.map(_.getOrElse(0.U) === io.csr_dup(0).hgatp.asid)).asUInt
    val l1hhit = VecInit(l1h.map(_ === onlyStage1)).asUInt
    val sphhit = VecInit(sph.map(_ === onlyStage1)).asUInt
    val l2hhit = VecInit(l2h.flatMap(_.map(_ === onlyStage1))).asUInt
    val hfencev_vpn = sfence_dup(0).bits.addr(sfence_dup(0).bits.addr.getWidth-1, offLen)
    when(sfence_dup(0).bits.rs1) {
      when(sfence_dup(0).bits.rs2) {
        l2v := l2v & ~l2hhit
        l1v := l1v & ~(l1hhit & l1vmidhit)
        spv := spv & ~(l2hhit & spvmidhit)
      }.otherwise {
        l2v := l2v & (l2g | ~l2hhit)
        l1v := l1v & ~(~l1g & l1hhit & l1asidhit & l1vmidhit)
        spv := spv & ~(~spg & sphhit & spasidhit & spvmidhit)
      }
    }.otherwise {
      when(sfence_dup(0).bits.rs2) {
        spv := spv & (~VecInit(sp.map(_.hit(hfencev_vpn, sfence_dup(0).bits.id, io.csr_dup(0).hgatp.asid, ignoreAsid = true, s2xlate = true.B))).asUInt)
      }.otherwise {
        spv := spv & (~VecInit(sp.map(_.hit(hfencev_vpn, sfence_dup(0).bits.id, io.csr_dup(0).hgatp.asid, s2xlate = true.B))).asUInt | spg)
      }
    }
  }


  val hfenceg_valid = sfence_dup(0).valid && sfence_dup(0).bits.hg
  when(hfenceg_valid) {
    val l1vmidhit = VecInit(l1vmids.map(_.getOrElse(0.U) === sfence_dup(0).bits.id)).asUInt
    val spvmidhit = VecInit(spvmids.map(_.getOrElse(0.U) === sfence_dup(0).bits.id)).asUInt
    val l1hhit = VecInit(l1h.map(_ === onlyStage2)).asUInt
    val sphhit = VecInit(sph.map(_ === onlyStage2)).asUInt
    val l2hhit = VecInit(l2h.flatMap(_.map(_ === onlyStage2))).asUInt
    val hfenceg_gvpn = sfence_dup(0).bits.addr(sfence_dup(0).bits.addr.getWidth - 1, offLen)
    when(sfence_dup(0).bits.rs1) {
      when(sfence_dup(0).bits.rs2) {
        l2v := l2v & ~l2hhit
        l1v := l1v & ~l1hhit
        spv := spv & ~sphhit
      }.otherwise {
        l2v := l2v & ~l2hhit
        l1v := l1v & ~(l1hhit & l1vmidhit)
        spv := spv & ~(sphhit & spvmidhit)
      }
    }.otherwise {
      when(sfence_dup(0).bits.rs2) {
        spv := spv & (~VecInit(sp.map(_.hit(hfenceg_gvpn, 0.U, sfence_dup(0).bits.id, ignoreAsid = true, s2xlate = true.B))).asUInt)
      }.otherwise {
        spv := spv & (~VecInit(sp.map(_.hit(hfenceg_gvpn, 0.U, sfence_dup(0).bits.id, ignoreAsid = true, s2xlate = false.B))).asUInt)
      }
    }
  }

  def InsideStageConnect(in: DecoupledIO[PtwCacheReq], out: DecoupledIO[PtwCacheReq], inFire: Bool): Unit = {
    in.ready := !in.valid || out.ready
    out.valid := in.valid
    out.bits := in.bits
    out.bits.bypassed.zip(in.bits.bypassed).zipWithIndex.map{ case (b, i) =>
      val bypassed_reg = Reg(Bool())
      val bypassed_wire = refill_bypass(in.bits.req_info.vpn, i, in.bits.req_info.s2xlate) && io.refill.valid
      when (inFire) { bypassed_reg := bypassed_wire }
      .elsewhen (io.refill.valid) { bypassed_reg := bypassed_reg || bypassed_wire }

      b._1 := b._2 || (bypassed_wire || (bypassed_reg && !inFire))
    }
  }

  // Perf Count
  val resp_l3 = resp_res.l3.hit
  val resp_sp = resp_res.sp.hit
  val resp_l1_pre = resp_res.l1.pre
  val resp_l2_pre = resp_res.l2.pre
  val resp_l3_pre = resp_res.l3.pre
  val resp_sp_pre = resp_res.sp.pre
  val base_valid_access_0 = !from_pre(io.resp.bits.req_info.source) && io.resp.fire
  XSPerfAccumulate("access", base_valid_access_0)
  XSPerfAccumulate("l1_hit", base_valid_access_0 && io.resp.bits.toFsm.l1Hit && !io.resp.bits.toFsm.l2Hit && !io.resp.bits.hit)
  XSPerfAccumulate("l2_hit", base_valid_access_0 && io.resp.bits.toFsm.l2Hit && !io.resp.bits.hit)
  XSPerfAccumulate("l3_hit", base_valid_access_0 && resp_l3)
  XSPerfAccumulate("sp_hit", base_valid_access_0 && resp_sp)
  XSPerfAccumulate("pte_hit",base_valid_access_0 && io.resp.bits.hit)

  XSPerfAccumulate("l1_hit_pre", base_valid_access_0 && resp_l1_pre && io.resp.bits.toFsm.l1Hit && !io.resp.bits.toFsm.l2Hit && !io.resp.bits.hit)
  XSPerfAccumulate("l2_hit_pre", base_valid_access_0 && resp_l2_pre && io.resp.bits.toFsm.l2Hit && !io.resp.bits.hit)
  XSPerfAccumulate("l3_hit_pre", base_valid_access_0 && resp_l3_pre && resp_l3)
  XSPerfAccumulate("sp_hit_pre", base_valid_access_0 && resp_sp_pre && resp_sp)
  XSPerfAccumulate("pte_hit_pre",base_valid_access_0 && (resp_l3_pre && resp_l3 || resp_sp_pre && resp_sp) && io.resp.bits.hit)

  val base_valid_access_1 = from_pre(io.resp.bits.req_info.source) && io.resp.fire
  XSPerfAccumulate("pre_access", base_valid_access_1)
  XSPerfAccumulate("pre_l1_hit", base_valid_access_1 && io.resp.bits.toFsm.l1Hit && !io.resp.bits.toFsm.l2Hit && !io.resp.bits.hit)
  XSPerfAccumulate("pre_l2_hit", base_valid_access_1 && io.resp.bits.toFsm.l2Hit && !io.resp.bits.hit)
  XSPerfAccumulate("pre_l3_hit", base_valid_access_1 && resp_l3)
  XSPerfAccumulate("pre_sp_hit", base_valid_access_1 && resp_sp)
  XSPerfAccumulate("pre_pte_hit",base_valid_access_1 && io.resp.bits.hit)

  XSPerfAccumulate("pre_l1_hit_pre", base_valid_access_1 && resp_l1_pre && io.resp.bits.toFsm.l1Hit && !io.resp.bits.toFsm.l2Hit && !io.resp.bits.hit)
  XSPerfAccumulate("pre_l2_hit_pre", base_valid_access_1 && resp_l2_pre && io.resp.bits.toFsm.l2Hit && !io.resp.bits.hit)
  XSPerfAccumulate("pre_l3_hit_pre", base_valid_access_1 && resp_l3_pre && resp_l3)
  XSPerfAccumulate("pre_sp_hit_pre", base_valid_access_1 && resp_sp_pre && resp_sp)
  XSPerfAccumulate("pre_pte_hit_pre",base_valid_access_1 && (resp_l3_pre && resp_l3 || resp_sp_pre && resp_sp) && io.resp.bits.hit)

  val base_valid_access_2 = stageResp.bits.isFirst && !from_pre(io.resp.bits.req_info.source) && io.resp.fire
  XSPerfAccumulate("access_first", base_valid_access_2)
  XSPerfAccumulate("l1_hit_first", base_valid_access_2 && io.resp.bits.toFsm.l1Hit && !io.resp.bits.toFsm.l2Hit && !io.resp.bits.hit)
  XSPerfAccumulate("l2_hit_first", base_valid_access_2 && io.resp.bits.toFsm.l2Hit && !io.resp.bits.hit)
  XSPerfAccumulate("l3_hit_first", base_valid_access_2 && resp_l3)
  XSPerfAccumulate("sp_hit_first", base_valid_access_2 && resp_sp)
  XSPerfAccumulate("pte_hit_first",base_valid_access_2 && io.resp.bits.hit)

  XSPerfAccumulate("l1_hit_pre_first", base_valid_access_2 && resp_l1_pre && io.resp.bits.toFsm.l1Hit && !io.resp.bits.toFsm.l2Hit && !io.resp.bits.hit)
  XSPerfAccumulate("l2_hit_pre_first", base_valid_access_2 && resp_l2_pre && io.resp.bits.toFsm.l2Hit && !io.resp.bits.hit)
  XSPerfAccumulate("l3_hit_pre_first", base_valid_access_2 && resp_l3_pre && resp_l3)
  XSPerfAccumulate("sp_hit_pre_first", base_valid_access_2 && resp_sp_pre && resp_sp)
  XSPerfAccumulate("pte_hit_pre_first",base_valid_access_2 && (resp_l3_pre && resp_l3 || resp_sp_pre && resp_sp) && io.resp.bits.hit)

  val base_valid_access_3 = stageResp.bits.isFirst && from_pre(io.resp.bits.req_info.source) && io.resp.fire
  XSPerfAccumulate("pre_access_first", base_valid_access_3)
  XSPerfAccumulate("pre_l1_hit_first", base_valid_access_3 && io.resp.bits.toFsm.l1Hit && !io.resp.bits.toFsm.l2Hit && !io.resp.bits.hit)
  XSPerfAccumulate("pre_l2_hit_first", base_valid_access_3 && io.resp.bits.toFsm.l2Hit && !io.resp.bits.hit)
  XSPerfAccumulate("pre_l3_hit_first", base_valid_access_3 && resp_l3)
  XSPerfAccumulate("pre_sp_hit_first", base_valid_access_3 && resp_sp)
  XSPerfAccumulate("pre_pte_hit_first", base_valid_access_3 && io.resp.bits.hit)

  XSPerfAccumulate("pre_l1_hit_pre_first", base_valid_access_3 && resp_l1_pre && io.resp.bits.toFsm.l1Hit && !io.resp.bits.toFsm.l2Hit && !io.resp.bits.hit)
  XSPerfAccumulate("pre_l2_hit_pre_first", base_valid_access_3 && resp_l2_pre && io.resp.bits.toFsm.l2Hit && !io.resp.bits.hit)
  XSPerfAccumulate("pre_l3_hit_pre_first", base_valid_access_3 && resp_l3_pre && resp_l3)
  XSPerfAccumulate("pre_sp_hit_pre_first", base_valid_access_3 && resp_sp_pre && resp_sp)
  XSPerfAccumulate("pre_pte_hit_pre_first",base_valid_access_3 && (resp_l3_pre && resp_l3 || resp_sp_pre && resp_sp) && io.resp.bits.hit)

  XSPerfAccumulate("rwHarzad", io.req.valid && !io.req.ready)
  XSPerfAccumulate("out_blocked", io.resp.valid && !io.resp.ready)
  l1AccessPerf.zipWithIndex.map{ case (l, i) => XSPerfAccumulate(s"L1AccessIndex${i}", l) }
  l2AccessPerf.zipWithIndex.map{ case (l, i) => XSPerfAccumulate(s"L2AccessIndex${i}", l) }
  l3AccessPerf.zipWithIndex.map{ case (l, i) => XSPerfAccumulate(s"L3AccessIndex${i}", l) }
  spAccessPerf.zipWithIndex.map{ case (l, i) => XSPerfAccumulate(s"SPAccessIndex${i}", l) }
  l1RefillPerf.zipWithIndex.map{ case (l, i) => XSPerfAccumulate(s"L1RefillIndex${i}", l) }
  l2RefillPerf.zipWithIndex.map{ case (l, i) => XSPerfAccumulate(s"L2RefillIndex${i}", l) }
  l3RefillPerf.zipWithIndex.map{ case (l, i) => XSPerfAccumulate(s"L3RefillIndex${i}", l) }
  spRefillPerf.zipWithIndex.map{ case (l, i) => XSPerfAccumulate(s"SPRefillIndex${i}", l) }

  XSPerfAccumulate("l1Refill", Cat(l1RefillPerf).orR)
  XSPerfAccumulate("l2Refill", Cat(l2RefillPerf).orR)
  XSPerfAccumulate("l3Refill", Cat(l3RefillPerf).orR)
  XSPerfAccumulate("spRefill", Cat(spRefillPerf).orR)
  XSPerfAccumulate("l1Refill_pre", Cat(l1RefillPerf).orR && refill_prefetch_dup(0))
  XSPerfAccumulate("l2Refill_pre", Cat(l2RefillPerf).orR && refill_prefetch_dup(0))
  XSPerfAccumulate("l3Refill_pre", Cat(l3RefillPerf).orR && refill_prefetch_dup(0))
  XSPerfAccumulate("spRefill_pre", Cat(spRefillPerf).orR && refill_prefetch_dup(0))

  // debug
  XSDebug(sfence_dup(0).valid, p"[sfence] original v and g vector:\n")
  XSDebug(sfence_dup(0).valid, p"[sfence] l1v:${Binary(l1v)}\n")
  XSDebug(sfence_dup(0).valid, p"[sfence] l2v:${Binary(l2v)}\n")
  XSDebug(sfence_dup(0).valid, p"[sfence] l3v:${Binary(l3v)}\n")
  XSDebug(sfence_dup(0).valid, p"[sfence] l3g:${Binary(l3g)}\n")
  XSDebug(sfence_dup(0).valid, p"[sfence] spv:${Binary(spv)}\n")
  XSDebug(RegNext(sfence_dup(0).valid), p"[sfence] new v and g vector:\n")
  XSDebug(RegNext(sfence_dup(0).valid), p"[sfence] l1v:${Binary(l1v)}\n")
  XSDebug(RegNext(sfence_dup(0).valid), p"[sfence] l2v:${Binary(l2v)}\n")
  XSDebug(RegNext(sfence_dup(0).valid), p"[sfence] l3v:${Binary(l3v)}\n")
  XSDebug(RegNext(sfence_dup(0).valid), p"[sfence] l3g:${Binary(l3g)}\n")
  XSDebug(RegNext(sfence_dup(0).valid), p"[sfence] spv:${Binary(spv)}\n")

  val perfEvents = Seq(
    ("access           ", base_valid_access_0             ),
    ("l1_hit           ", l1Hit                           ),
    ("l2_hit           ", l2Hit                           ),
    ("l3_hit           ", l3Hit                           ),
    ("sp_hit           ", spHit                           ),
    ("pte_hit          ", l3Hit || spHit                  ),
    ("rwHarzad         ",  io.req.valid && !io.req.ready  ),
    ("out_blocked      ",  io.resp.valid && !io.resp.ready),
  )
  generatePerfEvent()
}
