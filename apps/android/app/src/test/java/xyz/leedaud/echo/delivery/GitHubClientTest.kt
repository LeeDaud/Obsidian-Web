package xyz.leedaud.echo.delivery

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import xyz.leedaud.echo.notes.*
import xyz.leedaud.echo.notes.Target
import java.io.IOException
import java.util.Base64

class GitHubClientTest {
    private val target = Target("target", "owner", "repo", "main", 10, true)
    private fun file(path: String, text: String): DeliveryFile {
        val bytes = text.toByteArray(); return DeliveryFile(path, Base64.getEncoder().encodeToString(bytes), digest(bytes))
    }
    private val files = listOf(file("00_Inbox/20261008-143000.md", "正文"), file("attachments/20261008-143000/20261008-143000.png", "image-bytes"))
    @Test fun publishesBodyAndAttachmentInOneCommitAndVerifies() {
        val fake = FakeGitHub(); var checkpoint: Attempt? = null
        val attempt = GitHubClient(fake).publish(target, files, null) { checkpoint = it; assertEquals("h0", fake.head) }
        assertEquals(attempt, checkpoint); assertEquals(attempt.commit, fake.head)
        assertEquals(1, fake.commits); assertEquals(2, fake.blobs.size)
        assertEquals(1, fake.patches)
    }
    @Test fun lostRefReplyRecoversWithoutAnotherCommitOrPatch() {
        val fake = FakeGitHub(); fake.loseReply = true; var checkpoint: Attempt? = null
        assertThrows(IOException::class.java) { GitHubClient(fake).publish(target, files, null) { checkpoint = it } }
        assertNotNull(checkpoint)
        val result = GitHubClient(fake).publish(target, files, checkpoint) { fail("must reuse persisted attempt") }
        assertEquals(checkpoint, result); assertEquals(1, fake.commits); assertEquals(1, fake.patches)
    }
    @Test fun unpublishedCheckpointCanResumeBeforeRefMove() {
        val fake = FakeGitHub(); var saved: Attempt? = null
        assertThrows(IOException::class.java) { GitHubClient(fake).publish(target, files, null) { saved = it; throw IOException("crash before ref") } }
        assertEquals("h0", fake.head)
        GitHubClient(fake).publish(target, files, saved) { fail("no new attempt") }
        assertEquals(1, fake.commits); assertEquals(1, fake.patches)
    }
    @Test fun conflictingCaseAndAncestorCannotBeOverwritten() {
        for (path in listOf("00_inbox/20261008-143000.md", "00_Inbox")) {
            val fake = FakeGitHub()
            fake.trees["t0"] = listOf(JSONObject().put("path", path).put("type", "blob").put("mode", "100644").put("sha", "existing"))
            val error = assertThrows(DeliveryFailure::class.java) { GitHubClient(fake).publish(target, files, null) {} }
            assertEquals("conflict", error.kind); assertEquals(0, fake.commits)
        }
    }
    @Test fun attachmentCorruptionDoesNotVerifyEvenAfterPublish() {
        val fake = FakeGitHub(); fake.corrupt = true
        val error = assertThrows(DeliveryFailure::class.java) { GitHubClient(fake).publish(target, files, null) {} }
        assertEquals("retryable_error", error.kind); assertEquals(1, fake.commits)
    }
    @Test fun repoIdentityAndPermissionsAreCheckedReadOnly() {
        val fake = FakeGitHub()
        assertEquals(10L, GitHubClient(fake).check(target))
        assertEquals(0, fake.commits)
        assertThrows(DeliveryFailure::class.java) { GitHubClient(fake).check(target.copy(repositoryId = 11)) }
        val forbidden = GitHubClient(GitHubTransport { _, _, _ -> ApiResponse(403, JSONObject()) })
        assertEquals("auth_required", assertThrows(DeliveryFailure::class.java) { forbidden.check(target) }.kind)
    }
    @Test fun limitWaitsForRetryAfterAndDoesNotWrite() {
        val client = GitHubClient(GitHubTransport { _, _, _ -> ApiResponse(429, JSONObject(), mapOf("retry-after" to "120")) })
        val start = System.currentTimeMillis()
        val error = assertThrows(DeliveryFailure::class.java) { client.check(target) }
        assertEquals("retryable_error", error.kind); assertTrue(error.retryAt >= start + 120000)
    }
    @Test fun unsafeTargetsCannotReceiveCredentials() {
        for (branch in listOf("../main", "main.lock", "x@{x", "a b", "a//b", "a\\b")) {
            assertThrows(IllegalArgumentException::class.java) { TargetValidation.validate("owner", "repo", branch) }
        }
        assertThrows(IllegalArgumentException::class.java) { TargetValidation.validate("owner@evil.test", "repo", "main") }
    }
    @Test fun truncatedRecursiveTreeNeverMeansPathIsAbsent() {
        val fake = FakeGitHub(); fake.truncated = true
        assertFalse(GitHubClient(fake).available(target, files[0].path, emptyList(), emptySet()))
    }

    private class FakeGitHub : GitHubTransport {
        var head = "h0"; var commits = 0; var patches = 0; var loseReply = false; var corrupt = false; var truncated = false
        val blobs = linkedMapOf<String, String>()
        val trees = mutableMapOf("t0" to emptyList<JSONObject>())
        val commitTrees = mutableMapOf("h0" to "t0")
        override fun request(method: String, path: String, body: JSONObject?): ApiResponse {
            val result = when {
                path.isEmpty() -> JSONObject().put("id", 10)
                path.startsWith("/git/ref/heads/") -> JSONObject().put("object", JSONObject().put("sha", head))
                path.startsWith("/compare/") -> {
                    val from = path.substringAfter("/compare/").substringBefore("...")
                    JSONObject().put("status", when { from == head -> "identical"; head == "h0" -> "behind"; else -> "ahead" })
                }
                method == "GET" && path.startsWith("/git/commits/") -> JSONObject().put("tree", JSONObject().put("sha", commitTrees.getValue(path.substringAfterLast('/'))))
                method == "GET" && path.startsWith("/git/trees/") -> {
                    val recursive = path.contains('?'); val sha = path.substringAfterLast('/').substringBefore('?')
                    val entries = if (truncated && !recursive) listOf(JSONObject().put("path", "00_Inbox").put("type", "blob").put("sha", "b0")) else trees.getValue(sha)
                    JSONObject().put("truncated", truncated && recursive).put("tree", JSONArray(entries))
                }
                method == "POST" && path == "/git/blobs" -> {
                    val sha = "b${blobs.size + 1}"; blobs[sha] = body!!.getString("content"); JSONObject().put("sha", sha)
                }
                method == "POST" && path == "/git/trees" -> {
                    val sha = "t${trees.size}"; val array = body!!.getJSONArray("tree")
                    trees[sha] = (0 until array.length()).map { array.getJSONObject(it) }; JSONObject().put("sha", sha)
                }
                method == "POST" && path == "/git/commits" -> {
                    val sha = "c${++commits}"; commitTrees[sha] = body!!.getString("tree"); JSONObject().put("sha", sha)
                }
                method == "PATCH" -> {
                    assertFalse(body!!.getBoolean("force")); head = body.getString("sha"); patches++
                    if (loseReply) { loseReply = false; throw IOException("lost ref reply") }; JSONObject().put("ref", head)
                }
                method == "GET" && path.startsWith("/git/blobs/") -> {
                    val sha = path.substringAfterLast('/'); val encoded = if (corrupt) Base64.getEncoder().encodeToString("wrong".toByteArray()) else blobs.getValue(sha)
                    JSONObject().put("sha", sha).put("encoding", "base64").put("content", encoded).put("size", Base64.getDecoder().decode(encoded).size)
                }
                else -> error("Unexpected $method $path")
            }
            return ApiResponse(200, result)
        }
    }
}
