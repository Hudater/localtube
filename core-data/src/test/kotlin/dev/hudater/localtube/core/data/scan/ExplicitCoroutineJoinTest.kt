package dev.hudater.localtube.core.data.scan

import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression guard for a debugging session where `coroutineScope`'s
 * implicit wait-for-children silently did NOT materialize in the Gradle
 * unit-test worker (a plain `launch` inside `coroutineScope` returned
 * before the child ran — see ScanEngine's resolution phase, which joins
 * its children EXPLICITLY via [Job] handles for exactly this reason).
 *
 * This test asserts the EXPLICIT-join contract ScanEngine relies on: a
 * captured Job joined through [Job.join] happens-before the join call
 * returns. If it ever fails, the await semantics core-data depends on
 * are broken at the runtime level, not in the engine's logic.
 */
class ExplicitCoroutineJoinTest {
    @Test
    fun `explicit job join observes the child's writes`() = runBlocking {
        val out = coroutineScope {
            val arr = arrayOfNulls<String>(1)
            val job: Job = launch { arr[0] = "x" }
            job.join()
            arr.filterNotNull()
        }
        assertEquals(1, out.size)
    }
}
