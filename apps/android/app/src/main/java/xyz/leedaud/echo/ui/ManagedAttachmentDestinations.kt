package xyz.leedaud.echo.ui

import org.commonmark.internal.util.Escaping
import org.json.JSONArray
import java.net.URI

/** Main-frame parser supplies spans; native code rechecks bounds, origin and owned resources. */
internal object ManagedAttachmentDestinations {
    fun rewrite(source: String, spans: JSONArray, attachments: Map<String, Pair<String, String>>, origin: String): String {
        if (spans.length() > 10000) throw LocalUiFailure("附件引用过多")
        val base = URI(origin)
        var previous = 0
        val result = StringBuilder()
        for (i in 0 until spans.length()) {
            val span = spans.getJSONObject(i)
            val start = span.getInt("start"); val end = span.getInt("end")
            if (span.getDouble("start") != start.toDouble() || span.getDouble("end") != end.toDouble()) throw LocalUiFailure("正文引用位置无效")
            if (start < previous || end <= start || end > source.length) throw LocalUiFailure("正文引用位置已变化")
            val name = span.getString("name")
            val attachment = attachments[name] ?: throw LocalUiFailure("附件不属于当前记录")
            val url = try { base.resolve(URI(Escaping.unescapeString(source.substring(start, end)).replace(" ", "%20"))).normalize() }
                catch (_: Exception) { throw LocalUiFailure("附件引用地址无效") }
            val path = "/file/$name"
            fun port(uri: URI) = if (uri.port != -1) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
            if (url.userInfo != null || !url.scheme.equals(base.scheme, true) || !url.host.equals(base.host, true) || port(url) != port(base)
                || (url.path != path && url.path != "$path/${attachment.first}")) throw LocalUiFailure("附件来源或名称不匹配")
            result.append(source, previous, start).append(attachment.second)
            previous = end
        }
        return result.append(source, previous, source.length).toString()
    }
}
