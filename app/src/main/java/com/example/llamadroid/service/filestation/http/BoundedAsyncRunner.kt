package com.example.llamadroid.service.filestation.http

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Collections
import java.util.UUID
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Connection runner with 4..32 threads and no queue; connections beyond the cap are closed. */
class BoundedAsyncRunner(
    corePoolSize: Int = 4,
    maxPoolSize: Int = 32,
) : NanoHTTPD.AsyncRunner {
    private val counter = AtomicInteger()
    private val running: MutableSet<NanoHTTPD.ClientHandler> =
        Collections.synchronizedSet(LinkedHashSet())
    private val pool = ThreadPoolExecutor(
        corePoolSize, maxPoolSize, 30, TimeUnit.SECONDS, SynchronousQueue(),
        ThreadFactory { r -> Thread(r, "fs-http-${counter.incrementAndGet()}").apply { isDaemon = true } },
    )

    override fun exec(clientHandler: NanoHTTPD.ClientHandler) {
        running.add(clientHandler)
        try {
            pool.execute(clientHandler)
        } catch (e: RejectedExecutionException) {
            running.remove(clientHandler)
            clientHandler.close()
        }
    }

    override fun closed(clientHandler: NanoHTTPD.ClientHandler) {
        running.remove(clientHandler)
    }

    override fun closeAll() {
        val snapshot = synchronized(running) { running.toList() }
        snapshot.forEach { it.close() }
        running.clear()
        pool.shutdown()
    }
}

/** Temp files (NanoHTTPD-internal use only) live under one folder wiped on server start/stop. */
class DirTempFileManagerFactory(private val dir: File) : NanoHTTPD.TempFileManagerFactory {
    override fun create(): NanoHTTPD.TempFileManager = object : NanoHTTPD.TempFileManager {
        private val files = ArrayList<NanoHTTPD.TempFile>()

        override fun createTempFile(filenameHint: String?): NanoHTTPD.TempFile {
            dir.mkdirs()
            val file = File(dir, "tmp-" + UUID.randomUUID())
            val temp = object : NanoHTTPD.TempFile {
                override fun open(): OutputStream = FileOutputStream(file)
                override fun delete() { file.delete() }
                override fun getName(): String = file.absolutePath
            }
            files.add(temp)
            return temp
        }

        override fun clear() {
            files.forEach { runCatching { it.delete() } }
            files.clear()
        }
    }
}
