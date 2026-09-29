package com.dshbox.terminal

/**
 * Rule shared by every kill path for deciding whether a session needs a signal
 * and a bookkeeping entry.
 *
 * The shell pid of a session is reported in three distinct states: greater than
 * zero once the process has been spawned, exactly zero while it has never been
 * spawned, and minus one once it has ended. Only a spawned process can be
 * signalled, and only a spawned process delivers a finish callback; an unspawned
 * or already finished session must be neither killed nor recorded, since a
 * recorded session that never reports again would keep the entry forever.
 *
 * Kept free of Android types so the rule is directly unit-testable on the JVM.
 */
internal object TerminalSessionBookkeeping {

    /** True when the session owns a live process: kill it and record it as intentionally stopped. */
    fun needsKillAndMark(pid: Int): Boolean = pid > 0
}
