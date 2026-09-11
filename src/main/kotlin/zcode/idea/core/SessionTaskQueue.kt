package zcode.idea.core

import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

/** Serializes session state without blocking the RPC reader. A switch invalidates queued work and UI callbacks. */
internal class SessionTaskQueue : AutoCloseable {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "zcode-session-worker").apply { isDaemon = true }
    }
    private val generation = AtomicLong()
    private val runningGeneration = ThreadLocal<Long>()
    @Volatile private var closed = false

    fun token(): Long = runningGeneration.get() ?: generation.get()
    fun isCurrent(token: Long): Boolean = !closed && generation.get() == token
    fun invalidate(): Long = generation.incrementAndGet()

    fun checkCurrent() {
        if (!isCurrent(token())) throw CancellationException("Session changed or disposed")
    }

    /** Poll cancellation while awaiting RPC; switching sessions must not wait for the full RPC timeout. */
    fun <T> await(future: CompletableFuture<T>, seconds: Long): T {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (true) {
            checkCurrent()
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw TimeoutException("RPC response timed out")
            try {
                val result = future.get(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS)
                checkCurrent()
                return result
            } catch (e: TimeoutException) {
                if (System.nanoTime() >= deadline) throw e
            }
        }
    }

    fun execute(token: Long = token(), action: () -> Unit) {
        if (!isCurrent(token)) return
        try {
            executor.execute worker@{
                if (!isCurrent(token)) return@worker
                runningGeneration.set(token)
                try {
                    action()
                } catch (_: CancellationException) {
                    // Work belonging to the previous session is intentionally abandoned.
                } finally {
                    runningGeneration.remove()
                }
            }
        } catch (_: RejectedExecutionException) {
            // A concurrent project disposal shuts the executor down.
        }
    }

    override fun close() {
        closed = true
        invalidate()
        executor.shutdownNow()
    }
}
