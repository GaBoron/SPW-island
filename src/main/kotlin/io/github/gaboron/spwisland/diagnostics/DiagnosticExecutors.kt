// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.diagnostics

import java.util.concurrent.*

/** submit()/scheduled futures capture exceptions instead of invoking a thread's uncaught handler. */
internal object DiagnosticExecutors {
    private fun completed(task: Runnable, failure: Throwable?) {
        var error = failure
        if (error == null && task is Future<*> && task.isDone && !task.isCancelled) {
            try { task.get() }
            catch (caught: ExecutionException) { error = caught.cause ?: caught }
            catch (_: CancellationException) { }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        error?.let { IslandLog.failure("async", "task.failed", it) }
    }
    fun scheduled(factory: ThreadFactory): ScheduledExecutorService = object : ScheduledThreadPoolExecutor(1, factory) {
        override fun afterExecute(task: Runnable, error: Throwable?) { super.afterExecute(task, error); completed(task, error) }
    }
    fun single(factory: ThreadFactory): ExecutorService = pool(1, 1, 0, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(), factory, ThreadPoolExecutor.AbortPolicy())
    fun pool(core: Int, maximum: Int, keepAlive: Long, unit: TimeUnit, queue: BlockingQueue<Runnable>,
             factory: ThreadFactory, rejected: RejectedExecutionHandler): ThreadPoolExecutor =
        object : ThreadPoolExecutor(core, maximum, keepAlive, unit, queue, factory, rejected) {
            override fun afterExecute(task: Runnable, error: Throwable?) { super.afterExecute(task, error); completed(task, error) }
        }
}
