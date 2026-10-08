package xyz.leedaud.echo;

import org.junit.Test;
import static org.junit.Assert.*;

public class NavigationPolicyTest {
    @Test public void preservesTrustedRoutes() {
        assertTrue(NavigationPolicy.isTrusted(NavigationPolicy.HOME));
        assertTrue(NavigationPolicy.isTrusted("https://memos.leedaud.xyz:443/memos/abc?from=list#edit"));
        assertTrue(NavigationPolicy.isTrusted("https://MEMOS.LEEDAUD.XYZ/auth"));
    }

    @Test public void rejectsLookalikesAndUntrustedTransports() {
        String[] urls = {"http://memos.leedaud.xyz/", "https://memos.leedaud.xyz.evil.test/",
                "https://memos.leedaud.xyz@evil.test/", "https://user@memos.leedaud.xyz/",
                "https://memos.leedaud.xyz:444/", "file:///data/data/xyz.leedaud.echo/",
                "content://private/", "javascript:alert(1)", "data:text/html,x", "//memos.leedaud.xyz/",
                "https://memos.leedaud.xyz\\@evil.test/", "https://memos.leedaud.xyz./", "", null};
        for (String url : urls) assertFalse(String.valueOf(url), NavigationPolicy.isTrusted(url));
    }

    @Test public void opensOnlyExplicitExternalProtocols() {
        assertTrue(NavigationPolicy.canOpenExternally("https://github.com/LeeDaud/Echo"));
        assertTrue(NavigationPolicy.canOpenExternally("obsidian://open?vault=Echo"));
        assertTrue(NavigationPolicy.canOpenExternally("mailto:user@example.com"));
        assertTrue(NavigationPolicy.canOpenExternally("tel:123"));
        for (String url : new String[]{"intent://evil/", "http://example.com/", "javascript:alert(1)",
                "file:///private/", "data:text/html,x", "https://user@example.com/", "https:/broken", null}) {
            assertFalse(String.valueOf(url), NavigationPolicy.canOpenExternally(url));
        }
    }

    @Test public void downloadsOnlyTrustedFilesAndBlobs() {
        assertTrue(NavigationPolicy.canDownload("https://memos.leedaud.xyz/file/attachments/example"));
        assertTrue(NavigationPolicy.canDownload("blob:https://memos.leedaud.xyz/123"));
        for (String url : new String[]{"blob:https://evil.test/123", "blob:http://memos.leedaud.xyz/123",
                "blob:null/123", "data:image/png;base64,x", "https://github.com/file", null}) {
            assertFalse(String.valueOf(url), NavigationPolicy.canDownload(url));
        }
    }
}
