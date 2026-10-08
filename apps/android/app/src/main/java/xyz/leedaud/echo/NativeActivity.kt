package xyz.leedaud.echo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import xyz.leedaud.echo.ui.EchoApp
import xyz.leedaud.echo.ui.NoteViewModel

/** The launcher runs entirely on the device; no WebView or Memos connection. */
class NativeActivity : ComponentActivity() {
    private val model: NoteViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { EchoApp(model) }
    }
    override fun onStop() { model.stash(); super.onStop() }
    override fun onResume() { super.onResume(); model.refreshStatuses() }
}
