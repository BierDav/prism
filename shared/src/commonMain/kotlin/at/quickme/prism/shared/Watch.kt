package at.quickme.prism.shared

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * A [Flow] for Swift, which can't collect one: [watch] collects it on a
 * background dispatcher and calls back per value and once at the end (with
 * the error, or nil when it completed); [Subscription.cancel] stops it.
 * Swift wraps it in an AsyncThrowingStream (macos/Shared.swift). Kotlin
 * callers use [flow] directly.
 */
class Watchable<T : Any>(val flow: Flow<T>) {
    fun watch(onEach: (T) -> Unit, onComplete: (Throwable?) -> Unit): Subscription {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                flow.collect { onEach(it) }
                onComplete(null)
            } catch (e: CancellationException) {
                onComplete(null)
                throw e
            } catch (e: Throwable) {
                onComplete(e)
            }
        }
        return Subscription(scope.coroutineContext[Job]!!)
    }
}

class Subscription internal constructor(private val job: Job) {
    fun cancel() = job.cancel()
}

/** [count] probes, [intervalMillis] apart: a Flow for the self-tests. */
fun countdown(count: Int, intervalMillis: Long): Watchable<Probe> =
    Watchable(
        flow {
            for (i in count downTo 1) {
                emit(Probe(name = "tick", count = i))
                delay(intervalMillis)
            }
        }
    )
