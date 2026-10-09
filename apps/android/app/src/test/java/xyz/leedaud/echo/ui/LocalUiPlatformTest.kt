package xyz.leedaud.echo.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalUiPlatformTest {
    private lateinit var controller: ActivityController<ComponentActivity>
    private lateinit var activity: ComponentActivity
    private lateinit var platform: LocalUiPlatform
    @Before fun setup() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        activity = controller.get()
        platform = LocalUiPlatform(activity)
        controller.start().resume()
    }
    @After fun close() { platform.destroy(); controller.pause().stop().destroy() }
    private class Request(private val source: String, private val types: Array<String>) : PermissionRequest() {
        var grants = 0
        var denies = 0
        override fun getOrigin(): Uri = Uri.parse(source)
        override fun getResources(): Array<String> = types
        override fun grant(resources: Array<String>) { grants++ }
        override fun deny() { denies++ }
    }
    private fun params() = object : WebChromeClient.FileChooserParams() {
        override fun getMode() = MODE_OPEN_MULTIPLE
        override fun getAcceptTypes() = arrayOf("text/plain")
        override fun isCaptureEnabled() = false
        override fun getTitle(): CharSequence? = null
        override fun getFilenameHint(): String? = null
        override fun createIntent() = Intent(Intent.ACTION_OPEN_DOCUMENT)
    }
    @Test fun chooserCancelAndDestroyResolveCallbackWithoutFiles() {
        var calls = 0
        assertTrue(platform.choose(params()) { assertNull(it); calls++ })
        val launched = shadowOf(activity).nextStartedActivityForResult
        activity.activityResultRegistry.dispatchResult(launched.requestCode, Activity.RESULT_CANCELED, null)
        assertEquals(1, calls)
        platform.choose(params()) { assertNull(it); calls++ }
        platform.destroy()
        platform.destroy()
        assertEquals(2, calls)
        assertFalse(platform.choose(params()) { assertNull(it); calls++ })
        assertEquals(3, calls)
    }
    @Test fun microphoneRejectsForeignOriginsVideoAndEmptyRequests() {
        for (request in listOf(Request("https://example.test", arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)),
            Request(LocalWebView.ORIGIN, arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)), Request(LocalWebView.ORIGIN, emptyArray()))) {
            platform.microphone(request)
            assertEquals(1, request.denies); assertEquals(0, request.grants)
        }
    }
    @Test fun canceledMicrophoneRequestCannotBeGrantedByLatePermissionResult() {
        shadowOf(activity.application).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val request = Request(LocalWebView.ORIGIN, arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        platform.microphone(request)
        val code = shadowOf(activity).lastRequestedPermission.requestCode
        platform.cancelMicrophone(request)
        activity.onRequestPermissionsResult(code, arrayOf(Manifest.permission.RECORD_AUDIO), intArrayOf(android.content.pm.PackageManager.PERMISSION_GRANTED))
        assertEquals(0, request.grants)
        assertEquals(0, request.denies)
    }
    @Test fun grantedMicrophoneCannotBeUsedAfterPlatformIsDestroyed() {
        shadowOf(activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val request = Request(LocalWebView.ORIGIN, arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        platform.microphone(request)
        assertEquals(1, request.grants)
        platform.destroy()
        val late = Request(LocalWebView.ORIGIN, arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        platform.microphone(late)
        assertEquals(0, late.grants); assertEquals(1, late.denies)
        var replied = false
        platform.handle("platform.settings", JSONObject()) { value, error -> assertNull(value); assertNotNull(error); replied = true }
        assertTrue(replied)
    }
}
