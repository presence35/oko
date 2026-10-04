package com.odesaplay.oko.service

/**
 * Latest-wins holder for a sound requested before its SoundPool sample finished loading.
 *
 * SoundPool loads asynchronously, so a siren fired on a cold start can arrive before its buffer
 * is ready and be dropped. This queues the newest request and replays it the moment the sample
 * reports loaded. Pure (no Android types) so the policy is unit-testable.
 */
class PendingAlarm {

    data class Sound(
        val sampleId: Int,
        val leftVol: Float,
        val rightVol: Float,
        val priority: Int,
        val loopCount: Int,
        val rate: Float
    )

    enum class Decision { PLAY, QUEUE, DROP }

    private var pending: Sound? = null

    /** A play request: PLAY when [loaded], QUEUE (replacing any older request) otherwise. */
    fun onRequest(sound: Sound, loaded: Boolean): Decision {
        if (sound.sampleId == 0) return Decision.DROP
        if (loaded) {
            pending = null
            return Decision.PLAY
        }
        pending = sound
        return Decision.QUEUE
    }

    /** A sample finished loading: returns the queued sound when it was the pending one AND [ok]. */
    fun onLoaded(sampleId: Int, ok: Boolean): Sound? {
        val p = pending ?: return null
        if (p.sampleId != sampleId) return null
        pending = null
        return if (ok) p else null
    }

    fun clear() {
        pending = null
    }
}
