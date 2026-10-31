// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.diagnostics

import com.google.gson.Gson
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Bounded, nonblocking producers; formatting, stack traces and all disk work stay on this worker. */
internal class DiagnosticWriter(val directory: Path, val session: String, private val role: String) : AutoCloseable {
    private data class Repeated(val first: DiagnosticEvent, var count: Long, var last: String, var reported: Long)
    private data class State(val module: String, val key: String, val value: String,
                             val context: Map<String, Any?>, val observedAt: Instant,
                             val changedAt: Instant, val monotonicNs: Long)
    private val queue = LinkedBlockingDeque<Any>(4096)
    private val shutdownWakeup = Any()
    private val lost = AtomicLong()
    private val overflow = AtomicReference<DiagnosticEvent?>()
    private val gson = Gson()
    // Resolve the sanitizer before the worker can outlive a failed plugin start or class-loader close.
    private val privacy = DiagnosticPrivacy
    private val pid = ProcessHandle.current().pid()
    private val storage = DiagnosticStorage(directory)
    private val retention = DiagnosticRetention(storage)
    private val states = object : LinkedHashMap<String, State>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, State>) = size > 256
    }
    private val repeated = LinkedHashMap<String, Repeated>()
    @Volatile private var closed = false
    private var output: BufferedWriter? = null
    private var outputLease: DiagnosticFileLease? = null
    private var day: LocalDate? = null
    private var size = 0L
    private var part = 0
    private var nextMaintenance = 0L
    private var lastFallback = 0L
    @Volatile var idleIntervalNs = 1_000_000_000L
    private val worker = Thread(::run, "SPW Island log writer").apply {
        isDaemon = true
        uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
            reportFailure("Diagnostic writer stopped", error)
        }
        start()
    }

    fun offer(event: DiagnosticEvent) {
        if (closed) return
        if (!queue.offer(event)) {
            // Preserve the first queued evidence and one latest critical event if the disk stalls.
            val previous = overflow.getAndUpdate { current ->
                if (current == null || event.level != "INFO" || current.level == "INFO") event else current
            }
            if (previous != null) lost.incrementAndGet()
        }
    }

    fun state(module: String, key: String, value: String, context: Map<String, Any?>) {
        if (closed) return
        val observedAt = Instant.now()
        val now = System.nanoTime()
        val changed = synchronized(states) {
            val previous = states["$module/$key"]
            val changed = previous?.value != value
            // Refresh sampled details without turning every sample or frame counter into a log event.
            states["$module/$key"] = State(module, key, value, context.toMap(), observedAt,
                if (changed) observedAt else checkNotNull(previous).changedAt, now)
            changed
        }
        if (changed) offer(DiagnosticEvent(observedAt.toString(), now, "INFO", module, "$key.changed",
            Thread.currentThread().name, context + ("state" to value)))
    }

    /** Latest observations only; never query UI/host objects or wait for their threads during export. */
    fun snapshot(): Map<String, Any?> {
        val recorded = synchronized(states) { states.values.toList() }
        val now = System.nanoTime()
        return mapOf("available" to true, "scope" to "current_process", "session" to session,
            "process" to pid, "role" to role, "capturedAt" to Instant.now().toString(),
            "states" to recorded.map { state ->
                mapOf("module" to state.module, "key" to state.key, "state" to privacy.text(state.value),
                    "lastObservedAt" to state.observedAt.toString(), "stateChangedAt" to state.changedAt.toString(),
                    "ageMs" to (now - state.monotonicNs).coerceAtLeast(0) / 1_000_000,
                    "context" to state.context.entries.take(40).associate { it.key to privacy.text(it.value) })
            })
    }

    fun flush(timeoutMs: Long): Boolean {
        if (Thread.currentThread() === worker || closed) return false
        // Queue markers use JDK classes so shutdown can release waiters after the plugin loader closes.
        val barrier = FutureTask { true }
        if (!queue.offer(barrier)) return false
        return try { barrier.get(timeoutMs, TimeUnit.MILLISECONDS) }
        catch (_: TimeoutException) { false }
        catch (_: CancellationException) { false }
        catch (_: ExecutionException) { false }
    }

    private fun run() {
        try {
            while (!closed || queue.isNotEmpty() || overflow.get() != null) {
                val value = queue.poll(idleIntervalNs.coerceIn(1_000_000_000L, 5_000_000_000L), TimeUnit.NANOSECONDS)
                try {
                    val now = System.nanoTime()
                    if (now >= nextMaintenance) {
                        storage.prepare()
                        retention.request()
                        nextMaintenance = now + 3_600_000_000_000L
                    }
                    if (value is DiagnosticEvent) accept(value)
                    overflow.getAndSet(null)?.let(::accept)
                    repeated.values.forEach { repeat ->
                        if (repeat.count > 1 && (value is FutureTask<*> || now - repeat.reported >= 60_000_000_000L)) {
                            summary(repeat); repeat.count = 1; repeat.reported = now
                        }
                    }
                    val dropped = lost.getAndSet(0)
                    if (dropped > 0) write(DiagnosticEvent(java.time.Instant.now().toString(), now, "WARN",
                        "diagnostics", "queue.entries_lost", worker.name, mapOf("count" to dropped)))
                    output?.flush()
                    if (value is FutureTask<*>) {
                        // Include pending repeat summaries, overflow evidence and loss counts in the export barrier.
                        if (output != null) value.run() else value.cancel(false)
                    }
                } catch (error: Throwable) {
                    if (value is FutureTask<*>) value.cancel(false)
                    lost.incrementAndGet()
                    try { closeOutput() } catch (_: Throwable) { }
                    if (System.nanoTime() - lastFallback > 60_000_000_000L || lastFallback == 0L) {
                        lastFallback = System.nanoTime()
                        reportFailure("Persistent diagnostics unavailable", error)
                    }
                    if (error is LinkageError) break
                }
            }
            repeated.values.filter { it.count > 1 }.forEach(::summary)
        } catch (error: Throwable) {
            reportFailure("Diagnostic writer stopped", error)
        } finally {
            closed = true
            try { retention.close() } catch (_: Throwable) { }
            try { closeOutput() } catch (_: Throwable) { }
            try {
                while (true) {
                    val pending = queue.poll() ?: break
                    if (pending is FutureTask<*>) pending.cancel(false)
                }
            } catch (error: Throwable) {
                reportFailure("Diagnostic queue cleanup failed", error)
            }
        }
    }

    private fun reportFailure(message: String, error: Throwable) {
        // A logging/class-loading failure must not escape through the host's uncaught handler.
        try {
            val detail = try { privacy.text(error) } catch (_: Throwable) { error.javaClass.name }
            System.err.println("[SPW Island] $message: $detail")
        } catch (_: Throwable) { }
    }

    private fun accept(event: DiagnosticEvent) {
        if (event.level == "INFO") { write(event); return }
        val signature = "${event.module}/${event.event}/${event.error?.javaClass?.name}/${privacy.text(event.error?.message ?: event.context)}"
        val previous = repeated[signature]
        if (previous != null) {
            if (previous.count == 1L && System.nanoTime() - previous.reported >= 60_000_000_000L) {
                write(event); repeated[signature] = Repeated(event, 1, event.time, System.nanoTime())
            } else { previous.count++; previous.last = event.time }
            return
        }
        if (repeated.size >= 256) {
            val oldest = repeated.entries.first()
            if (oldest.value.count > 1) summary(oldest.value)
            repeated.remove(oldest.key)
        }
        write(event)
        repeated[signature] = Repeated(event, 1, event.time, System.nanoTime())
    }

    private fun summary(repeat: Repeated) = write(repeat.first.copy(time = java.time.Instant.now().toString(),
        monotonicNs = System.nanoTime(), event = "${repeat.first.event}.repeated",
        context = repeat.first.context + mapOf("additionalOccurrences" to repeat.count - 1,
            "firstSeen" to repeat.first.time, "lastSeen" to repeat.last), error = null))

    private fun write(event: DiagnosticEvent) {
        val currentDay = LocalDate.now(ZoneOffset.UTC)
        if (output == null || day != currentDay || size >= 4 * 1_048_576) {
            closeOutput()
            storage.prepare()
            retention.request()
            check(storage.hasCapacity()) { "Log capacity reached; historical files could not be recycled" }
            val file = directory.resolve("island-$session-$role-$pid-$currentDay-${part++}.jsonl")
            val lease = DiagnosticFileLease.create(file)
            try {
                output = Files.newBufferedWriter(file, Charsets.UTF_8, CREATE_NEW, WRITE)
                outputLease = lease
            } catch (error: Throwable) { lease.close(); throw error }
            day = currentDay; size = 0
        }
        val record = linkedMapOf<String, Any?>("time" to event.time, "monotonicNs" to event.monotonicNs,
            "session" to session, "process" to pid, "role" to role, "thread" to privacy.text(event.thread),
            "level" to event.level, "module" to event.module, "event" to event.event,
            "context" to event.context.entries.take(40).associate { it.key to privacy.text(it.value) })
        event.error?.let { record["exception"] = it.javaClass.name; record["stack"] = privacy.stack(it) }
        val line = gson.toJson(record)
        output!!.write(line); output!!.newLine()
        size += line.toByteArray(Charsets.UTF_8).size + 1
    }

    private fun closeOutput() {
        try { output?.close() }
        finally {
            output = null
            outputLease?.close()
            outputLease = null
        }
    }

    override fun close() {
        if (closed) return
        offer(DiagnosticEvent(java.time.Instant.now().toString(), System.nanoTime(), "INFO", "lifecycle",
            "diagnostics.stopped", Thread.currentThread().name, emptyMap()))
        closed = true
        retention.close()
        queue.offer(shutdownWakeup)
        // Plugin shutdown can hold a host monitor: never wait for disk/EDT from that thread.
    }
}
