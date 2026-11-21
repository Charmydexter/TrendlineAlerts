package com.trendlinealerts.core.detector

import com.trendlinealerts.core.engine.KissReversalTracker
import com.trendlinealerts.core.engine.SlotRuntimeState
import com.trendlinealerts.core.engine.SymbolEngineState
import com.trendlinealerts.core.model.AlertSettings
import com.trendlinealerts.core.model.Candle
import com.trendlinealerts.core.model.DetectedEvent
import com.trendlinealerts.core.model.EventType
import com.trendlinealerts.core.model.KissMode
import com.trendlinealerts.core.model.PriceSide
import com.trendlinealerts.core.model.SlotNearestInfo
import com.trendlinealerts.core.model.SymbolEvaluationResult
import com.trendlinealerts.core.model.TrendlineSlotId
import kotlin.math.abs
import kotlin.math.min

class EventDetector(
    private val dedupeRetentionMs: Long = DEFAULT_DEDUPE_RETENTION_MS
) {
    private data class DedupeKey(
        val symbol: String,
        val slot: TrendlineSlotId,
        val candleOpenTime: Long,
        val eventType: EventType
    )

    private val seenKeys = HashMap<DedupeKey, Long>()

    fun evaluate(
        symbolState: SymbolEngineState,
        lastClosed1m: Candle?,
        forming1m: Candle?,
        nowMs: Long,
        settings: AlertSettings
    ): SymbolEvaluationResult {
        pruneOldDedupeKeys(nowMs)

        val trendlines = symbolState.currentTrendlines
        val latestCandle = forming1m ?: lastClosed1m
        val lastPrice = latestCandle?.close

        if (trendlines == null || latestCandle == null || lastPrice == null) {
            return SymbolEvaluationResult(
                symbol = symbolState.symbol,
                candidateEvent = null,
                lastPrice = lastPrice,
                nearestLine = null,
                anyWithinThreeXProximity = false
            )
        }

        val candlesToEvaluate = buildList {
            if (lastClosed1m != null) add(lastClosed1m)
            if (forming1m != null && forming1m.openTime != lastClosed1m?.openTime) {
                add(forming1m)
            } else if (forming1m != null && lastClosed1m == null) {
                add(forming1m)
            }
        }

        val candidates = ArrayList<DetectedEvent>()
        var nearestLine: SlotNearestInfo? = null
        var anyWithinThreeX = false

        for (slotId in TrendlineSlotId.ALL) {
            val slot = trendlines.slot(slotId)
            val slotState = symbolState.slotState(slotId)

            if (!slot.isValid) {
                slotState.rememberedSide = PriceSide.UNKNOWN
                slotState.skipNextEvaluationCycle = true
                slotState.kissReversalTracker = null
                continue
            }

            val lineValue = slot.lineValue(nowMs) ?: continue
            if (lineValue <= 0.0) continue

            val signedDistPct = ((lastPrice - lineValue) / lineValue) * 100.0
            val absDistPct = abs(signedDistPct)
            if (absDistPct <= settings.proximityPct * 3.0) {
                anyWithinThreeX = true
            }
            if (nearestLine == null || absDistPct < nearestLine.absDistancePct) {
                nearestLine = SlotNearestInfo(
                    slot = slotId,
                    lineValue = lineValue,
                    signedDistancePct = signedDistPct,
                    absDistancePct = absDistPct
                )
            }

            if (slotState.skipNextEvaluationCycle || slotState.rememberedSide == PriceSide.UNKNOWN) {
                val sideNow = PriceSide.of(lastPrice, lineValue)
                if (sideNow != PriceSide.UNKNOWN) {
                    slotState.rememberedSide = sideNow
                    slotState.skipNextEvaluationCycle = false
                }
                slotState.kissReversalTracker = null
                continue
            }

            val slotEnabled = slotId in settings.enabledSlots

            for (candle in candlesToEvaluate) {
                val detected = evaluateCandleAgainstSlot(
                    symbol = symbolState.symbol,
                    slotId = slotId,
                    slotState = slotState,
                    candle = candle,
                    lineValue = lineValue,
                    nowMs = nowMs,
                    settings = settings
                )

                val candleCloseSide = PriceSide.of(candle.close, lineValue)
                if (candleCloseSide != PriceSide.UNKNOWN) {
                    slotState.rememberedSide = candleCloseSide
                }

                if (detected != null && slotEnabled && detected.eventType in settings.enabledEvents) {
                    val key = DedupeKey(
                        symbol = detected.symbol,
                        slot = detected.slot,
                        candleOpenTime = detected.candleOpenTime,
                        eventType = detected.eventType
                    )
                    if (!seenKeys.containsKey(key)) {
                        seenKeys[key] = nowMs
                        candidates.add(detected)
                    }
                }
            }
        }

        val bestCandidate = candidates.minOrNull()

        return SymbolEvaluationResult(
            symbol = symbolState.symbol,
            candidateEvent = bestCandidate,
            lastPrice = lastPrice,
            nearestLine = nearestLine,
            anyWithinThreeXProximity = anyWithinThreeX
        )
    }

    private fun evaluateCandleAgainstSlot(
        symbol: String,
        slotId: TrendlineSlotId,
        slotState: SlotRuntimeState,
        candle: Candle,
        lineValue: Double,
        nowMs: Long,
        settings: AlertSettings
    ): DetectedEvent? {
        val straddle = candle.low <= lineValue && lineValue <= candle.high
        val currentSide = PriceSide.of(candle.close, lineValue)
        val prevSide = slotState.rememberedSide

        if (straddle) {
            slotState.kissReversalTracker = null

            val eventType = when {
                prevSide != PriceSide.UNKNOWN &&
                    currentSide != PriceSide.UNKNOWN &&
                    currentSide != prevSide -> EventType.CROSS
                prevSide != PriceSide.UNKNOWN -> EventType.TOUCH
                else -> return null
            }

            return DetectedEvent(
                symbol = symbol,
                slot = slotId,
                eventType = eventType,
                candleOpenTime = candle.openTime,
                price = candle.close,
                lineValue = lineValue,
                gapPct = 0.0,
                detectedAtMs = nowMs
            )
        }

        val lineAboveCandle = lineValue > candle.high
        val gapRatio = if (lineAboveCandle) {
            (lineValue - candle.high) / lineValue
        } else {
            (candle.low - lineValue) / lineValue
        }
        val gapPct = gapRatio * 100.0
        val candleSide = if (lineAboveCandle) PriceSide.BELOW else PriceSide.ABOVE
        val inProximity = gapPct <= settings.proximityPct

        return when (settings.kissMode) {
            KissMode.ENTER_ZONE -> {
                if (!inProximity) return null
                val referencePrice = if (lineAboveCandle) candle.high else candle.low
                DetectedEvent(
                    symbol = symbol,
                    slot = slotId,
                    eventType = EventType.KISS,
                    candleOpenTime = candle.openTime,
                    price = referencePrice,
                    lineValue = lineValue,
                    gapPct = gapPct,
                    detectedAtMs = nowMs
                )
            }

            KissMode.REVERSAL -> {
                val existingTracker = slotState.kissReversalTracker
                val tracker = when {
                    existingTracker != null && existingTracker.approachSide == candleSide -> {
                        if (inProximity) {
                            existingTracker.copy(minGapPct = min(existingTracker.minGapPct, gapPct))
                        } else {
                            existingTracker
                        }
                    }
                    inProximity -> KissReversalTracker(approachSide = candleSide, minGapPct = gapPct)
                    else -> {
                        slotState.kissReversalTracker = null
                        return null
                    }
                }
                slotState.kissReversalTracker = tracker

                val closeGapPct = abs(lineValue - candle.close) / lineValue * 100.0
                val reversalThresholdPct = settings.proximityPct * REVERSAL_FLIP_FRACTION
                val flippedAway = currentSide == tracker.approachSide &&
                    (closeGapPct - tracker.minGapPct) >= reversalThresholdPct

                if (flippedAway) {
                    val bestMinGap = tracker.minGapPct
                    slotState.kissReversalTracker = null
                    DetectedEvent(
                        symbol = symbol,
                        slot = slotId,
                        eventType = EventType.KISS,
                        candleOpenTime = candle.openTime,
                        price = candle.close,
                        lineValue = lineValue,
                        gapPct = bestMinGap,
                        detectedAtMs = nowMs
                    )
                } else {
                    null
                }
            }
        }
    }

    private fun pruneOldDedupeKeys(nowMs: Long) {
        val cutoff = nowMs - dedupeRetentionMs
        val iter = seenKeys.entries.iterator()
        while (iter.hasNext()) {
            if (iter.next().value < cutoff) {
                iter.remove()
            }
        }
    }

    companion object {
        const val REVERSAL_FLIP_FRACTION = 0.30
        const val DEFAULT_DEDUPE_RETENTION_MS = 60L * 60_000L
    }
}