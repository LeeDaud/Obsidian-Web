package xyz.leedaud.echo;

import java.net.URI;
import java.net.URISyntaxException;

/** Only the fixed Memos HTTPS origin may navigate inside the app. */
final class NavigationPolicy {
    static final String HOME = "https://memos.leedaud.xyz/";

    static boolean isTrusted(String value) {
        URI uri = parse(value);
        return uri != null && "https".equalsIgnoreCase(uri.getScheme())
                && "memos.leedaud.xyz".equalsIgnoreCase(uri.getHost())
                && uri.getRawUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    static boolean canOpenExternally(String value) {
        URI uri = parse(value);
        if (uri == null || uri.getScheme() == null || uri.getRawUserInfo() != null) return false;
        String scheme = uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        return ("https".equals(scheme) && uri.getHost() != null)
                || "mailto".equals(scheme) || "tel".equals(scheme) || "obsidian".equals(scheme);
    }

    static boolean canDownload(String value) {
        return isTrusted(value) || (value != null && value.startsWith("blob:") && isTrusted(value.substring(5)));
    }

    private static URI parse(String value) {
        if (value == null) return null;
        try { return new URI(value); }
        catch (URISyntaxException error) { return null; }
    }
}
