package dev.experimental.edgetts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {

    private fun release(vararg assets: Pair<String, String>, prerelease: Boolean = false): String {
        val items = assets.joinToString(",") { (name, url) ->
            """{"name":"$name","browser_download_url":"$url"}"""
        }
        return """
            {
              "html_url": "https://github.com/Mezcalito23/Edge-Read-Aloud-TTS-Android/releases/tag/v9",
              "draft": false,
              "prerelease": $prerelease,
              "assets": [$items]
            }
        """.trimIndent()
    }

    private fun manifest(
        code: Int = 30,
        name: String = "1.2.0",
        apk: String = "app-release.apk",
        schema: Any = 1,
    ): String = """
        {"schemaVersion": $schema, "versionCode": $code, "versionName": "$name", "apkAssetName": "$apk"}
    """.trimIndent()

    private val apkUrl =
        "https://github.com/Mezcalito23/Edge-Read-Aloud-TTS-Android/releases/download/v9/app-release.apk"
    private val otherUrl =
        "https://github.com/Mezcalito23/Edge-Read-Aloud-TTS-Android/releases/download/v9/other.apk"
    private val jsonUrl =
        "https://github.com/Mezcalito23/Edge-Read-Aloud-TTS-Android/releases/download/v9/update.json"

    @Test
    fun equalVersionIsUpToDate() {
        val result = UpdateCheck.interpret(
            30,
            release("update.json" to jsonUrl, "app-release.apk" to apkUrl),
            manifest(code = 30),
        )
        assertTrue(result is UpdateResult.UpToDate)
        assertEquals(30, (result as UpdateResult.UpToDate).versionCode)
    }

    @Test
    fun olderRemoteDoesNotOfferDowngrade() {
        val result = UpdateCheck.interpret(
            40,
            release("update.json" to jsonUrl, "app-release.apk" to apkUrl),
            manifest(code = 30),
        )
        val newer = result as UpdateResult.InstalledIsNewer
        assertEquals(40, newer.installedCode)
        assertEquals(30, newer.remoteCode)
        assertFalse(result is UpdateResult.Available)
    }

    @Test
    fun newerRemoteSelectsTheNamedApkNotTheFirstOne() {
        val result = UpdateCheck.interpret(
            26,
            release(
                "other.apk" to otherUrl,
                "update.json" to jsonUrl,
                "app-release.apk" to apkUrl,
            ),
            manifest(code = 27, apk = "app-release.apk"),
        )
        val available = result as UpdateResult.Available
        assertEquals(apkUrl, available.apkUrl)
        assertEquals(27, available.remoteCode)
        assertFalse(available.apkUrl.contains("other.apk"))
    }

    @Test
    fun missingNamedApkIsIncomplete() {
        val result = UpdateCheck.interpret(
            26,
            release("update.json" to jsonUrl, "other.apk" to otherUrl),
            manifest(code = 27, apk = "app-release.apk"),
        )
        assertTrue(result is UpdateResult.Incomplete)
        assertFalse(result is UpdateResult.Available)
        assertFalse(result is UpdateResult.UpToDate)
    }

    @Test
    fun rejectsAUrlWrittenInsideTheManifest() {
        val body = """
            {
              "schemaVersion": 1,
              "versionCode": 27,
              "versionName": "1.2.0",
              "apkAssetName": "app-release.apk",
              "browser_download_url": "https://evil.example/app.apk"
            }
        """.trimIndent()
        val result = UpdateCheck.interpret(
            26,
            release("update.json" to jsonUrl),
            body,
        )
        assertTrue(result is UpdateResult.Incomplete)
    }

    @Test
    fun rejectsNonHttpsAsset() {
        val result = UpdateCheck.interpret(
            26,
            release(
                "update.json" to jsonUrl,
                "app-release.apk" to "http://github.com/app-release.apk",
            ),
            manifest(code = 27),
        )
        assertTrue(result is UpdateResult.Incomplete)
    }

    @Test
    fun missingManifestIsNotUpToDate() {
        val result = UpdateCheck.interpret(
            26,
            release("app-release.apk" to apkUrl),
            null,
        )
        assertTrue(result is UpdateResult.CannotVerify)
        assertFalse(result is UpdateResult.UpToDate)
    }

    @Test
    fun invalidAndIncompleteJson() {
        assertTrue(UpdateCheck.interpret(1, release(), "not-json") is UpdateResult.InvalidMetadata)
        assertTrue(
            UpdateCheck.interpret(1, release(), """{"schemaVersion":1}""")
                is UpdateResult.InvalidMetadata
        )
        assertTrue(
            UpdateCheck.interpret(
                1,
                release(),
                """{"schemaVersion":1,"versionCode":"27","versionName":"1","apkAssetName":"a.apk"}""",
            ) is UpdateResult.InvalidMetadata
        )
    }

    @Test
    fun unsupportedSchema() {
        val result = UpdateCheck.interpret(1, release(), manifest(schema = 2))
        assertTrue(result is UpdateResult.UnsupportedSchema)
        assertFalse(result is UpdateResult.UpToDate)
    }

    @Test
    fun prereleaseIsNotAStableRelease() {
        val result = UpdateCheck.interpret(
            1,
            release("update.json" to jsonUrl, prerelease = true),
            manifest(),
        )
        assertTrue(result is UpdateResult.NoStableRelease)
    }

    @Test
    fun unreadableReleaseIsNotUpToDate() {
        val result = UpdateCheck.interpret(26, "nope", null)
        assertTrue(result is UpdateResult.CannotVerify)
    }
}
