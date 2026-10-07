package com.odesaplay.oko

import kotlin.random.Random

/**
 * Deals bodies without repeating back to back, and reshuffles when it runs out.
 *
 * A plain random pick is the wrong shape here: it can hand the same style twice in a row and can
 * skip one all day. A bag guarantees every style is seen before any repeat, without making the
 * order predictable. It reshuffles only when empty — no timer, no UI, nothing to press.
 *
 * Queues are kept per threat kind so a ballistic hit never waits behind a drone queue, and so a
 * style that suits ballistic hits is only dealt for them.
 */
class ShuffleBag(
    private val bodies: List<ExplosionBody>,
    private val random: Random = Random.Default
) {
    private val order = mutableMapOf<EliteKind, ArrayDeque<ExplosionBody>>()
    private val lock = Any()

    /** Bodies that suit [kind]: those that named it, plus those that suit everything. */
    private fun eligible(kind: EliteKind): List<ExplosionBody> =
        bodies.filter { it.kinds.isEmpty() || kind in it.kinds }

    /** The next body for [kind], reshuffling that kind's queue when it empties. */
    fun deal(kind: EliteKind): ExplosionBody {
        val candidates = eligible(kind).ifEmpty { bodies }
        synchronized(lock) {
            val queue = order.getOrPut(kind) { ArrayDeque(candidates.shuffled(random)) }
            if (queue.isEmpty()) {
                queue.addAll(candidates.shuffled(random))
            }
            return queue.removeFirst()
        }
    }
}