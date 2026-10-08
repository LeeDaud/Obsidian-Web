package xyz.leedaud.echo;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.WebView;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Device-only synthetic smoke checks. No login, Memo save, upload or repository access. */
public final class AndroidSmokeInstrumentation extends Instrumentation {
    private MainActivity activity;
    private WebView web;
    private static final String FIXTURE = "<!doctype html><meta name='viewport' content='width=device-width'>"
            + "<textarea aria-label='合成笔记'></textarea><script>"
            + "const key='__echo_android_smoke_draft';const input=document.querySelector('textarea');"
            + "input.value=localStorage.getItem(key)||'';"
            + "window.addEventListener('pagehide',()=>localStorage.setItem(key,input.value));"
            + "window.fixtureReady=true;</script>";

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        new Thread(() -> {
            Bundle report = new Bundle();
            try {
                launchFixture();
                runOnMainSync(() -> {
                    require(web.getSettings().getDomStorageEnabled(), "DOM storage disabled");
                    require(!web.getSettings().getAllowFileAccess(), "File access enabled");
                    require(!web.getSettings().getAllowContentAccess(), "Content navigation enabled");
                });
                evaluate("localStorage.removeItem('__echo_android_smoke_draft');document.querySelector('textarea').value='中文合成草稿';");
                runOnMainSync(() -> callActivityOnPause(activity));
                require("\"中文合成草稿\"".equals(evaluate("localStorage.getItem('__echo_android_smoke_draft')")),
                        "Pause did not flush the synthetic draft");
                runOnMainSync(() -> { callActivityOnResume(activity); activity.finish(); });
                waitForIdleSync();
                launchFixture();
                require("\"中文合成草稿\"".equals(evaluate("document.querySelector('textarea').value")),
                        "WebView profile did not retain the synthetic draft across recreation");
                evaluate("localStorage.removeItem('__echo_android_smoke_draft')");
                report.putString("stream", "\nPASS: WebView settings, pause draft flush, recreation persistence.\n"
                        + "This synthetic fixture does not certify Memos login, file picker or GitHub delivery.\n");
                finish(Activity.RESULT_OK, report);
            } catch (Throwable error) {
                report.putString("stream", "\nFAIL: " + error.getClass().getSimpleName() + ": " + error.getMessage() + "\n");
                finish(Activity.RESULT_CANCELED, report);
            } finally {
                if (activity != null) runOnMainSync(() -> activity.finish());
            }
        }, "echo-android-smoke").start();
    }

    private void launchFixture() throws Exception {
        Intent intent = new Intent(getTargetContext(), MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = (MainActivity) startActivitySync(intent);
        runOnMainSync(() -> {
            web = activity.findViewById(R.id.memos_webview);
            web.stopLoading();
            web.loadDataWithBaseURL(NavigationPolicy.HOME, FIXTURE, "text/html", "UTF-8", NavigationPolicy.HOME);
        });
        for (int attempt = 0; attempt < 30; attempt++) {
            if ("true".equals(evaluate("window.fixtureReady===true"))) return;
            Thread.sleep(100);
        }
        throw new IllegalStateException("Synthetic page did not initialize");
    }

    private String evaluate(String script) throws Exception {
        AtomicReference<String> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        runOnMainSync(() -> web.evaluateJavascript(script, value -> { result.set(value); done.countDown(); }));
        if (!done.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("WebView evaluation timed out");
        return result.get();
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
