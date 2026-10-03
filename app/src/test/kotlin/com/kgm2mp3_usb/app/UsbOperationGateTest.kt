package com.kgm2mp3_usb.app

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class UsbOperationGateTest {
    @Test fun onlyOwnerCanReleaseAndAcquireNextOperation() {
        val first = Any()
        val second = Any()
        assertTrue(UsbOperationGate.acquire(first))
        try {
            assertTrue(UsbOperationGate.busy)
            assertFalse(UsbOperationGate.acquire(second))
            UsbOperationGate.release(second)
            assertFalse(UsbOperationGate.acquire(second))
        } finally { UsbOperationGate.release(first) }
        assertTrue(UsbOperationGate.acquire(second))
        UsbOperationGate.release(second)
        assertFalse(UsbOperationGate.busy)
    }
    @Test fun racingOperationsHaveExactlyOneWinner() {
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val tokens = listOf(Any(), Any())
        try {
            val results = tokens.map { token -> executor.submit<Boolean> { ready.countDown(); go.await(); UsbOperationGate.acquire(token) } }
            ready.await(); go.countDown()
            assertEquals(1, results.count { it.get() })
        } finally {
            tokens.forEach(UsbOperationGate::release)
            executor.shutdown()
        }
    }
}
