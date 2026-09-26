package com.example.llamadroid.data.db

import com.example.llamadroid.tama.db.TamaDatabase
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DatabaseSingletonContentionTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @After fun close() {
        AppDatabase.closeInstance()
        TamaDatabase.closeInstance()
    }

    @Test fun appFactoryBuildsOnceAcrossFirstAccessAndOnceAfterClose() {
        AppDatabase.closeInstance()
        val first = contend(AppDatabase.Companion) { AppDatabase.getDatabase(context) }
        first.forEach { assertSame(first[0], it) }
        assertSame(first[0], AppDatabase.getDatabase(context))
        AppDatabase.closeInstance()
        val second = contend(AppDatabase.Companion) { AppDatabase.getDatabase(context) }
        second.forEach { assertSame(second[0], it) }
        assertNotSame(first[0], second[0])
    }

    @Test fun tamaFactoryBuildsOnceAcrossFirstAccessAndOnceAfterClose() {
        TamaDatabase.closeInstance()
        val first = contend(TamaDatabase.Companion) { TamaDatabase.getInstance(context) }
        first.forEach { assertSame(first[0], it) }
        assertSame(first[0], TamaDatabase.getInstance(context))
        TamaDatabase.closeInstance()
        val second = contend(TamaDatabase.Companion) { TamaDatabase.getInstance(context) }
        second.forEach { assertSame(second[0], it) }
        assertNotSame(first[0], second[0])
    }

    private fun <T : Any> contend(monitor: Any, acquire: () -> T): List<T> {
        val threads = Collections.synchronizedList(mutableListOf<Thread>())
        val executor = Executors.newFixedThreadPool(4) { task ->
            Thread(task, "database-contention-${threads.size}").also(threads::add)
        }
        val ready = CountDownLatch(4)
        val release = CountDownLatch(1)
        try {
            val futures: List<java.util.concurrent.Future<T>>
            synchronized(monitor) {
                futures = (0 until 4).map {
                    executor.submit<T> {
                        ready.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        acquire()
                    }
                }
                check(ready.await(5, TimeUnit.SECONDS))
                release.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (threads.count { it.state == Thread.State.BLOCKED } < 4 &&
                    System.nanoTime() < deadline) Thread.sleep(1)
                assertEquals("All callers must pass the first null check before release", 4,
                    threads.count { it.state == Thread.State.BLOCKED })
            }
            return futures.map { it.get(5, TimeUnit.SECONDS) }
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
