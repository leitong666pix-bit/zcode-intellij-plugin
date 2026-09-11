package zcode.idea.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SessionTaskQueueTest {
    @Test fun switchDropsQueuedWorkAndInvalidatesInFlightCallbacks() {
        SessionTaskQueue().use { queue ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val stale = CompletableFuture<Boolean>()
            val forbidden = CompletableFuture<Boolean>()
            val old = queue.token()
            queue.execute {
                entered.countDown()
                release.await(3, TimeUnit.SECONDS)
                stale.complete(queue.isCurrent(queue.token()))
                queue.execute { forbidden.complete(true) }
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            queue.execute { forbidden.complete(true) }
            val current = queue.invalidate()
            assertFalse(queue.isCurrent(old))
            release.countDown()
            val done = CompletableFuture<Boolean>()
            queue.execute(current) { done.complete(true) }
            assertTrue(done.get(3, TimeUnit.SECONDS))
            assertFalse(stale.get(3, TimeUnit.SECONDS))
            assertFalse(forbidden.isDone)
        }
    }

    @Test fun switchInterruptsWaitingForAnUnansweredRpc() {
        SessionTaskQueue().use { queue ->
            val started = CountDownLatch(1)
            val reply = CompletableFuture<String>()
            val stale = CompletableFuture<Boolean>()
            queue.execute {
                started.countDown()
                queue.await(reply, 60)
                stale.complete(true)
            }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            val generation = queue.invalidate()
            val ready = CompletableFuture<Boolean>()
            queue.execute(generation) { ready.complete(true) }
            assertTrue(ready.get(2, TimeUnit.SECONDS))
            assertFalse(stale.isDone)
            reply.complete("late response")
            assertFalse(stale.isDone)
        }
    }

    @Test fun responseArrivingAtPollBoundaryIsNotAnRpcTimeout() {
        SessionTaskQueue().use { queue ->
            val reply = object : CompletableFuture<String>() {
                private var firstPoll = true
                override fun get(timeout: Long, unit: TimeUnit): String {
                    if (firstPoll) {
                        firstPoll = false
                        complete("ok")
                        throw java.util.concurrent.TimeoutException("poll boundary")
                    }
                    return super.get(timeout, unit)
                }
            }
            assertEquals("ok", queue.await(reply, 1))
        }
    }

    @Test fun disposalRejectsNewWorkAndOldUiTokens() {
        val queue = SessionTaskQueue()
        val token = queue.token()
        queue.close()
        assertFalse(queue.isCurrent(token))
        val ran = CompletableFuture<Boolean>()
        queue.execute { ran.complete(true) }
        assertFalse(ran.isDone)
    }
}
