package com.kgm2mp3_usb.app

import java.util.concurrent.atomic.AtomicReference

/** A token is acquired on the UI thread and may be released by a background worker. */
internal object UsbOperationGate {
    private val owner = AtomicReference<Any?>(null)
    val busy: Boolean get() = owner.get() != null
    fun acquire(token: Any): Boolean = owner.compareAndSet(null, token)
    fun release(token: Any) { owner.compareAndSet(token, null) }
}
