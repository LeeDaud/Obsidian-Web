package xyz.leedaud.echo.delivery

import android.content.Context
import androidx.work.*
import xyz.leedaud.echo.notes.*
import xyz.leedaud.echo.storage.CredentialStore
import xyz.leedaud.echo.storage.NoteRepository
import java.time.Instant
import java.util.concurrent.TimeUnit

class DeliveryWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val repository = try { NoteRepository.get(applicationContext) } catch (_: Exception) { return Result.retry() }
        val id = inputData.getString("deliveryId") ?: return Result.failure()
        var job = repository.claim(id) ?: return if (repository.deliveries().find { it.id == id }?.state in setOf("verified", "conflict", "auth_required", "paused")) Result.success() else Result.retry()
        try {
            val resolved = repository.target()?.takeIf { it.id == job.target.id && it.repositoryId == job.target.repositoryId && it.branch == job.target.branch }
                ?: throw DeliveryFailure("paused", "目标身份已变化，原任务保持原仓库绑定")
            job = job.copy(target = resolved)
            check(repository.checkpoint(job)) { "任务绑定检查点未保存" }
            val parent = job.note.parentId?.let { parentId -> repository.deliveries().find { it.noteId == parentId && it.target.id == job.target.id } }
            if (job.note.parentId != null && parent?.receipt == null) {
                repository.finish(job.copy(state = "waiting_parent", error = "等待父笔记完成仓库核验"))
                return Result.retry()
            }
            val token = try { CredentialStore(applicationContext).read(job.target.credential) }
                catch (_: Exception) { throw DeliveryFailure("auth_required", "需要重新配置当前仓库的 GitHub 授权") }
            val client = GitHubClient(NetworkTransport(job.target, token))
            if (job.files.isEmpty()) {
                val reserved = repository.deliveries().filter { it.id != job.id && it.target.id == job.target.id }.map { it.path }.toSet()
                var time = job.note.created
                var path: String
                var files: List<DeliveryFile>
                var count = 0
                do {
                    path = "00_Inbox/${timestamp(time)}.md"
                    files = repository.bundle(job.note, path, parent?.receipt?.path)
                    if (++count > 120) throw DeliveryFailure("conflict", "同秒路径冲突过多，请稍后重试")
                    time = Instant.ofEpochMilli(time).plusSeconds(1).toEpochMilli()
                } while (!client.available(job.target, path, files.drop(1).map { it.path }, reserved))
                job = job.copy(path = path, files = repository.persistBundle(job.id, files))
                check(repository.checkpoint(job)) { "任务租约已变化" }
            }
            val current = repository.target()
            if (current?.id != job.target.id || !current.enabled) {
                repository.finish(job.copy(state = "paused", error = "目标配置已变化或投递暂停，原任务未转投"))
                return Result.success()
            }
            val attempt = client.publish(job.target, repository.hydrate(job.files), job.attempt) { attempt ->
                val allowed = repository.target()
                if (allowed?.id != job.target.id || !allowed.enabled) throw DeliveryFailure("paused", "分支更新前投递已暂停，快照保留")
                job = job.copy(attempt = attempt, state = "commit_prepared")
                check(repository.checkpoint(job)) { "任务检查点未保存，停止发布" }
            }
            repository.finish(job.copy(attempt = attempt, state = "verified", error = null,
                receipt = Receipt(attempt.commit, job.path, System.currentTimeMillis(), job.files.associate { it.path to it.sha256 })))
            return Result.success()
        } catch (failure: DeliveryFailure) {
            val retries = job.failures + 1
            val retryAt = maxOf(failure.retryAt, System.currentTimeMillis() + minOf(3600000, 30000L * (1L shl minOf(retries, 6))))
            runCatching { repository.finish(job.copy(state = failure.kind, error = failure.message, failures = retries, retryAt = retryAt)) }
            return if (failure.kind == "retryable_error") Result.retry() else Result.success()
        } catch (_: Exception) {
            runCatching { repository.finish(job.copy(state = "retryable_error", error = "投递或本地核验未完成，内容保留；请重试",
                failures = job.failures + 1, retryAt = System.currentTimeMillis() + 60000)) }
            return Result.retry()
        }
    }
    companion object {
        fun schedule(context: Context, repository: NoteRepository) {
            repository.deliveries().filter { it.state !in setOf("verified", "conflict", "auth_required") }.forEach { job ->
                val request = OneTimeWorkRequestBuilder<DeliveryWorker>()
                    .setInputData(workDataOf("deliveryId" to job.id))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
                WorkManager.getInstance(context).enqueueUniqueWork("echo-delivery-${job.id}", ExistingWorkPolicy.KEEP, request)
            }
        }
    }
}
