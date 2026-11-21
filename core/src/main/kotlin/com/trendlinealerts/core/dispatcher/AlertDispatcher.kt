package com.trendlinealerts.core.dispatcher

import com.trendlinealerts.core.model.DetectedEvent

fun interface Clock {
    fun nowMs(): Long
}

object SystemClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

data class DispatcherPersistedState(
    val lastAlertSymbol: String? = null,
    val lastAlertTimeMs: Long? = null
)

data class DispatchOutcome(
    val dispatchedAlert: DetectedEvent?,
    val updatedState: DispatcherPersistedState,
    val pendingQueueSize: Int,
    val nextAlertAllowedAtMs: Long
)

class AlertDispatcher(
    private val clock: Clock,
    initialState: DispatcherPersistedState = DispatcherPersistedState(),
    private val globalCooldownMs: Long = DEFAULT_GLOBAL_COOLDOWN_MS,
    private val queueTtlMs: Long = DEFAULT_QUEUE_TTL_MS
) {
    var lastAlertSymbol: String? = initialState.lastAlertSymbol
        private set

    var lastAlertTimeMs: Long? = initialState.lastAlertTimeMs
        private set

    private val pendingBySymbol = LinkedHashMap<String, DetectedEvent>()

    fun restoreState(state: DispatcherPersistedState) {
        lastAlertSymbol = state.lastAlertSymbol
        lastAlertTimeMs = state.lastAlertTimeMs
        if (lastAlertSymbol != null) {
            pendingBySymbol.remove(lastAlertSymbol)
        }
    }

    fun currentState(): DispatcherPersistedState = DispatcherPersistedState(
        lastAlertSymbol = lastAlertSymbol,
        lastAlertTimeMs = lastAlertTimeMs
    )

    fun nextAlertAllowedAtMs(): Long {
        val lastTime = lastAlertTimeMs ?: return 0L
        return lastTime + globalCooldownMs
    }

    fun remainingCooldownMs(nowMs: Long = clock.nowMs()): Long {
        val lastTime = lastAlertTimeMs ?: return 0L
        return (lastTime + globalCooldownMs - nowMs).coerceAtLeast(0L)
    }

    fun pendingCount(nowMs: Long = clock.nowMs()): Int {
        pruneQueue(nowMs)
        return pendingBySymbol.size
    }

    fun processCycle(candidates: List<DetectedEvent>): DispatchOutcome {
        val nowMs = clock.nowMs()

        pruneQueue(nowMs)

        for (candidate in candidates) {
            if (candidate.symbol == lastAlertSymbol) {
                continue
            }
            val existing = pendingBySymbol[candidate.symbol]
            if (existing == null || isStrictlyStronger(candidate, existing)) {
                pendingBySymbol[candidate.symbol] = candidate
            }
        }

        lastAlertSymbol?.let { pendingBySymbol.remove(it) }

        val rateGateOpen = lastAlertTimeMs == null || (nowMs - lastAlertTimeMs!!) >= globalCooldownMs
        if (!rateGateOpen || pendingBySymbol.isEmpty()) {
            return DispatchOutcome(
                dispatchedAlert = null,
                updatedState = currentState(),
                pendingQueueSize = pendingBySymbol.size,
                nextAlertAllowedAtMs = nextAlertAllowedAtMs()
            )
        }

        val winner = pendingBySymbol.values.minWithOrNull(RELEASE_COMPARATOR)
        if (winner != null) {
            pendingBySymbol.remove(winner.symbol)
            lastAlertSymbol = winner.symbol
            lastAlertTimeMs = nowMs
        }

        return DispatchOutcome(
            dispatchedAlert = winner,
            updatedState = currentState(),
            pendingQueueSize = pendingBySymbol.size,
            nextAlertAllowedAtMs = nextAlertAllowedAtMs()
        )
    }

    private fun pruneQueue(nowMs: Long) {
        val iter = pendingBySymbol.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next().value
            if (nowMs - entry.detectedAtMs >= queueTtlMs) {
                iter.remove()
            }
        }
    }

    private fun isStrictlyStronger(incoming: DetectedEvent, existing: DetectedEvent): Boolean {
        val typeCmp = incoming.eventType.priorityRank.compareTo(existing.eventType.priorityRank)
        if (typeCmp != 0) return typeCmp < 0

        val gapCmp = incoming.gapPct.compareTo(existing.gapPct)
        if (gapCmp != 0) return gapCmp < 0

        val ageCmp = incoming.slot.ageRank.compareTo(existing.slot.ageRank)
        if (ageCmp != 0) return ageCmp < 0

        return incoming.slot.ordinal < existing.slot.ordinal
    }

    companion object {
        const val DEFAULT_GLOBAL_COOLDOWN_MS = 60_000L
        const val DEFAULT_QUEUE_TTL_MS = 3L * 60_000L

        private val RELEASE_COMPARATOR = Comparator<DetectedEvent> { a, b ->
            val typeCmp = a.eventType.priorityRank.compareTo(b.eventType.priorityRank)
            if (typeCmp != 0) return@Comparator typeCmp

            val gapCmp = a.gapPct.compareTo(b.gapPct)
            if (gapCmp != 0) return@Comparator gapCmp

            val timeCmp = a.detectedAtMs.compareTo(b.detectedAtMs)
            if (timeCmp != 0) return@Comparator timeCmp

            val slotCmp = a.slot.ageRank.compareTo(b.slot.ageRank)
            if (slotCmp != 0) return@Comparator slotCmp

            a.symbol.compareTo(b.symbol)
        }
    }
}