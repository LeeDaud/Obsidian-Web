package xyz.leedaud.echo.memos

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MemosSessionStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "secrets/memos-session-v1").apply { parentFile!!.mkdirs() })
    private fun key(): SecretKey {
        val alias = "echo.memos.session.v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }
    fun save(session: MemosSession) {
        val data = JSONObject().put("origin", session.origin).put("user", session.user).put("username", session.username)
            .put("access", session.access).put("refresh", session.refresh).put("expires", session.expires).put("active", session.active)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(data.toString().toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (error: Exception) { file.failWrite(stream); throw error }
    }
    fun read(): MemosSession? {
        if (!file.baseFile.exists()) return null
        val bytes = file.readFully()
        require(bytes.size in 28..32768) { "Memos 会话需要重新登录" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val data = JSONObject(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8))
        val origin = memosOrigin(data.getString("origin")); val user = data.getString("user")
        requireResource(user, "users")
        return MemosSession(origin, user, data.getString("username"), data.getString("access"), data.getString("refresh"),
            data.getLong("expires"), data.optBoolean("active", false)).takeIf { it.active }
    }
    fun disconnect(session: MemosSession) = save(session.copy(access = "", refresh = "", expires = 0, active = false))
}
