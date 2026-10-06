package com.odesaplay.oko.connection

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks which currently-attached networks are INTERNET + VALIDATED.
 *
 * A socket teardown is only justified when this set **drains**: losing one interface (e.g. Wi-Fi
 * dropping while LTE stays up) is not an outage. Generic over the network key type so the drain
 * logic is directly unit-testable without Android framework classes.
 */
class ValidatedNetworkTracker<K : Any> {
    private val keys = ConcurrentHashMap.newKeySet<K>()

    /** @return true when the set transitioned empty -> non-empty (network restored). */
    fun add(key: K): Boolean {
        val wasEmpty = keys.isEmpty()
        keys.add(key)
        return wasEmpty && keys.isNotEmpty()
    }

    /** @return true when this removal drained the set (the key was held and the set is now empty).
     *  Removing an untracked key is an idempotent no-op and never reports a drain. */
    fun remove(key: K): Boolean {
        val existed = keys.remove(key)
        return existed && keys.isEmpty()
    }

    fun clear() = keys.clear()

    fun isEmpty(): Boolean = keys.isEmpty()
    val size: Int get() = keys.size
}
