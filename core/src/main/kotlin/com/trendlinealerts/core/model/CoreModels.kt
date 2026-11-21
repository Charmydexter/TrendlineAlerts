package com.trendlinealerts.core.model

data class Candle(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val closeTime: Long
) {
    fun isClosed(nowMs: Long): Boolean = closeTime < nowMs
}

enum class Timeframe(
    val label: String,
    val binanceInterval: String,
    val bybitInterval: String,
    val intervalMs: Long
) {
    M3(label = "3m", binanceInterval = "3m", bybitInterval = "3", intervalMs = 3L * 60_000L),
    M5(label = "5m", binanceInterval = "5m", bybitInterval = "5", intervalMs = 5L * 60_000L),
    M15(label = "15m", binanceInterval = "15m", bybitInterval = "15", intervalMs = 15L * 60_000L),
    M30(label = "30m", binanceInterval = "30m", bybitInterval = "30", intervalMs = 30L * 60_000L),
    H1(label = "1H", binanceInterval = "1h", bybitInterval = "60", intervalMs = 60L * 60_000L),
    H4(label = "4H", binanceInterval = "4h", bybitInterval = "240", intervalMs = 4L * 60L * 60_000L);

    companion object {
        val DEFAULT: Timeframe = M3

        fun fromLabel(label: String): Timeframe =
            entries.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: DEFAULT
    }
}

data class Pivot(
    val value: Double,
    val timeMs: Long
)

data class PivotStackSnapshot(
    val p1: Pivot?,
    val p2: Pivot?,
    val p3: Pivot?,
    val p4: Pivot?,
    val pending: Pivot?,
    val stackSize: Int
)

enum class TrendlineSide {
    RESISTANCE,
    SUPPORT
}

enum class TrendlineSlotId(
    val side: TrendlineSide,
    val ageRank: Int
) {
    R1(TrendlineSide.RESISTANCE, ageRank = 0),
    S1(TrendlineSide.SUPPORT, ageRank = 0),
    R2(TrendlineSide.RESISTANCE, ageRank = 1),
    S2(TrendlineSide.SUPPORT, ageRank = 1),
    R3(TrendlineSide.RESISTANCE, ageRank = 2),
    S3(TrendlineSide.SUPPORT, ageRank = 2);

    companion object {
        val ALL: List<TrendlineSlotId> = listOf(R1, R2, R3, S1, S2, S3)
    }
}

data class TrendlineSlot(
    val id: TrendlineSlotId,
    val newerPivot: Pivot?,
    val olderPivot: Pivot?
) {
    val isValid: Boolean
        get() = newerPivot != null &&
            olderPivot != null &&
            newerPivot.timeMs != olderPivot.timeMs

    val slopePerMs: Double?
        get() {
            val e = newerPivot ?: return null
            val o = olderPivot ?: return null
            if (e.timeMs == o.timeMs) return null
            return (e.value - o.value) / (e.timeMs - o.timeMs).toDouble()
        }

    fun lineValue(tNowMs: Long): Double? {
        val e = newerPivot ?: return null
        val slope = slopePerMs ?: return null
        return e.value + slope * (tNowMs - e.timeMs).toDouble()
    }

    fun anchorKey(): Pair<Long, Long>? {
        if (!isValid) return null
        return newerPivot!!.timeMs to olderPivot!!.timeMs
    }
}

data class SymbolTrendlines(
    val symbol: String,
    val resistanceStack: PivotStackSnapshot,
    val supportStack: PivotStackSnapshot,
    val slots: Map<TrendlineSlotId, TrendlineSlot>,
    val lastClosedCandleOpenTime: Long?
) {
    fun slot(id: TrendlineSlotId): TrendlineSlot =
        slots[id] ?: TrendlineSlot(id, newerPivot = null, olderPivot = null)
}

enum class PriceSide(val sign: Int) {
    ABOVE(1),
    BELOW(-1),
    UNKNOWN(0);

    companion object {
        fun of(price: Double, lineValue: Double): PriceSide = when {
            price > lineValue -> ABOVE
            price < lineValue -> BELOW
            else -> UNKNOWN
        }
    }
}

enum class EventType(val priorityRank: Int) {
    CROSS(priorityRank = 0),
    TOUCH(priorityRank = 1),
    KISS(priorityRank = 2)
}

enum class KissMode {
    ENTER_ZONE,
    REVERSAL
}

data class DetectedEvent(
    val symbol: String,
    val slot: TrendlineSlotId,
    val eventType: EventType,
    val candleOpenTime: Long,
    val price: Double,
    val lineValue: Double,
    val gapPct: Double,
    val detectedAtMs: Long
) : Comparable<DetectedEvent> {

    override fun compareTo(other: DetectedEvent): Int {
        val typeCmp = eventType.priorityRank.compareTo(other.eventType.priorityRank)
        if (typeCmp != 0) return typeCmp

        val gapCmp = gapPct.compareTo(other.gapPct)
        if (gapCmp != 0) return gapCmp

        val ageCmp = slot.ageRank.compareTo(other.slot.ageRank)
        if (ageCmp != 0) return ageCmp

        return slot.ordinal.compareTo(other.slot.ordinal)
    }
}

data class SlotNearestInfo(
    val slot: TrendlineSlotId,
    val lineValue: Double,
    val signedDistancePct: Double,
    val absDistancePct: Double
)

data class SymbolEvaluationResult(
    val symbol: String,
    val candidateEvent: DetectedEvent?,
    val lastPrice: Double?,
    val nearestLine: SlotNearestInfo?,
    val anyWithinThreeXProximity: Boolean
)

data class AlertSettings(
    val timeframe: Timeframe = Timeframe.DEFAULT,
    val proximityPct: Double = 0.08,
    val kissMode: KissMode = KissMode.ENTER_ZONE,
    val enabledEvents: Set<EventType> = setOf(EventType.CROSS, EventType.TOUCH, EventType.KISS),
    val enabledSlots: Set<TrendlineSlotId> = TrendlineSlotId.ALL.toSet()
) {
    init {
        require(proximityPct in 0.01..1.0) {
            "proximityPct must be between 0.01 and 1.0, got $proximityPct"
        }
    }
}