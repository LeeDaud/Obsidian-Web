package xyz.leedaud.echo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import xyz.leedaud.echo.ui.EchoApp
import xyz.leedaud.echo.ui.NoteViewModel
import xyz.leedaud.echo.memos.MemosViewModel
import xyz.leedaud.echo.ui.LocalWebView
import xyz.leedaud.echo.ui.LocalUiBackend
import xyz.leedaud.echo.storage.NoteRepository
import xyz.leedaud.echo.ui.LocalUiPlatform
import xyz.leedaud.echo.ui.LocalMemosSource

/** Local launcher with an optional, read-only Memos history connection. */
class NativeActivity : ComponentActivity() {
    private val model: NoteViewModel by viewModels()
    private val memos: MemosViewModel by viewModels()
    private var localWeb: LocalWebView? = null
    private var platform: LocalUiPlatform? = null
    private var webMode = false
    private var nativeFallback = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        nativeFallback = savedInstanceState?.getBoolean("native-fallback", false) == true
        webMode = (BuildConfig.LOCAL_UI_DEFAULT || (BuildConfig.DEBUG && intent.getBooleanExtra("local-ui-preview", false)))
            && !nativeFallback && !intent.getBooleanExtra("native-settings", false) && !(BuildConfig.DEBUG && intent.getBooleanExtra("native-legacy-ui", false))
        if (webMode) {
            val version = runCatching { android.webkit.WebView.getCurrentWebViewPackage()?.versionName?.substringBefore('.')?.toIntOrNull() }.getOrNull()
            if (version == null || version < 111) {
                val panel = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(24, 80, 24, 24) }
                panel.addView(android.widget.TextView(this).apply { textSize = 16f; text = "本地原组件需要可用的 Android System WebView（Chromium 111 或以上）。当前设备未满足，未加载页面；本机记录保留。" })
                panel.addView(android.widget.Button(this).apply { text = "返回本机界面"; setOnClickListener {
                    nativeFallback = true; webMode = false
                    setContent { EchoApp(model, memos) }
                } })
                setContentView(panel)
                return
            }
            val frame = android.widget.FrameLayout(this)
            platform = LocalUiPlatform(this)
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(frame) { view, insets ->
                val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
                val keyboard = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom)); insets
            }
            setContentView(frame)
            Thread {
                val repository = NoteRepository.get(this)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        val backend = LocalUiBackend(this, repository, remote = LocalMemosSource(this))
                        platform!!.resolveFile = backend::attachmentFile
                        val id = intent.data?.takeIf { it.scheme == "echo" && it.host == "memos" }?.path?.removePrefix("/")
                        val resource = id?.takeIf { Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,119}").matches(it) }?.let { "memos/$it" }
                        localWeb = LocalWebView(this, backend, platform!!, resource)
                        frame.addView(localWeb, android.widget.FrameLayout.LayoutParams(-1, -1))
                    }
                }
            }.start()
            onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { if (localWeb?.canGoBack() == true) localWeb?.goBack() else finish() }
            })
        } else setContent { EchoApp(model, memos, if (intent.getBooleanExtra("native-settings", false)) "设置" else "首页") }
    }
    override fun onStop() { if (!webMode) model.stash(); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("native-fallback", nativeFallback)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume()
        if (!webMode) model.refreshStatuses()
        if (!webMode) xyz.leedaud.echo.update.AppUpdates.get(this).autoCheck()
        else if (platform?.refreshAfterSettings == true && localWeb != null) { platform!!.refreshAfterSettings = false; localWeb!!.reload() }
    }
    override fun onDestroy() { platform?.destroy(); localWeb?.destroy(); localWeb = null; super.onDestroy() }
}
