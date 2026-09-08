package dev.experimental.edgetts

import android.speech.tts.SynthesisCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class TerminalityTest {

    /**
     * Callback falso que verifica exactly-once terminalidad.
     */
    private class FakeCallback : SynthesisCallback {
        private val doneCalled = AtomicBoolean(false)
        private val errorCalled = AtomicBoolean(false)
        private val startCalled = AtomicBoolean(false)
        val terminalCalls = AtomicInteger(0)
        val latch = CountDownLatch(1)
        private val maxBufferSizeValue = 4096
        private val hasStartedValue = AtomicBoolean(false)
        private val hasFinishedValue = AtomicBoolean(false)

        override fun start(sampleRateInHz: Int, audioFormat: Int, channelCount: Int): Int {
            startCalled.set(true)
            hasStartedValue.set(true)
            return 0
        }

        override fun done(): Int {
            if (doneCalled.getAndSet(true)) {
                throw IllegalStateException("done() called multiple times")
            }
            hasFinishedValue.set(true)
            terminalCalls.incrementAndGet()
            latch.countDown()
            return 0
        }

        override fun error() {
            if (errorCalled.getAndSet(true)) {
                throw IllegalStateException("error() called multiple times")
            }
            hasFinishedValue.set(true)
            terminalCalls.incrementAndGet()
            latch.countDown()
        }

        override fun error(errorCode: Int) {
            // Usar directamente la misma guarda que error() sin cÃ³digo
            if (errorCalled.getAndSet(true)) {
                throw IllegalStateException("error() called multiple times")
            }
            hasFinishedValue.set(true)
            terminalCalls.incrementAndGet()
            latch.countDown()
        }

        override fun audioAvailable(buffer: ByteArray, offset: Int, length: Int): Int {
            return 0
        }

        override fun getMaxBufferSize(): Int = maxBufferSizeValue

        override fun hasFinished(): Boolean = hasFinishedValue.get()

        override fun hasStarted(): Boolean = hasStartedValue.get()

        fun awaitTerminal(timeout: Long = 5, unit: TimeUnit = TimeUnit.SECONDS): Boolean {
            return latch.await(timeout, unit)
        }

        fun isTerminal(): Boolean {
            return doneCalled.get() || errorCalled.get()
        }

        fun wasStarted(): Boolean {
            return startCalled.get()
        }
    }

    @Test
    fun doneCalledExactlyOnce() {
        val callback = FakeCallback()
        callback.done()
        assertTrue(callback.isTerminal())
        assertEquals(1, callback.terminalCalls.get())
        // Second call should throw
        try {
            callback.done()
            throw AssertionError("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("multiple times"))
        }
    }

    @Test
    fun errorCalledExactlyOnce() {
        val callback = FakeCallback()
        callback.error()
        assertTrue(callback.isTerminal())
        assertEquals(1, callback.terminalCalls.get())
        // Second call should throw
        try {
            callback.error()
            throw AssertionError("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("multiple times"))
        }
    }

    @Test
    fun errorWithCodeCalledExactlyOnce() {
        val callback = FakeCallback()
        callback.error(1) // Con cÃ³digo
        assertTrue(callback.isTerminal())
        assertEquals(1, callback.terminalCalls.get())
        // Second call should throw
        try {
            callback.error(2)
            throw AssertionError("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("multiple times"))
        }
    }

    @Test
    fun doneAndErrorAreMutuallyExclusive() {
        val callback = FakeCallback()
        callback.done()
        assertTrue(callback.isTerminal())
        // Calling error after done should throw
        try {
            callback.error()
            throw AssertionError("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("multiple times"))
        }
    }

    @Test
    fun errorAndDoneAreMutuallyExclusive() {
        val callback = FakeCallback()
        callback.error()
        assertTrue(callback.isTerminal())
        // Calling done after error should throw
        try {
            callback.done()
            throw AssertionError("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("multiple times"))
        }
    }

    @Test
    fun startDoesNotAffectTerminality() {
        val callback = FakeCallback()
        callback.start(24000, 2, 1)
        assertFalse(callback.isTerminal())
        assertEquals(0, callback.terminalCalls.get())
        // Can still call done
        callback.done()
        assertTrue(callback.isTerminal())
        assertEquals(1, callback.terminalCalls.get())
    }

    @Test
    fun callbackReachesTerminalState() {
        val callback = FakeCallback()
        callback.start(24000, 2, 1)
        callback.done()
        assertTrue(callback.awaitTerminal())
        assertTrue(callback.isTerminal())
        assertTrue(callback.wasStarted())
    }

    @Test
    fun callbackReachesErrorState() {
        val callback = FakeCallback()
        callback.start(24000, 2, 1)
        callback.error()
        assertTrue(callback.awaitTerminal())
        assertTrue(callback.isTerminal())
        assertTrue(callback.wasStarted())
    }

    @Test
    fun terminalGuardPreventsDoubleDone() {
        val callback = FakeCallback()
        val guard = TerminalGuard()
        guard.done(callback)
        assertTrue(callback.isTerminal())
        // Second call through guard should be no-op
        guard.done(callback)
        assertEquals(1, callback.terminalCalls.get())
    }

    @Test
    fun terminalGuardPreventsDoubleError() {
        val callback = FakeCallback()
        val guard = TerminalGuard()
        guard.error(callback, "Error message")
        assertTrue(callback.isTerminal())
        // Second call through guard should be no-op
        guard.error(callback, "Another error")
        assertEquals(1, callback.terminalCalls.get())
    }

    @Test
    fun terminalGuardPreventsDoneAfterError() {
        val callback = FakeCallback()
        val guard = TerminalGuard()
        guard.error(callback, "Error message")
        // done() through guard should be no-op
        guard.done(callback)
        assertEquals(1, callback.terminalCalls.get())
    }

    @Test
    fun terminalGuardPreventsErrorAfterDone() {
        val callback = FakeCallback()
        val guard = TerminalGuard()
        guard.done(callback)
        // error() through guard should be no-op
        guard.error(callback, "Error message")
        assertEquals(1, callback.terminalCalls.get())
    }
}
