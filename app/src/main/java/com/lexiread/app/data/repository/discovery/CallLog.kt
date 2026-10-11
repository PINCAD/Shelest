package com.lexiread.app.data.repository.discovery

import java.util.concurrent.atomic.AtomicInteger

/**
 * How many catalogue requests came back at all, across every source and every thread.
 *
 * The distinction it exists to draw is between "the catalogues answered and had nothing to
 * suggest" and "nothing answered". Those need opposite things said to the reader — mark another
 * book, versus check your connection — and an empty result list looks identical either way.
 */
internal class CallLog {
    private val answered = AtomicInteger(0)

    fun succeeded() {
        answered.incrementAndGet()
    }

    val anySucceeded: Boolean get() = answered.get() > 0
}
