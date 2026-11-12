package net.aprlflwrs.aunthillary.server

import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicReferenceArray

/** One outgoing message: binary for the frames, text for the JSON messages. */
sealed interface Outgoing {
    class Binary(val bytes: ByteArray) : Outgoing
    class Text(val text: String) : Outgoing
}

/**
 * The messages waiting to go to one browser: one latest-value slot per message type.
 *
 * The tick loop calls [offer] and never waits. A newer message replaces an unsent older one of
 * the same type only, so a burst of ant frames can never push out a terrain or params message.
 * Because every message carries complete state, a replaced message costs nothing.
 */
class Outbox {
    enum class Slot { PARAMS, TERRAIN, STATE, PHEROMONE, ANTS }

    private val slots = AtomicReferenceArray<Outgoing?>(Slot.entries.size)
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    fun offer(slot: Slot, message: Outgoing) {
        slots.set(slot.ordinal, message)
        wakeUp.trySend(Unit)
    }

    /** Sends messages as they arrive, rarely-changing types first, until the connection closes. */
    suspend fun drain(send: suspend (Outgoing) -> Unit) {
        for (signal in wakeUp) {
            for (slot in Slot.entries) {
                val message = slots.getAndSet(slot.ordinal, null) ?: continue
                send(message)
            }
        }
    }

    fun close() {
        wakeUp.close()
    }
}
