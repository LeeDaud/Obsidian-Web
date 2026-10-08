package xyz.leedaud.echo.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import xyz.leedaud.echo.delivery.*
import xyz.leedaud.echo.notes.*
import xyz.leedaud.echo.notes.Target
import xyz.leedaud.echo.storage.*
import java.io.InputStream
import java.util.concurrent.Executors

data class UiState(val ready: Boolean = false, val draft: Draft = Draft(), val notes: List<Note> = emptyList(),
                   val drafts: List<Draft> = emptyList(), val jobs: List<Delivery> = emptyList(), val target: Target? = null,
                   val busy: Boolean = false, val message: String? = null, val draftSaved: Boolean = false)

class NoteViewModel(application: Application) : AndroidViewModel(application) {
    private val local = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private lateinit var repository: NoteRepository
    private val mutable = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutable
    private var autosave: Job? = null
    private val activeOperations = java.util.concurrent.atomic.AtomicInteger()
    init { initialize() }
    fun initialize() {
        operation {
            repository = NoteRepository.get(getApplication())
            val draft = repository.activeDraft()
            mutable.update { it.copy(ready = true, draft = draft, draftSaved = true) }
            DeliveryWorker.schedule(getApplication(), repository)
        }
    }
    private fun refresh() {
        mutable.update { it.copy(notes = repository.notes(), drafts = repository.drafts(), jobs = repository.deliveries(), target = repository.target()) }
    }
    private fun operation(work: suspend () -> Unit) {
        viewModelScope.launch {
            activeOperations.incrementAndGet()
            mutable.update { it.copy(busy = true) }
            try { withContext(local) { work(); refresh() } }
            catch (error: Exception) { mutable.update { it.copy(message = error.message?.take(180) ?: "操作未完成，本地内容保留") } }
            finally { val busy = activeOperations.decrementAndGet() > 0; mutable.update { it.copy(busy = busy) } }
        }
    }
    fun dismissMessage() { mutable.update { it.copy(message = null) } }
    fun refreshStatuses() {
        if (!mutable.value.ready) return
        viewModelScope.launch { runCatching { withContext(local) { refresh() } } }
    }
    fun change(draft: Draft) {
        if (!mutable.value.ready) return
        if (draft.body.toByteArray(Charsets.UTF_8).size > NoteFormat.TEXT_LIMIT) {
            mutable.update { it.copy(message = "正文最多 256 KiB，超限输入未写入，请拆分笔记") }
            return
        }
        val changed = draft.copy(generation = mutable.value.draft.generation + 1)
        mutable.update { it.copy(draft = changed, draftSaved = false) }
        autosave?.cancel()
        autosave = viewModelScope.launch {
            delay(350)
            try {
                withContext(local) { repository.saveDraft(changed) }
                mutable.update { if (it.draft.id == changed.id && it.draft.generation == changed.generation) it.copy(draftSaved = true) else it }
            } catch (_: Exception) { mutable.update { it.copy(message = "设备草稿尚未落盘，请暂存或检查存储空间", draftSaved = false) } }
        }
    }
    fun stash() {
        if (!mutable.value.ready) return
        val draft = mutable.value.draft
        autosave?.cancel()
        operation {
            repository.saveDraft(draft)
            repository.select(draft)
            mutable.update { it.copy(draftSaved = it.draft == draft, message = "已暂存到本机，没有上传") }
        }
    }
    fun save() {
        if (mutable.value.busy) return
        val draft = mutable.value.draft
        autosave?.cancel()
        operation {
            val note = repository.save(draft)
            if (note == null) { mutable.update { it.copy(message = "空白笔记无需保存") }; return@operation }
            val next = repository.select(Draft())
            mutable.update { it.copy(draft = next, draftSaved = true, message = if (note.frozen) "已保存，当前快照进入投递队列" else "已保存到本机") }
            DeliveryWorker.schedule(getApplication(), repository)
        }
    }
    fun newNote(parent: Note? = null) {
        val previous = mutable.value.draft
        autosave?.cancel()
        operation {
            repository.saveDraft(previous)
            val next = repository.select(Draft(parentId = parent?.id))
            mutable.update { it.copy(draft = next, draftSaved = true) }
        }
    }
    fun edit(note: Note) {
        val previous = mutable.value.draft
        autosave?.cancel()
        operation {
            repository.saveDraft(previous)
            val next = repository.edit(note)
            mutable.update { it.copy(draft = next, draftSaved = true) }
        }
    }
    fun openDraft(draft: Draft) {
        val previous = mutable.value.draft
        autosave?.cancel()
        operation {
            repository.saveDraft(previous)
            val next = repository.select(draft)
            mutable.update { it.copy(draft = next, draftSaved = true) }
        }
    }
    fun importUris(uris: List<Uri>, owner: String = mutable.value.draft.id) {
        operation {
            val imported = uris.map { repository.importUri(getApplication(), it) }
            if (mutable.value.draft.id != owner) error("编辑会话已变化，附件留在本机但没有加入新笔记")
            val current = mutable.value.draft
            NoteFormat.validate(current.body, current.attachments + imported)
            val next = current.copy(attachments = current.attachments + imported, generation = current.generation + 1)
            repository.saveDraft(next)
            mutable.update { it.copy(draft = next, draftSaved = true) }
        }
    }
    fun importRecording(stream: InputStream, name: String) {
        val owner = mutable.value.draft.id
        operation {
            val attachment = repository.importStream(stream, name, "audio/mp4")
            if (mutable.value.draft.id != owner) error("编辑会话已变化，录音保留在本机")
            val current = mutable.value.draft
            val next = current.copy(attachments = current.attachments + attachment, generation = current.generation + 1)
            NoteFormat.validate(next.body, next.attachments)
            repository.saveDraft(next)
            mutable.update { it.copy(draft = next, draftSaved = true) }
        }
    }
    fun updateNote(note: Note) = operation { repository.updateNote(note) }
    fun enqueue(note: Note) = operation {
        repository.enqueue(note.id)
        DeliveryWorker.schedule(getApplication(), repository)
    }
    fun retry(job: Delivery) = operation {
        repository.retry(job.id)
        DeliveryWorker.schedule(getApplication(), repository)
    }
    fun configure(owner: String, repo: String, branch: String, token: String, enabled: Boolean) {
        operation {
            TargetValidation.validate(owner, repo, branch)
            val old = repository.target()
            val unchanged = old != null && old.owner == owner && old.repo == repo && old.branch == branch
            val credential = if (unchanged) old!!.credential else newId()
            val secret = if (token.isBlank() && unchanged) CredentialStore(getApplication()).read(credential) else token.trim()
            val provisional = Target(credential, owner, repo, branch, 0, false, credential)
            val repoId = withContext(Dispatchers.IO) { GitHubClient(NetworkTransport(provisional, secret)).check(provisional) }
            if (unchanged && old!!.repositoryId != repoId) error("仓库身份变化，原任务不转投，请核对目标")
            val targetId = digest("$repoId\n$branch".toByteArray(Charsets.UTF_8)).take(32)
            CredentialStore(getApplication()).save(targetId, secret)
            val target = provisional.copy(id = targetId, repositoryId = repoId, enabled = enabled, credential = targetId)
            repository.setTarget(target)
            if (enabled) DeliveryWorker.schedule(getApplication(), repository)
            mutable.update { it.copy(message = "配置已保存；只读连接检查通过，尚未验证真实写入。旧笔记不会自动投递") }
        }
    }
    fun setEnabled(enabled: Boolean) = operation {
        val target = repository.target() ?: error("请先检查并保存仓库配置")
        repository.setTarget(target.copy(enabled = enabled))
        if (enabled) DeliveryWorker.schedule(getApplication(), repository)
        mutable.update { it.copy(message = if (enabled) "已开启当前仓库投递，草稿和历史不会自动上传" else "后续投递已暂停，已发出的请求仍需确认") }
    }
    fun privateFile(relative: String) = repository.file(relative)
    fun exportFiles(note: Note): List<Pair<String, ByteArray>> {
        val own = repository.deliveries().find { it.noteId == note.id && it.revision == note.revision }
        val path = own?.path?.takeIf { it.isNotBlank() } ?: "00_Inbox/${timestamp(note.created)}.md"
        val parentPath = note.parentId?.let { parent -> repository.deliveries().find { it.noteId == parent }?.receipt?.path }
        return repository.bundle(note, path, parentPath).map { it.path to java.util.Base64.getDecoder().decode(it.base64) }
    }
    override fun onCleared() { autosave?.cancel(); local.close(); super.onCleared() }
}
