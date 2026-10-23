package dev.experimental.edgetts

import org.json.JSONObject
import java.net.URI

/**
 * Compara la versión instalada con la última release estable de GitHub.
 * El criterio es versionCode, leído de un update.json adjunto a esa release.
 * No usa tag_name, versionName ni la fecha.
 */
object UpdateCheck {

    const val SCHEMA_VERSION = 1

    const val LATEST_API =
        "https://api.github.com/repos/Mezcalito23/Edge-Read-Aloud-TTS-Android/releases/latest"

    const val RELEASES_PAGE =
        "https://github.com/Mezcalito23/Edge-Read-Aloud-TTS-Android/releases"

    const val PROJECT_PAGE =
        "https://github.com/Mezcalito23/Edge-Read-Aloud-TTS-Android/"

    const val KOFI_PAGE = "https://ko-fi.com/mezcalito23"

    const val ASSET_NAME = "update.json"

    fun userAgent(versionName: String): String =
        "EdgeReadAloudTTS/$versionName (+$PROJECT_PAGE)"

    fun interpret(
        installedVersionCode: Int,
        releaseJson: String,
        manifestJson: String?,
    ): UpdateResult {
        val release = parseRelease(releaseJson)
            ?: return UpdateResult.CannotVerify(RELEASES_PAGE)
        if (release.draft || release.prerelease) {
            return UpdateResult.NoStableRelease
        }
        val page = release.pageUrl.ifBlank { RELEASES_PAGE }
        if (manifestJson == null) {
            return UpdateResult.CannotVerify(page)
        }
        val manifest = when (val parsed = parseManifest(manifestJson)) {
            is ManifestParse.Ok -> parsed.manifest
            ManifestParse.UnsupportedSchema -> return UpdateResult.UnsupportedSchema(page)
            ManifestParse.Invalid -> return UpdateResult.InvalidMetadata(page)
        }
        val remote = manifest.versionCode
        if (remote == installedVersionCode) {
            return UpdateResult.UpToDate(manifest.versionName, remote)
        }
        if (remote < installedVersionCode) {
            return UpdateResult.InstalledIsNewer(
                installedCode = installedVersionCode,
                remoteCode = remote,
                remoteName = manifest.versionName,
            )
        }
        val apk = release.assets.firstOrNull { it.name == manifest.apkAssetName }
            ?: return UpdateResult.Incomplete(page, manifest.apkAssetName)
        if (!isAllowedDownloadUrl(apk.url)) {
            return UpdateResult.Incomplete(page, manifest.apkAssetName)
        }
        return UpdateResult.Available(
            remoteCode = remote,
            remoteName = manifest.versionName,
            apkUrl = apk.url,
            releasePageUrl = page,
        )
    }

    /** URL del asset update.json, solo si GitHub la publicó en esta release. */
    fun manifestDownloadUrl(releaseJson: String): String? {
        val release = parseRelease(releaseJson) ?: return null
        val asset = release.assets.firstOrNull { it.name == ASSET_NAME } ?: return null
        return asset.url.takeIf { isAllowedDownloadUrl(it) }
    }

    fun isAllowedDownloadUrl(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return false
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        return host == "github.com" || host.endsWith(".githubusercontent.com")
    }
}

data class ReleaseAsset(val name: String, val url: String)

data class GithubRelease(
    val pageUrl: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val assets: List<ReleaseAsset>,
)

data class UpdateManifest(
    val schemaVersion: Int,
    val versionCode: Int,
    val versionName: String,
    val apkAssetName: String,
)

sealed class ManifestParse {
    data class Ok(val manifest: UpdateManifest) : ManifestParse()
    data object Invalid : ManifestParse()
    data object UnsupportedSchema : ManifestParse()
}

sealed class UpdateResult {
    data class UpToDate(val versionName: String, val versionCode: Int) : UpdateResult()
    data class InstalledIsNewer(
        val installedCode: Int,
        val remoteCode: Int,
        val remoteName: String,
    ) : UpdateResult()
    data class Available(
        val remoteCode: Int,
        val remoteName: String,
        val apkUrl: String,
        val releasePageUrl: String,
    ) : UpdateResult()
    data object NoStableRelease : UpdateResult()
    data class CannotVerify(val releasesPage: String) : UpdateResult()
    data class InvalidMetadata(val releasesPage: String) : UpdateResult()
    data class UnsupportedSchema(val releasesPage: String) : UpdateResult()
    data class Incomplete(val releasesPage: String, val apkAssetName: String) : UpdateResult()
    data object RateLimited : UpdateResult()
    data object TimedOut : UpdateResult()
    data object NetworkFailed : UpdateResult()
}

internal fun parseRelease(body: String): GithubRelease? {
    val root = try {
        JSONObject(body)
    } catch (_: Exception) {
        return null
    }
    val assetsJson = root.optJSONArray("assets") ?: return null
    val assets = buildList {
        for (i in 0 until assetsJson.length()) {
            val item = assetsJson.optJSONObject(i) ?: continue
            val name = item.optString("name")
            val url = item.optString("browser_download_url")
            if (name.isNotEmpty() && url.isNotEmpty()) {
                add(ReleaseAsset(name, url))
            }
        }
    }
    return GithubRelease(
        pageUrl = root.optString("html_url"),
        draft = root.optBoolean("draft"),
        prerelease = root.optBoolean("prerelease"),
        assets = assets,
    )
}

internal fun parseManifest(body: String): ManifestParse {
    val root = try {
        JSONObject(body)
    } catch (_: Exception) {
        return ManifestParse.Invalid
    }
    if (!root.has("schemaVersion") || root.isNull("schemaVersion")) {
        return ManifestParse.Invalid
    }
    val schema = integral(root.get("schemaVersion")) ?: return ManifestParse.Invalid
    if (schema != UpdateCheck.SCHEMA_VERSION) {
        return ManifestParse.UnsupportedSchema
    }
    val code = if (root.has("versionCode") && !root.isNull("versionCode")) {
        integral(root.get("versionCode"))
    } else {
        null
    } ?: return ManifestParse.Invalid
    if (code < 0) return ManifestParse.Invalid
    val name = root.optString("versionName")
    val apk = root.optString("apkAssetName")
    if (name.isBlank() || !isAssetFileName(apk)) {
        return ManifestParse.Invalid
    }
    return ManifestParse.Ok(
        UpdateManifest(
            schemaVersion = schema,
            versionCode = code,
            versionName = name,
            apkAssetName = apk,
        )
    )
}

private fun isAssetFileName(name: String): Boolean {
    if (name.isBlank() || name.length > 180) return false
    if (name.contains('/') || name.contains('\\') || name.contains("..")) return false
    if (name.contains("://")) return false
    return true
}

private fun integral(value: Any?): Int? {
    val number = value as? Number ?: return null
    val asLong = number.toLong()
    if (number.toDouble() != asLong.toDouble()) return null
    if (asLong !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return null
    return asLong.toInt()
}
