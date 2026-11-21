package com.trendlinealerts.core

import com.trendlinealerts.core.detector.EventDetector
import com.trendlinealerts.core.dispatcher.AlertDispatcher
import com.trendlinealerts.core.dispatcher.Clock
import com.trendlinealerts.core.dispatcher.DispatcherPersistedState
import com.trendlinealerts.core.engine.PivotStack
import com.trendlinealerts.core.engine.SymbolEngineState
import com.trendlinealerts.core.engine.TrendlineEngine
import com.trendlinealerts.core.model.AlertSettings
import com.trendlinealerts.core.model.Candle
import com.trendlinealerts.core.model.DetectedEvent
import com.trendlinealerts.core.model.EventType
import com.trendlinealerts.core.model.KissMode
import com.trendlinealerts.core.model.Pivot
import com.trendlinealerts.core.model.PivotStackSnapshot
import com.trendlinealerts.core.model.PriceSide
import com.trendlinealerts.core.model.SymbolTrendlines
import com.trendlinealerts.core.model.TrendlineSlot
import com.trendlinealerts.core.model.TrendlineSlotId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PivotDetectionTest {

    private val engine = TrendlineEngine()

    private fun candle(index: Int, high: Double, low: Double): Candle {
        val open = index * 180_000L
        return Candle(
            openTime = open,
            open = (high + low) / 2.0,
            high = high,
            low = low,
            close = (high + low) / 2.0,
            closeTime = open + 179_999L
        )
    }

    @Test
    fun `detects strict high pivot and ignores current bar j`() {
        val candles = listOf(
            candle(0, high = 10.0, low = 5.0),
            candle(1, high = 12.0, low = 6.0),
            candle(2, high = 15.0, low = 7.0),
            candle(3, high = 13.0, low = 6.0),
            candle(4, high = 11.0, low = 5.0),
            candle(5, high = 999.0, low = 1.0)
        )

        val result = engine.compute("BTCUSDT", candles)
        assertEquals(15.0, result.resistanceStack.p1?.value!!, 1e-9)
        assertEquals(candles[2].openTime, result.resistanceStack.p1?.timeMs)
    }

    @Test
    fun `equal highs do not form a pivot due to strict inequality`() {
        val flatPeakCandles = listOf(
            candle(0, high = 10.0, low = 5.0),
            candle(1, high = 12.0, low = 6.0),
            candle(2, high = 15.0, low = 7.0),
            candle(3, high = 15.0, low = 6.0),
            candle(4, high = 11.0, low = 5.0),
            candle(5, high = 9.0, low = 4.0)
        )

        val result = engine.compute("BTCUSDT", flatPeakCandles)
        assertNull(result.resistanceStack.p1)
        assertEquals(0, result.resistanceStack.stackSize)
    }

    @Test
    fun `equal lows do not form a pivot due to strict inequality`() {
        val flatBottomCandles = listOf(
            candle(0, high = 20.0, low = 10.0),
            candle(1, high = 19.0, low = 8.0),
            candle(2, high = 18.0, low = 8.0),
            candle(3, high = 19.0, low = 9.0),
            candle(4, high = 20.0, low = 11.0),
            candle(5, high = 21.0, low = 12.0)
        )

        val result = engine.compute("BTCUSDT", flatBottomCandles)
        assertNull(result.supportStack.p1)
        assertEquals(0, result.supportStack.stackSize)
    }

    @Test
    fun `detects strict low pivot`() {
        val candles = listOf(
            candle(0, high = 20.0, low = 10.0),
            candle(1, high = 19.0, low = 8.0),
            candle(2, high = 18.0, low = 5.0),
            candle(3, high = 19.0, low = 7.0),
            candle(4, high = 20.0, low = 9.0),
            candle(5, high = 21.0, low = 1.0)
        )

        val result = engine.compute("BTCUSDT", candles)
        assertEquals(5.0, result.supportStack.p1?.value!!, 1e-9)
        assertEquals(candles[2].openTime, result.supportStack.p1?.timeMs)
    }

    @Test
    fun `forming candle is dropped before pivot detection`() {
        val candles = listOf(
            candle(0, high = 10.0, low = 5.0),
            candle(1, high = 12.0, low = 6.0),
            candle(2, high = 15.0, low = 7.0),
            candle(3, high = 13.0, low = 6.0),
            candle(4, high = 11.0, low = 5.0),
            Candle(
                openTime = 5 * 180_000L,
                open = 10.0,
                high = 10.5,
                low = 9.5,
                close = 10.0,
                closeTime = 6 * 180_000L
            )
        )

        val nowMs = 5 * 180_000L + 60_000L
        val result = engine.computeFromRaw("BTCUSDT", candles, nowMs)
        assertNull(result.resistanceStack.p1)
    }
}

class PivotStackTest {

    @Test
    fun `resistance lower high is added directly and higher high becomes pending`() {
        val stack = PivotStack(dir = +1.0)

        stack.update(100.0, 1L, isNew = true)
        stack.update(90.0, 2L, isNew = true)
        assertEquals(listOf(100.0, 90.0), stack.valuesOldestToNewest().map { it.value })
        assertNull(stack.snapshot().pending)

        stack.update(95.0, 3L, isNew = true)
        val snap = stack.snapshot()
        assertEquals(90.0, snap.p1?.value!!, 1e-9)
        assertEquals(100.0, snap.p2?.value!!, 1e-9)
        assertEquals(95.0, snap.pending?.value!!, 1e-9)
        assertEquals(2, snap.stackSize)
    }

    @Test
    fun `more extreme pivot replaces pending and inside pivot promotes pending and pops outranked pivots`() {
        val stack = PivotStack(dir = +1.0)

        stack.update(100.0, 1L, isNew = true)
        stack.update(90.0, 2L, isNew = true)
        stack.update(85.0, 3L, isNew = true)

        stack.update(92.0, 4L, isNew = true)
        assertEquals(92.0, stack.snapshot().pending?.value!!, 1e-9)

        stack.update(96.0, 5L, isNew = true)
        assertEquals(96.0, stack.snapshot().pending?.value!!, 1e-9)

        stack.update(88.0, 6L, isNew = true)
        val values = stack.valuesOldestToNewest().map { it.value }
        assertEquals(listOf(100.0, 96.0, 88.0), values)
        assertNull(stack.snapshot().pending)

        val snap = stack.snapshot()
        assertEquals(88.0, snap.p1?.value!!, 1e-9)
        assertEquals(96.0, snap.p2?.value!!, 1e-9)
        assertEquals(100.0, snap.p3?.value!!, 1e-9)
        assertNull(snap.p4)
    }

    @Test
    fun `support stack remains strictly ascending across pending promotions`() {
        val stack = PivotStack(dir = -1.0)

        stack.update(50.0, 1L, isNew = true)
        stack.update(60.0, 2L, isNew = true)
        stack.update(70.0, 3L, isNew = true)

        stack.update(58.0, 4L, isNew = true)
        stack.update(55.0, 5L, isNew = true)
        assertEquals(55.0, stack.snapshot().pending?.value!!, 1e-9)

        stack.update(62.0, 6L, isNew = true)
        val values = stack.valuesOldestToNewest().map { it.value }
        assertEquals(listOf(50.0, 55.0, 62.0), values)
        assertNull(stack.snapshot().pending)

        for (i in 1 until values.size) {
            assertTrue(values[i] > values[i - 1])
        }
    }

    @Test
    fun `stack is capped at 50 entries dropping oldest`() {
        val stack = PivotStack(dir = +1.0, maxSize = 50)
        for (i in 1..60) {
            stack.update(1000.0 - i, i.toLong(), isNew = true)
        }
        val values = stack.valuesOldestToNewest()
        assertEquals(50, values.size)
        assertEquals(11L, values.first().timeMs)
        assertEquals(989.0, values.first().value, 1e-9)
        assertEquals(60L, values.last().timeMs)
        assertEquals(940.0, values.last().value, 1e-9)
    }
}

class TrendlineEngineTest {

    @Test
    fun `slot slope and extrapolated line value match exact Double math`() {
        val p2 = Pivot(value = 100.0, timeMs = 1_000_000L)
        val p1 = Pivot(value = 90.0, timeMs = 2_000_000L)
        val r1 = TrendlineSlot(TrendlineSlotId.R1, newerPivot = p1, olderPivot = p2)

        assertTrue(r1.isValid)
        assertEquals(-1e-5, r1.slopePerMs!!, 1e-12)
        assertEquals(75.0, r1.lineValue(3_500_000L)!!, 1e-9)
    }

    @Test
    fun `swap resets remembered side and skips one evaluation cycle to prevent false CROSS`() {
        val state = SymbolEngineState("BTCUSDT")
        val detector = EventDetector()
        val settings = AlertSettings()

        val initialTrendlines = makeTrendlines(
            r1 = TrendlineSlot(
                TrendlineSlotId.R1,
                newerPivot = Pivot(100.0, 2_000_000L),
                olderPivot = Pivot(110.0, 1_000_000L)
            )
        )
        state.applyTrendlines(initialTrendlines)

        val warmupCandle = Candle(2_000_000L, 95.0, 96.0, 94.0, 95.0, 2_059_999L)
        val res1 = detector.evaluate(state, null, warmupCandle, 2_000_000L, settings)
        assertNull(res1.candidateEvent)
        assertEquals(PriceSide.BELOW, state.slotState(TrendlineSlotId.R1).rememberedSide)
        assertFalse(state.slotState(TrendlineSlotId.R1).skipNextEvaluationCycle)

        val swappedTrendlines = makeTrendlines(
            r1 = TrendlineSlot(
                TrendlineSlotId.R1,
                newerPivot = Pivot(80.0, 2_100_000L),
                olderPivot = Pivot(90.0, 1_500_000L)
            )
        )
        state.applyTrendlines(swappedTrendlines)
        assertEquals(PriceSide.UNKNOWN, state.slotState(TrendlineSlotId.R1).rememberedSide)
        assertTrue(state.slotState(TrendlineSlotId.R1).skipNextEvaluationCycle)

        val postSwapCandle = Candle(2_100_000L, 79.0, 96.0, 78.0, 95.0, 2_159_999L)
        val res2 = detector.evaluate(state, null, postSwapCandle, 2_100_000L, settings)
        assertNull(res2.candidateEvent)
        assertEquals(PriceSide.ABOVE, state.slotState(TrendlineSlotId.R1).rememberedSide)
        assertFalse(state.slotState(TrendlineSlotId.R1).skipNextEvaluationCycle)
    }

    @Test
    fun `lines never die when price goes far beyond them`() {
        val state = SymbolEngineState("ETHUSDT")
        val r1 = TrendlineSlot(
            TrendlineSlotId.R1,
            newerPivot = Pivot(100.0, 200_000L),
            olderPivot = Pivot(110.0, 100_000L)
        )
        state.applyTrendlines(makeTrendlines(r1 = r1))

        val detector = EventDetector()
        val settings = AlertSettings()

        detector.evaluate(
            state,
            null,
            Candle(200_000L, 90.0, 91.0, 89.0, 90.0, 259_999L),
            200_000L,
            settings
        )

        detector.evaluate(
            state,
            null,
            Candle(260_000L, 190.0, 205.0, 185.0, 200.0, 319_999L),
            300_000L,
            settings
        )

        val slot = state.currentTrendlines!!.slot(TrendlineSlotId.R1)
        assertTrue(slot.isValid)
        assertNotNull(slot.lineValue(1_000_000L))
        assertEquals(20.0, slot.lineValue(1_000_000L)!!, 1e-9)
    }

    private fun makeTrendlines(r1: TrendlineSlot): SymbolTrendlines {
        val emptySnap = PivotStackSnapshot(null, null, null, null, null, 0)
        val slots = TrendlineSlotId.ALL.associateWith { id ->
            if (id == TrendlineSlotId.R1) r1 else TrendlineSlot(id, null, null)
        }
        return SymbolTrendlines("BTCUSDT", emptySnap, emptySnap, slots, null)
    }
}

class EventDetectorTest {

    private fun flatR1State(lineLevel: Double, symbol: String = "BTCUSDT"): SymbolEngineState {
        val r1 = TrendlineSlot(
            TrendlineSlotId.R1,
            newerPivot = Pivot(lineLevel, 2_000L),
            olderPivot = Pivot(lineLevel, 1_000L)
        )
        return stateWithSlots(symbol, mapOf(TrendlineSlotId.R1 to r1))
    }

    private fun stateWithSlots(
        symbol: String,
        customSlots: Map<TrendlineSlotId, TrendlineSlot>
    ): SymbolEngineState {
        val state = SymbolEngineState(symbol)
        val emptySnap = PivotStackSnapshot(null, null, null, null, null, 0)
        val allSlots = TrendlineSlotId.ALL.associateWith { id ->
            customSlots[id] ?: TrendlineSlot(id, null, null)
        }
        state.applyTrendlines(SymbolTrendlines(symbol, emptySnap, emptySnap, allSlots, null))
        return state
    }

    @Test
    fun `detects CROSS when candle straddles and close flips side`() {
        val state = flatR1State(100.0)
        val detector = EventDetector()
        val settings = AlertSettings()

        assertNull(
            detector.evaluate(
                state,
                null,
                Candle(10_000L, 95.0, 96.0, 94.0, 95.0, 69_999L),
                20_000L,
                settings
            ).candidateEvent
        )

        val res = detector.evaluate(
            state,
            null,
            Candle(70_000L, 98.0, 102.0, 97.0, 101.0, 129_999L),
            80_000L,
            settings
        )
        assertEquals(EventType.CROSS, res.candidateEvent?.eventType)
    }

    @Test
    fun `detects TOUCH when candle straddles and close remains on remembered side`() {
        val state = flatR1State(100.0)
        val detector = EventDetector()
        val settings = AlertSettings()

        detector.evaluate(
            state,
            null,
            Candle(10_000L, 95.0, 96.0, 94.0, 95.0, 69_999L),
            20_000L,
            settings
        )

        val res = detector.evaluate(
            state,
            null,
            Candle(70_000L, 98.0, 101.0, 97.5, 99.0, 129_999L),
            80_000L,
            settings
        )
        assertEquals(EventType.TOUCH, res.candidateEvent?.eventType)
    }

    @Test
    fun `detects KISS inside proximityPct and ignores outside proximityPct`() {
        val state = flatR1State(1000.0)
        val detector = EventDetector()
        val settings = AlertSettings(proximityPct = 0.08, kissMode = KissMode.ENTER_ZONE)

        detector.evaluate(
            state,
            null,
            Candle(10_000L, 990.0, 995.0, 989.0, 992.0, 69_999L),
            20_000L,
            settings
        )

        val outsideRes = detector.evaluate(
            state,
            null,
            Candle(70_000L, 998.0, 999.0, 997.0, 998.5, 129_999L),
            80_000L,
            settings
        )
        assertNull(outsideRes.candidateEvent)

        val insideRes = detector.evaluate(
            state,
            null,
            Candle(130_000L, 998.5, 999.4, 998.0, 999.2, 189_999L),
            140_000L,
            settings
        )
        assertEquals(EventType.KISS, insideRes.candidateEvent?.eventType)
        assertEquals(0.06, insideRes.candidateEvent!!.gapPct, 1e-6)
    }

    @Test
    fun `REVERSAL kiss mode fires only after entering zone without touch and flipping back by 30 percent`() {
        val state = flatR1State(1000.0)
        val detector = EventDetector()
        val settings = AlertSettings(proximityPct = 0.10, kissMode = KissMode.REVERSAL)

        detector.evaluate(
            state,
            null,
            Candle(10_000L, 995.0, 996.0, 994.0, 995.0, 69_999L),
            20_000L,
            settings
        )

        val enterRes = detector.evaluate(
            state,
            null,
            Candle(70_000L, 999.0, 999.8, 998.9, 999.7, 129_999L),
            80_000L,
            settings
        )
        assertNull(enterRes.candidateEvent)

        val flipRes = detector.evaluate(
            state,
            null,
            Candle(130_000L, 999.7, 999.7, 999.3, 999.4, 189_999L),
            140_000L,
            settings
        )
        assertEquals(EventType.KISS, flipRes.candidateEvent?.eventType)
        assertEquals(0.02, flipRes.candidateEvent!!.gapPct, 1e-6)
    }

    @Test
    fun `priority picks CROSS over TOUCH over KISS and newest slot on ties`() {
        val r1 = TrendlineSlot(TrendlineSlotId.R1, Pivot(100.0, 2_000L), Pivot(100.0, 1_000L))
        val r2 = TrendlineSlot(TrendlineSlotId.R2, Pivot(100.0, 2_000L), Pivot(100.0, 1_000L))
        val s1 = TrendlineSlot(TrendlineSlotId.S1, Pivot(95.0, 2_000L), Pivot(95.0, 1_000L))
        val state = stateWithSlots(
            "SOLUSDT",
            mapOf(TrendlineSlotId.R1 to r1, TrendlineSlotId.R2 to r2, TrendlineSlotId.S1 to s1)
        )
        val detector = EventDetector()
        val settings = AlertSettings(proximityPct = 0.50)

        detector.evaluate(
            state,
            null,
            Candle(10_000L, 97.0, 97.5, 96.5, 97.0, 69_999L),
            20_000L,
            settings
        )

        val res = detector.evaluate(
            state,
            null,
            Candle(70_000L, 97.0, 102.0, 94.8, 101.0, 129_999L),
            80_000L,
            settings
        )
        assertNotNull(res.candidateEvent)
        assertEquals(EventType.CROSS, res.candidateEvent!!.eventType)
        assertEquals(TrendlineSlotId.R1, res.candidateEvent!!.slot)
    }

    @Test
    fun `dedupes same symbol slot candleOpenTime and eventType across polls`() {
        val state = flatR1State(1000.0)
        val detector = EventDetector()
        val settings = AlertSettings(proximityPct = 0.10)

        detector.evaluate(
            state,
            null,
            Candle(10_000L, 995.0, 996.0, 994.0, 995.0, 69_999L),
            20_000L,
            settings
        )

        val candle = Candle(70_000L, 999.0, 999.5, 998.8, 999.2, 129_999L)
        val first = detector.evaluate(state, null, candle, 80_000L, settings)
        assertEquals(EventType.KISS, first.candidateEvent?.eventType)

        val second = detector.evaluate(
            state,
            candle,
            Candle(130_000L, 998.5, 998.5, 998.0, 998.2, 189_999L),
            140_000L,
            settings
        )
        assertNull(second.candidateEvent)
    }
}

class AlertDispatcherTest {

    private class FakeClock(var timeMs: Long) : Clock {
        override fun nowMs(): Long = timeMs
        fun advance(ms: Long) {
            timeMs += ms
        }
    }

    private fun event(
        symbol: String,
        type: EventType = EventType.KISS,
        gapPct: Double = 0.05,
        detectedAtMs: Long,
        slot: TrendlineSlotId = TrendlineSlotId.R1
    ) = DetectedEvent(
        symbol = symbol,
        slot = slot,
        eventType = type,
        candleOpenTime = detectedAtMs - 1000L,
        price = 100.0,
        lineValue = 100.05,
        gapPct = gapPct,
        detectedAtMs = detectedAtMs
    )

    @Test
    fun `enforces max one alert per 60 seconds globally`() {
        val clock = FakeClock(100_000L)
        val dispatcher = AlertDispatcher(clock)

        val out1 = dispatcher.processCycle(listOf(event("BTCUSDT", detectedAtMs = clock.nowMs())))
        assertEquals("BTCUSDT", out1.dispatchedAlert?.symbol)

        clock.advance(30_000L)
        val out2 = dispatcher.processCycle(listOf(event("ETHUSDT", detectedAtMs = clock.nowMs())))
        assertNull(out2.dispatchedAlert)
        assertEquals(1, out2.pendingQueueSize)

        clock.advance(30_000L)
        val out3 = dispatcher.processCycle(emptyList())
        assertEquals("ETHUSDT", out3.dispatchedAlert?.symbol)
        assertEquals(0, out3.pendingQueueSize)
    }

    @Test
    fun `lastAlertSymbol is locked until a different symbol alerts then unlocks`() {
        val clock = FakeClock(100_000L)
        val dispatcher = AlertDispatcher(clock)

        val out1 = dispatcher.processCycle(listOf(event("BTCUSDT", detectedAtMs = clock.nowMs())))
        assertEquals("BTCUSDT", out1.dispatchedAlert?.symbol)

        clock.advance(60_000L)
        val out2 = dispatcher.processCycle(listOf(event("BTCUSDT", EventType.CROSS, detectedAtMs = clock.nowMs())))
        assertNull(out2.dispatchedAlert)
        assertEquals(0, out2.pendingQueueSize)

        clock.advance(60_000L)
        val out3 = dispatcher.processCycle(listOf(event("ETHUSDT", detectedAtMs = clock.nowMs())))
        assertEquals("ETHUSDT", out3.dispatchedAlert?.symbol)

        clock.advance(60_000L)
        val out4 = dispatcher.processCycle(emptyList())
        assertNull(out4.dispatchedAlert)

        clock.advance(60_000L)
        val out5 = dispatcher.processCycle(listOf(event("BTCUSDT", detectedAtMs = clock.nowMs())))
        assertEquals("BTCUSDT", out5.dispatchedAlert?.symbol)
    }

    @Test
    fun `queue respects 3 minute TTL and picks strongest then oldest detection`() {
        val clock = FakeClock(100_000L)
        val dispatcher = AlertDispatcher(clock)

        dispatcher.processCycle(listOf(event("AAVEUSDT", detectedAtMs = clock.nowMs())))

        clock.advance(10_000L)
        dispatcher.processCycle(listOf(event("SOLUSDT", EventType.KISS, 0.05, detectedAtMs = 110_000L)))

        clock.advance(5_000L)
        dispatcher.processCycle(listOf(event("LINKUSDT", EventType.KISS, 0.05, detectedAtMs = 115_000L)))

        clock.advance(45_000L)
        val out1 = dispatcher.processCycle(emptyList())
        assertEquals("SOLUSDT", out1.dispatchedAlert?.symbol)
        assertEquals(1, out1.pendingQueueSize)

        clock.timeMs = 295_000L
        val out2 = dispatcher.processCycle(emptyList())
        assertNull(out2.dispatchedAlert)
        assertEquals(0, out2.pendingQueueSize)
    }

    @Test
    fun `restored persisted state enforces Rule A and Rule B across restarts`() {
        val clock = FakeClock(500_000L)
        val dispatcher = AlertDispatcher(
            clock = clock,
            initialState = DispatcherPersistedState(
                lastAlertSymbol = "BTCUSDT",
                lastAlertTimeMs = 470_000L
            )
        )

        val out1 = dispatcher.processCycle(listOf(event("BTCUSDT", detectedAtMs = clock.nowMs())))
        assertNull(out1.dispatchedAlert)
        assertEquals(0, out1.pendingQueueSize)

        val out2 = dispatcher.processCycle(listOf(event("ETHUSDT", detectedAtMs = clock.nowMs())))
        assertNull(out2.dispatchedAlert)
        assertEquals(1, out2.pendingQueueSize)

        clock.advance(30_000L)
        val out3 = dispatcher.processCycle(emptyList())
        assertEquals("ETHUSDT", out3.dispatchedAlert?.symbol)
    }
}