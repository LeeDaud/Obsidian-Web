package xyz.leedaud.echo.update

import android.content.Intent
import android.os.Looper
import android.view.View
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateActivityTest {
    @Test fun originalThemeUpdatePageCanRenderWithoutStartingAnyInstaller() {
        val controller = Robolectric.buildActivity(UpdateActivity::class.java, Intent().putExtra("drafts-confirmed", true).putExtra("update-dark", true)).setup()
        try {
            val activity = controller.get()
            val content = activity.findViewById<View>(android.R.id.content)
            content.measure(View.MeasureSpec.makeMeasureSpec(430, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY))
            content.layout(0, 0, 430, 900)
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull(content)
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally { controller.pause().stop().destroy() }
    }
}
