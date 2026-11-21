package com.trendlinealerts.core.engine

import com.trendlinealerts.core.model.Candle
import com.trendlinealerts.core.model.Pivot
import com.trendlinealerts.core.model.PivotStackSnapshot
import com.trendlinealerts.core.model.PriceSide
import com.trendlinealerts.core.model.SymbolTrendlines
import com.trendlinealerts.core.model.TrendlineSlot
import com.trendlinealerts.core.model.TrendlineSlotId

class PivotStack(
    private val dir: Double,
    private val maxSize: Int = DEFAULT_MAX_SIZE
) {
    init {
        require(dir == 1.0 || dir == -1.0) { "dir must be +1.0 or -1.0" }
        require(maxSize > 0) { "maxSize must be positive" }
    }

    private val pr = ArrayList<Double>(maxSize + 2)
    private val tm = ArrayList<Long>(maxSize + 2)
    private var pend: Double? = null
    private var pendT: Long? = null

    fun update(pv: Double, pt: Long, isNew: Boolean) {
        if (!isNew) return

        val currentPend = pend
        val currentPendT = pendT
        if (currentPend != null && currentPendT != null) {
            if (dir * pv >= dir * currentPend) {
                pend = pv
                pendT = pt
            } else {
                while (pr.isNotEmpty() && dir * pr.last() <= dir * currentPend) {
                    pr.removeAt(pr.lastIndex)
                    tm.removeAt(tm.lastIndex)
                }
                pr.add(currentPend)
                tm.add(currentPendT)
                pr.add(pv)
                tm.add(pt)
                pend = null
                pendT = null
            }
        } else if (pr.isEmpty() || dir * pv < dir * pr.last()) {
            pr.add(pv)
            tm.add(pt)
        } else {
            pend = pv
            pendT = pt
        }

        while (pr.size > maxSize) {
            pr.removeAt(0)
            tm.removeAt(0)
        }
    }

    fun snapshot(): PivotStackSnapshot {
        val size = pr.size
        fun pivotAtOffsetFromEnd(offset: Int): Pivot? {
            val idx = size - 1 - offset
            return if (idx >= 0) Pivot(value = pr[idx], timeMs = tm[idx]) else null
        }

        val pendingPivot = if (pend != null && pendT != null) {
            Pivot(value = pend!!, timeMs = pendT!!)
        } else {
            null
        }

        return PivotStackSnapshot(
            p1 = pivotAtOffsetFromEnd(0),
            p2 = pivotAtOffsetFromEnd(1),
            p3 = pivotAtOffsetFromEnd(2),
            p4 = pivotAtOffsetFromEnd(3),
            pending = pendingPivot,
            stackSize = size
        )
    }

    fun valuesOldestToNewest(): List<Pivot> =
        pr.indices.map { idx -> Pivot(value = pr[idx], timeMs = tm[idx]) }

    companion object {
        const val DEFAULT_MAX_SIZE = 50
    }
}

class TrendlineEngine {

    fun filterClosedCandles(candles: List<Candle>, nowMs: Long): List<Candle> =
        candles
            .asSequence()
            .filter { it.isClosed(nowMs) }
            .sortedBy { it.openTime }
            .toList()

    fun compute(
        symbol: String,
        closedCandles: List<Candle>
    ): SymbolTrendlines {
        val ordered = if (isSortedByOpenTime(closedCandles)) {
            closedCandles
        } else {
            closedCandles.sortedBy { it.openTime }
        }

        val resistanceStack = PivotStack(dir = +1.0)
        val supportStack = PivotStack(dir = -1.0)

        for (j in 5 until ordered.size) {
            val p = j - 3
            val isHighPivot = isHighPivot(ordered, j)
            val isLowPivot = isLowPivot(ordered, j)

            resistanceStack.update(
                pv = ordered[p].high,
                pt = ordered[p].openTime,
                isNew = isHighPivot
            )
            supportStack.update(
                pv = ordered[p].low,
                pt = ordered[p].openTime,
                isNew = isLowPivot
            )
        }

        val resSnap = resistanceStack.snapshot()
        val supSnap = supportStack.snapshot()

        val slots = mapOf(
            TrendlineSlotId.R1 to TrendlineSlot(TrendlineSlotId.R1, newerPivot = resSnap.p1, olderPivot = resSnap.p2),
            TrendlineSlotId.R2 to TrendlineSlot(TrendlineSlotId.R2, newerPivot = resSnap.p2, olderPivot = resSnap.p3),
            TrendlineSlotId.R3 to TrendlineSlot(TrendlineSlotId.R3, newerPivot = resSnap.p3, olderPivot = resSnap.p4),
            TrendlineSlotId.S1 to TrendlineSlot(TrendlineSlotId.S1, newerPivot = supSnap.p1, olderPivot = supSnap.p2),
            TrendlineSlotId.S2 to TrendlineSlot(TrendlineSlotId.S2, newerPivot = supSnap.p2, olderPivot = supSnap.p3),
            TrendlineSlotId.S3 to TrendlineSlot(TrendlineSlotId.S3, newerPivot = supSnap.p3, olderPivot = supSnap.p4)
        )

        return SymbolTrendlines(
            symbol = symbol,
            resistanceStack = resSnap,
            supportStack = supSnap,
            slots = slots,
            lastClosedCandleOpenTime = ordered.lastOrNull()?.openTime
        )
    }

    fun computeFromRaw(
        symbol: String,
        rawCandles: List<Candle>,
        nowMs: Long
    ): SymbolTrendlines {
        val closed = filterClosedCandles(rawCandles, nowMs)
        return compute(symbol, closed)
    }

    private fun isHighPivot(candles: List<Candle>, j: Int): Boolean {
        val c5 = candles[j - 5].high
        val c4 = candles[j - 4].high
        val c3 = candles[j - 3].high
        val c2 = candles[j - 2].high
        val c1 = candles[j - 1].high
        return c5 < c4 && c4 < c3 && c3 > c2 && c2 > c1
    }

    private fun isLowPivot(candles: List<Candle>, j: Int): Boolean {
        val c5 = candles[j - 5].low
        val c4 = candles[j - 4].low
        val c3 = candles[j - 3].low
        val c2 = candles[j - 2].low
        val c1 = candles[j - 1].low
        return c5 > c4 && c4 > c3 && c3 < c2 && c2 < c1
    }

    private fun isSortedByOpenTime(candles: List<Candle>): Boolean {
        for (i in 1 until candles.size) {
            if (candles[i].openTime < candles[i - 1].openTime) return false
        }
        return true
    }
}

data class KissReversalTracker(
    val approachSide: PriceSide,
    val minGapPct: Double
)

data class SlotRuntimeState(
    var anchorKey: Pair<Long, Long>? = null,
    var rememberedSide: PriceSide = PriceSide.UNKNOWN,
    var skipNextEvaluationCycle: Boolean = true,
    var kissReversalTracker: KissReversalTracker? = null
)

class SymbolEngineState(val symbol: String) {
    private val slotStates: MutableMap<TrendlineSlotId, SlotRuntimeState> =
        TrendlineSlotId.ALL.associateWith { SlotRuntimeState() }.toMutableMap()

    var currentTrendlines: SymbolTrendlines? = null
        private set

    fun slotState(slotId: TrendlineSlotId): SlotRuntimeState =
        slotStates.getValue(slotId)

    fun applyTrendlines(updated: SymbolTrendlines) {
        currentTrendlines = updated
        for (slotId in TrendlineSlotId.ALL) {
            val newAnchor = updated.slot(slotId).anchorKey()
            val state = slotStates.getValue(slotId)
            if (state.anchorKey != newAnchor) {
                state.anchorKey = newAnchor
                state.rememberedSide = PriceSide.UNKNOWN
                state.skipNextEvaluationCycle = true
                state.kissReversalTracker = null
            }
        }
    }
}