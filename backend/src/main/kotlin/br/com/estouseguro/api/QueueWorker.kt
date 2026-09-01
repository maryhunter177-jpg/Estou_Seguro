package br.com.estouseguro.api

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class QueueWorker(private val repository: AppRepository, private val client: WhatsAppClient) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "whatsapp-delivery-worker").apply { isDaemon = true }
    }

    fun start() { executor.scheduleWithFixedDelay(::pollSafely, 0, 1, TimeUnit.SECONDS) }

    private fun pollSafely() {
        try {
            repository.claimJobs().forEach { job ->
                try { repository.markAccepted(job.id, runBlocking { client.send(job) }) }
                catch (error: Exception) {
                    log.warn("WhatsApp delivery {} failed: {}", job.id, error.message)
                    repository.markRetry(job.id, job.attempts, error.message ?: "Falha temporária")
                }
            }
        } catch (error: Exception) { log.error("Delivery worker cycle failed", error) }
    }

    override fun close() { executor.shutdown(); executor.awaitTermination(5, TimeUnit.SECONDS); client.close() }
}
