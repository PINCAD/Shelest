package com.shelest.shared.sync

/** Pure session state. Platform adapters serialize access; this object performs no I/O. */
class SyncCoordinator {
    var owner: String = ""; private set
    var cursor: Long = 0; private set
    var generation: Int = 0; private set
    var readerOpen: Boolean = false; private set
    private var entitlement = ""
    private var serial = 0
    private var running: Int? = null
    private var runGeneration = 0

    /** True means invalidate platform sent/file caches; never touch local books or credentials. */
    fun updateAccount(account: CloudAccount?): Boolean {
        val next = account?.entitlementKey.orEmpty()
        if (entitlement == next) return false
        owner = account?.id.orEmpty(); entitlement = next
        resetCursor(); generation++
        return true
    }
    fun resetCursor() { cursor = 0 }
    fun invalidate() { generation++; resetCursor() }
    fun setReading(value: Boolean) {
        if (value == readerOpen) return
        readerOpen = value
        if (value) generation++ // Cancel a library run, not explicit reader-position publishing.
    }
    fun begin(): Int {
        if (owner.isBlank() || readerOpen || running != null) return -1
        val run = ++serial
        running = run; runGeneration = generation
        return run
    }
    fun isCurrent(run: Int): Boolean = running == run && runGeneration == generation && !readerOpen && owner.isNotBlank()
    fun acceptCursor(run: Int, time: Long): Boolean {
        if (!isCurrent(run) || time < 0) return false
        cursor = maxOf(cursor, time); return true
    }
    fun finish(run: Int) { if (running == run) running = null }
}
