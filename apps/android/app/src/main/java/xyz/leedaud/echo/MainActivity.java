package xyz.leedaud.echo;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Insets;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.URLUtil;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.OutputStream;

/** Android client for the existing Memos service; no native upload or credential bridge. */
public final class MainActivity extends Activity {
    private static final int FILE_REQUEST = 10;
    private static final int AUDIO_PERMISSION = 11;
    private static final int LOCATION_PERMISSION = 12;
    private static final int SAVE_FILE_REQUEST = 13;
    private static final int MAX_DOWNLOAD_BYTES = 20 * 1024 * 1024;
    private FrameLayout root;
    private WebView webView;
    private LinearLayout connectionNotice;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest audioRequest;
    private GeolocationPermissions.Callback locationCallback;
    private String locationOrigin;
    private boolean pageFailed;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String downloadSlot;
    private byte[] downloadBytes;
    private boolean savingDownload;
    private String recoveryUrl = NavigationPolicy.HOME;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.surface_page));
        setContentView(root);
        configureInsets();
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
        createWebView();
        // Persist only a trusted route. WebView.saveState does not save editor DOM or drafts.
        // localStorage and cookies remain in WebView's private persistent profile.
        if (state != null && NavigationPolicy.isTrusted(state.getString("route"))) {
            recoveryUrl = state.getString("route");
        }
        webView.loadUrl(recoveryUrl);
    }

    private void configureInsets() {
        if (Build.VERSION.SDK_INT >= 27 && Build.VERSION.SDK_INT < 30) {
            boolean dark = (getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            int flags = getWindow().getDecorView().getSystemUiVisibility();
            if (dark) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            else flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            boolean dark = (getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            int appearance = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            getWindow().getInsetsController().setSystemBarsAppearance(dark ? 0 : appearance, appearance);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
                return WindowInsets.CONSUMED;
            });
        } else {
            root.setFitsSystemWindows(true);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void createWebView() {
        webView = new WebView(this);
        webView.setId(R.id.memos_webview);
        webView.setBackgroundColor(getColor(R.color.surface_page));
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setSafeBrowsingEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        webView.setWebViewClient(new MemosClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                String[] resources = request.getResources();
                if (!NavigationPolicy.isTrusted(request.getOrigin().toString())
                        || resources.length != 1 || !PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resources[0])
                        || audioRequest != null) {
                    request.deny();
                    return;
                }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                } else {
                    audioRequest = request;
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION);
                }
            }

            @Override public void onPermissionRequestCanceled(PermissionRequest request) {
                if (audioRequest == request) audioRequest = null;
            }

            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (!NavigationPolicy.isTrusted(origin) || locationCallback != null) {
                    callback.invoke(origin, false, false);
                    return;
                }
                if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false);
                } else {
                    locationOrigin = origin;
                    locationCallback = callback;
                    requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_PERMISSION);
                }
            }

            @Override public void onGeolocationPermissionsHidePrompt() {
                locationCallback = null;
                locationOrigin = null;
            }

            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                                       FileChooserParams params) {
                cancelFileChooser();
                if (!NavigationPolicy.isTrusted(view.getUrl()) || params.getMode() == FileChooserParams.MODE_SAVE) {
                    callback.onReceiveValue(null);
                    return true;
                }
                fileCallback = callback;
                // System document picker grants access only to explicitly selected content URIs.
                Intent intent = params.createIntent();
                intent.setAction(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try { startActivityForResult(intent, FILE_REQUEST); }
                catch (ActivityNotFoundException error) {
                    cancelFileChooser();
                    Toast.makeText(MainActivity.this, R.string.chooser_unavailable, Toast.LENGTH_LONG).show();
                }
                return true;
            }
        });
        webView.setDownloadListener((url, userAgent, disposition, mime, size) ->
                downloadFile(url, URLUtil.guessFileName(url, disposition, mime), mime, size));
        root.addView(webView, 0, new FrameLayout.LayoutParams(-1, -1));
    }

    private void downloadFile(String url, String filename, String mime, long size) {
        boolean allowed = NavigationPolicy.canDownload(url);
        if (webView == null || !NavigationPolicy.isTrusted(webView.getUrl()) || !allowed
                || downloadSlot != null || downloadBytes != null || savingDownload || size > MAX_DOWNLOAD_BYTES) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_LONG).show();
            return;
        }
        String slot = "__echo_download_" + java.util.UUID.randomUUID().toString().replace("-", "");
        downloadSlot = slot;
        String key = JSONObject.quote(slot);
        // No JavaScript interface: a bounded one-shot result is read with evaluateJavascript.
        // Fetch runs in the trusted page, so existing session cookies remain in WebView.
        String script = "(() => { window[" + key + "]={state:'pending'}; fetch(" + JSONObject.quote(url)
                + ", {credentials:'same-origin',redirect:'error'}).then(r=>{if(!r.ok)throw Error();return r.blob();})"
                + ".then(b=>{if(b.size>" + MAX_DOWNLOAD_BYTES + ")throw Error();const f=new FileReader();"
                + "f.onload=()=>window[" + key + "]={state:'ready',data:f.result,mime:b.type};"
                + "f.onerror=()=>window[" + key + "]={state:'error'};f.readAsDataURL(b);})"
                + ".catch(()=>window[" + key + "]={state:'error'});})();";
        webView.evaluateJavascript(script, null);
        Toast.makeText(this, R.string.download_preparing, Toast.LENGTH_SHORT).show();
        pollDownload(slot, filename, mime, SystemClock.elapsedRealtime() + 30000);
    }

    private void pollDownload(String slot, String filename, String mime, long deadline) {
        if (!slot.equals(downloadSlot)) return;
        if (webView == null || !NavigationPolicy.isTrusted(webView.getUrl()) || SystemClock.elapsedRealtime() > deadline) {
            finishDownload(false);
            return;
        }
        webView.evaluateJavascript("window[" + JSONObject.quote(slot) + "] || null", value -> {
            if (!slot.equals(downloadSlot)) return;
            try {
                JSONObject result = new JSONObject(value);
                if ("pending".equals(result.optString("state"))) {
                    handler.postDelayed(() -> pollDownload(slot, filename, mime, deadline), 150);
                    return;
                }
                if (!"ready".equals(result.optString("state"))) throw new IllegalStateException();
                String encoded = result.getString("data");
                int separator = encoded.indexOf(',');
                if (separator < 0 || encoded.length() > MAX_DOWNLOAD_BYTES * 4 / 3 + 1024) throw new IllegalStateException();
                downloadBytes = android.util.Base64.decode(encoded.substring(separator + 1), android.util.Base64.DEFAULT);
                if (downloadBytes.length > MAX_DOWNLOAD_BYTES) throw new IllegalStateException();
                clearDownloadSlot();
                Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                save.addCategory(Intent.CATEGORY_OPENABLE);
                String fileMime = result.optString("mime", mime);
                save.setType(fileMime != null && fileMime.matches("[\\w.+-]+/[\\w.+-]+") ? fileMime : "application/octet-stream");
                save.putExtra(Intent.EXTRA_TITLE, filename.replaceAll("[\\\\/\\r\\n]", "_"));
                startActivityForResult(save, SAVE_FILE_REQUEST);
            } catch (Exception error) { finishDownload(false); }
        });
    }

    private void clearDownloadSlot() {
        if (downloadSlot != null && webView != null && NavigationPolicy.isTrusted(webView.getUrl())) {
            webView.evaluateJavascript("delete window[" + JSONObject.quote(downloadSlot) + "];", null);
        }
        downloadSlot = null;
    }

    private void finishDownload(boolean success) {
        clearDownloadSlot();
        downloadBytes = null;
        savingDownload = false;
        Toast.makeText(this, success ? R.string.download_saved : R.string.download_failed, Toast.LENGTH_LONG).show();
    }

    private final class MemosClient extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (NavigationPolicy.isTrusted(url)) return false;
            if (request.isForMainFrame() && url.startsWith("blob:") && NavigationPolicy.canDownload(url)) {
                downloadFile(url, URLUtil.guessFileName(url, null, null), null, -1);
                return true;
            }
            if (request.isForMainFrame() && request.hasGesture()) openExternal(url);
            return true;
        }

        @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            if (!NavigationPolicy.isTrusted(url)) {
                view.stopLoading();
                showConnectionNotice(R.string.connection_message);
                return;
            }
            recoveryUrl = url;
            pageFailed = false;
        }

        @Override public void onPageCommitVisible(WebView view, String url) {
            if (!pageFailed && NavigationPolicy.isTrusted(url)) dismissNotice();
        }

        @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) showConnectionNotice(R.string.connection_message);
        }

        @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
            if (request.isForMainFrame()) showConnectionNotice(R.string.connection_message);
        }

        @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel();
            showConnectionNotice(R.string.connection_message);
        }

        @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            cancelFileChooser();
            root.removeView(view);
            view.destroy();
            webView = null;
            showConnectionNotice(R.string.renderer_message);
            return true;
        }
    }

    private void openExternal(String url) {
        if (NavigationPolicy.canOpenExternally(url)) {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            try { startActivity(intent); return; }
            catch (ActivityNotFoundException ignored) { /* Show a concise message below. */ }
        }
        Toast.makeText(this, R.string.unsupported_link, Toast.LENGTH_SHORT).show();
    }

    private void showConnectionNotice(int message) {
        pageFailed = true;
        if (isFinishing() || isDestroyed()) return;
        dismissNotice();
        connectionNotice = new LinearLayout(this);
        connectionNotice.setOrientation(LinearLayout.VERTICAL);
        connectionNotice.setPadding(dp(24), dp(24), dp(24), dp(24));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(getColor(R.color.surface_card));
        background.setCornerRadius(dp(16));
        background.setStroke(dp(1), getColor(R.color.surface_muted));
        connectionNotice.setBackground(background);
        connectionNotice.setElevation(dp(2));
        TextView title = new TextView(this);
        title.setText(R.string.connection_title);
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(getColor(R.color.text_primary));
        connectionNotice.addView(title);
        TextView description = new TextView(this);
        description.setText(message);
        description.setTextSize(14);
        description.setTextColor(getColor(R.color.text_secondary));
        description.setPadding(0, dp(12), 0, dp(12));
        description.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        connectionNotice.addView(description);
        LinearLayout actions = new LinearLayout(this);
        Button close = new Button(this);
        close.setText(R.string.dismiss);
        close.setMinHeight(dp(48));
        close.setOnClickListener(view -> dismissNotice());
        actions.addView(close, new LinearLayout.LayoutParams(0, -2, 1));
        Button retry = new Button(this);
        retry.setText(R.string.retry);
        retry.setMinHeight(dp(48));
        retry.setOnClickListener(view -> {
            dismissNotice();
            if (webView == null) createWebView();
            webView.loadUrl(recoveryUrl);
        });
        LinearLayout.LayoutParams retryLayout = new LinearLayout.LayoutParams(0, -2, 1);
        retryLayout.setMarginStart(dp(12));
        actions.addView(retry, retryLayout);
        connectionNotice.addView(actions);
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        layout.setMargins(dp(24), dp(24), dp(24), 0);
        root.addView(connectionNotice, layout);
    }

    private void dismissNotice() {
        if (connectionNotice != null) { root.removeView(connectionNotice); connectionNotice = null; }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void cancelFileChooser() {
        if (fileCallback != null) {
            ValueCallback<Uri[]> callback = fileCallback;
            fileCallback = null;
            callback.onReceiveValue(null);
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == SAVE_FILE_REQUEST) {
            byte[] bytes = downloadBytes;
            downloadBytes = null;
            if (result != RESULT_OK) return;
            Uri destination = data == null ? null : data.getData();
            if (bytes == null || destination == null || !"content".equals(destination.getScheme())) {
                finishDownload(false);
                return;
            }
            savingDownload = true;
            new Thread(() -> {
                boolean success = false;
                try (OutputStream output = getContentResolver().openOutputStream(destination)) {
                    if (output != null) { output.write(bytes); success = true; }
                } catch (Exception ignored) { /* Do not log private content or destination URLs. */ }
                boolean saved = success;
                runOnUiThread(() -> finishDownload(saved));
            }, "echo-file-save").start();
            return;
        }
        if (request != FILE_REQUEST || fileCallback == null) return;
        Uri[] selected = WebChromeClient.FileChooserParams.parseResult(result, data);
        if (webView == null || !NavigationPolicy.isTrusted(webView.getUrl())) selected = null;
        // Never allow a file picker result to expose app-private file:// paths.
        if (selected != null) {
            for (Uri uri : selected) {
                if (!"content".equals(uri.getScheme())) { selected = null; break; }
            }
        }
        ValueCallback<Uri[]> callback = fileCallback;
        fileCallback = null;
        callback.onReceiveValue(selected);
    }

    // API 33+ uses the registered OnBackInvokedCallback; retain this only for API 26–32.
    @SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() {
        handleBack();
    }

    private void handleBack() {
        if (connectionNotice != null) { dismissNotice(); return; }
        if (webView != null && webView.canGoBack()) { webView.goBack(); return; }
        // Keep the editing session alive; returning to the launcher does not upload.
        moveTaskToBack(true);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (webView != null && NavigationPolicy.isTrusted(webView.getUrl())) recoveryUrl = webView.getUrl();
        state.putString("route", recoveryUrl);
        super.onSaveInstanceState(state);
    }

    @Override protected void onPause() {
        // Reuse existing Memos pagehide handlers: flush device drafts and pause SSE,
        // without saving a Memo or invoking the server's delivery API.
        if (webView != null && NavigationPolicy.isTrusted(webView.getUrl())) {
            webView.evaluateJavascript("window.dispatchEvent(new Event('pagehide'));", null);
        }
        CookieManager.getInstance().flush();
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        if (webView != null && NavigationPolicy.isTrusted(webView.getUrl())) {
            webView.evaluateJavascript("window.dispatchEvent(new Event('pageshow'));", null);
        }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        clearDownloadSlot();
        downloadBytes = null;
        cancelFileChooser();
        if (audioRequest != null) { audioRequest.deny(); audioRequest = null; }
        if (locationCallback != null) {
            locationCallback.invoke(locationOrigin, false, false);
            locationCallback = null;
        }
        if (webView != null) {
            root.removeView(webView);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        boolean trusted = webView != null && NavigationPolicy.isTrusted(webView.getUrl());
        if (request == AUDIO_PERMISSION && audioRequest != null) {
            if (trusted && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                audioRequest.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            } else { audioRequest.deny(); }
            audioRequest = null;
        }
        if (request == LOCATION_PERMISSION && locationCallback != null) {
            boolean allowed = trusted && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            locationCallback.invoke(locationOrigin, allowed, false);
            locationCallback = null;
            locationOrigin = null;
        }
    }
}
