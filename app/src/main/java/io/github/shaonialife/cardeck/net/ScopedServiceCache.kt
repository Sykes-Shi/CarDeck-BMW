package io.github.shaonialife.cardeck.net

/** A discovery result is reusable only inside one bound network and selected vehicle. */
internal class ScopedServiceCache<T> {
    data class Scope(val networkHandle: Long, val ssid: String, val targetAddress: String)
    data class Token(val generation: Long, val service: String, val revision: Long)

    private var scope: Scope? = null
    private var generation = 0L
    private var active = false
    private var value: T? = null
    private var valueService: String? = null
    private val revisions = mutableMapOf<String, Long>()

    @Synchronized
    fun selectScope(next: Scope?): Long {
        // An unidentified network/vehicle must never reuse an earlier handoff's result.
        if (active && next != null && next == scope) return generation
        clear()
        scope = next
        active = true
        return generation
    }

    @Synchronized
    fun clear() {
        generation++
        active = false
        scope = null
        value = null
        valueService = null
        revisions.clear()
    }

    @Synchronized
    fun isCurrent(expectedGeneration: Long): Boolean = active && generation == expectedGeneration

    @Synchronized
    fun beginResolve(expectedGeneration: Long, service: String): Token? {
        if (!isCurrent(expectedGeneration)) return null
        return Token(generation, service, revisions.getOrPut(service) { 0L })
    }

    @Synchronized
    fun isCurrent(token: Token): Boolean =
        isCurrent(token.generation) && revisions[token.service] == token.revision

    @Synchronized
    fun publish(token: Token, resolved: T): Boolean {
        if (!isCurrent(token)) return false
        value = resolved
        valueService = token.service
        return true
    }

    @Synchronized
    fun serviceLost(expectedGeneration: Long, service: String) {
        if (!isCurrent(expectedGeneration)) return
        revisions[service] = (revisions[service] ?: 0L) + 1L
        if (valueService == service) {
            value = null
            valueService = null
        }
    }

    @Synchronized
    fun get(): T? = if (active) value else null
}