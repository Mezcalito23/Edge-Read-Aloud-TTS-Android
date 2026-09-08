package dev.experimental.edgetts

import android.speech.tts.SynthesisCallback
import android.speech.tts.TextToSpeech
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

        override fun start(maxLengthInBytes: Int) {
            startCalled.set(true)
        }

        override fun done() {
            if (doneCalled.getAndSet(true)) {
                throw IllegalStateException("done() called multiple times")
            }
            terminalCalls.incrementAndGet()
            latch.countDown()
        }

        override fun error(errorCode: Int) {
            if (errorCalled.getAndSet(true)) {
                throw IllegalStateException("error() called multiple times")
            }
            terminalCalls.incrementAndGet()
            latch.countDown()
        }

        override fun stop() {}
        override fun rangeStart(startOffset: Int) {}
        override fun audioData(data: ByteArray, offset: Int, length: Int) {}

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
        callback.error(TextToSpeech.ERROR_SYNTHESIS)
        assertTrue(callback.isTerminal())
        assertEquals(1, callback.terminalCalls.get())
        // Second call should throw
        try {
            callback.error(TextToSpeech.ERROR_SYNTHESIS)
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
            callback.error(TextToSpeech.ERROR_SYNTHESIS)
            throw AssertionError("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("multiple times"))
        }
    }

    @Test
    fun errorAndDoneAreMutuallyExclusive() {
        val callback = FakeCallback()
        callback.error(TextToSpeech.ERROR_SYNTHESIS)
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
        callback.start(1000)
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
        callback.start(1000)
        callback.done()
        assertTrue(callback.awaitTerminal())
        assertTrue(callback.isTerminal())
        assertTrue(callback.wasStarted())
    }

    @Test
    fun callbackReachesErrorState() {
        val callback = FakeCallback()
        callback.start(1000)
        callback.error(TextToSpeech.ERROR_SYNTHESIS)
        assertTrue(callback.awaitTerminal())
        assertTrue(callback.isTerminal())
        assertTrue(callback.wasStarted())
    }

    @Test
    fun terminalGuardPreventsDoubleDone() {
        val callback = FakeCallback()
        val guard = TerminalGuard(callback)
        guard.done()
        assertTrue(callback.isTerminal())
        // Second call through guard should be no-op
        guard.done()
        assertEquals(1, callback.terminalCalls.get())
    }

    @Test
    fun terminalGuardPreventsDoubleError() {
        val callback = FakeCallback()
        val guard = TerminalGuard(callback)
        guard.error(TextToSpeech.ERROR_SYNTHESIS)
        assertTrue(callback.isTerminal())
        // Second call through guard should be no-op
        guard.error(TextToSpeech.ERROR_SYNTHESIS)
        assertEquals(1, callback.terminalCalls.get())
    }

    @Test
    fun terminalGuardPreventsDoneAfterError() {
        val callback = FakeCallback()
        val guard = TerminalGuard(callback)
        guard.error(TextToSpeech.ERROR_SYNTHESIS)
        // done() through guard should be no-op
        guard.done()
        assertEquals(1, callback.terminalCalls.get())
    }

    @Test
    fun terminalGuardPreventsErrorAfterDone() {
        val callback = FakeCallback()
        val guard = TerminalGuard(callback)
        guard.done()
        // error() through guard should be no-op
        guard.error(TextToSpeech.ERROR_SYNTHESIS)
        assertEquals(1, callback.terminalCalls.get())
    }
}
