package com.dshbox.browser.runtime

import java.util.concurrent.atomic.AtomicLong

/** A delayed dialog dismissal may invalidate only the generation owned by that dialog. */
internal fun closeOwnedHumanSurface(generation: AtomicLong, expected: Long): Boolean =
    generation.compareAndSet(expected, expected + 1)
