package xyz.leedaud.echo.memos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

data class MemosAccount(val origin: String, val user: String, val username: String, val scope: String)
data class MemosState(val ready: Boolean = false, val account: MemosAccount? = null, val items: List<MemosMemo> = emptyList(),
    val cached: List<MemosCachedMemo> = emptyList(), val next: String = "", val archived: Boolean = false,
    val busy: Boolean = false, val offline: Boolean = false, val message: String? = null)

class MemosViewModel(application: Application) : AndroidViewModel(application) {
    private val sessions = MemosSessionStore(application)
    private val history = MemosHistoryStore(File(application.filesDir, "memos-history"))
    private val mutex = Mutex()
    private var client: MemosClient? = null
    private val mutable = MutableStateFlow(MemosState())
    val state: StateFlow<MemosState> = mutable
    fun dismissMessage() { mutable.update { it.copy(message = null) } }
    init {
        operation {
            val session = sessions.read()
            if (session != null) {
                client = newClient(session)
                mutable.update { it.copy(account = account(session), cached = history.list(session), offline = true) }
            }
            mutable.update { it.copy(ready = true) }
        }
    }
    private fun account(s: MemosSession) = MemosAccount(s.origin, s.user, s.username, s.scope)
    private fun newClient(s: MemosSession? = null) = MemosClient(MemosHttpTransport(), s, onSession = sessions::save)
    private fun operation(work: suspend () -> Unit) {
        viewModelScope.launch {
            mutex.withLock {
                mutable.update { it.copy(busy = true, message = null) }
                try { withContext(Dispatchers.IO) { work() } }
                catch (error: Exception) {
                    mutable.update { it.copy(ready = true, message = (error as? MemosFailure)?.publicMessage ?: "操作未完成，请重试；本机数据保留") }
                }
                finally { mutable.update { it.copy(busy = false) } }
            }
        }
    }
    fun login(origin: String, username: String, password: String) = operation {
        val next = newClient()
        val session = next.signIn(origin, username, password)
        client = next
        mutable.value = MemosState(ready = true, account = account(session), busy = true)
        mutable.update { it.copy(cached = history.list(session)) }
        try {
            val page = next.list(false)
            mutable.update { it.copy(items = page.memos.distinctBy { memo -> memo.name }, next = page.next, message = "已连接原 Memos，历史读取不会投递") }
        } catch (error: Exception) {
            mutable.update { it.copy(offline = true, message = (error as? MemosFailure)?.publicMessage ?: "已登录，历史暂未读取；可刷新重试") }
        }
    }
    fun refresh(archived: Boolean = mutable.value.archived, more: Boolean = false) = operation {
        val active = client ?: throw MemosFailure("请先连接 Memos")
        val old = mutable.value
        if (more && old.next.isBlank()) return@operation
        try {
            val page = active.list(archived, if (more) old.next else "")
            if (more && page.next.isNotBlank() && page.next == old.next) throw MemosFailure("分页标识没有推进，请重新刷新")
            val items = (if (more) old.items + page.memos else page.memos).associateBy { it.name }.values.toList()
            mutable.update { it.copy(items = items, next = page.next, archived = archived, offline = false) }
        } catch (error: Exception) {
            mutable.update { it.copy(archived = archived, offline = true) }
            throw error
        }
    }
    fun saveOffline(memo: MemosMemo) = operation {
        val active = client ?: throw MemosFailure("请先连接 Memos")
        val session = active.session ?: throw MemosFailure("请重新登录")
        history.cache(session, memo, active)
        mutable.update { it.copy(cached = history.list(session), message = "正文和全部附件已核验并离线保存，没有上传") }
    }
    fun localCopy(memo: MemosMemo, deliver: (MemosCachedMemo, String) -> Unit) = operation {
        val session = client?.session ?: throw MemosFailure("请先登录原账号")
        val cached = history.load(session, memo.name) ?: throw MemosFailure("请先离线保存此备忘录")
        if (cached.memo.version != memo.version) throw MemosFailure("当前版本尚未离线保存，请先保存当前版本")
        withContext(Dispatchers.Main) { deliver(cached, session.origin) }
    }
    fun disconnect() = operation {
        val active = client
        val session = active?.session
        if (session != null) sessions.disconnect(session)
        client = null
        mutable.value = MemosState(ready = true, busy = true, message = "已断开，历史缓存保留但不再显示；本机笔记不受影响")
        runCatching { active?.signOut() }
    }
}
