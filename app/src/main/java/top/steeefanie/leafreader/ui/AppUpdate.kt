package top.steeefanie.leafreader.ui

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.system.Os
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

internal class AppUpdate(private val context: Context) {
    data class Release(val version: String, val apkName: String, val sha256: String)

    val apk = File(context.cacheDir, "updates/update.apk")

    fun latest(installedVersion: String): Release? {
        val release = get("$RELEASES/latest/download/SHA256SUMS.txt") { input ->
            input.bufferedReader().useLines { lines ->
                lines.mapNotNull { APK_CHECKSUM.matchEntire(it.trim()) }.firstOrNull()?.destructured
                    ?.let { (sha256, name, version) -> Release(version, name, sha256) }
            }
        } ?: error("最新发布缺少 ARM64 安装包的校验记录")
        val remote = parseVersion(release.version)
        val local = parseVersion(installedVersion)
        if (compareValuesBy(remote, local, { it[0] }, { it[1] }, { it[2] }) <= 0) return null
        require("arm64-v8a" in Build.SUPPORTED_ABIS) { "此设备不支持发布包所需的 ARM64 架构" }
        return release
    }

    suspend fun download(release: Release) {
        val coroutine = currentCoroutineContext()
        val directory = checkNotNull(apk.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "无法创建更新缓存目录" }
        val partial = File.createTempFile("download-", ".apk", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            get("$RELEASES/download/v${release.version}/${release.apkName}") { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutine.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual.equals(release.sha256, ignoreCase = true)) { "更新包 SHA-256 校验失败，可能下载不完整" }
            coroutine.ensureActive()
            validateApk(partial, release.version)
            Os.rename(partial.absolutePath, apk.absolutePath)
        } finally {
            partial.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun validateApk(file: File, version: String) {
        val manager = context.packageManager
        val signingFlag = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = manager.getPackageInfo(context.packageName, signingFlag)
        val candidate = manager.getPackageArchiveInfo(file.absolutePath, signingFlag) ?: error("无法读取更新包信息")
        check(candidate.packageName == installed.packageName && candidate.versionName == version) { "更新包的应用标识或版本不符" }
        check(PackageInfoCompat.getLongVersionCode(candidate) > PackageInfoCompat.getLongVersionCode(installed)) { "更新包的版本号必须高于当前版本" }
        fun PackageInfo.signers(): Set<Signature> =
            (if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures)?.toSet().orEmpty()
        val signers = installed.signers()
        check(signers.isNotEmpty() && signers == candidate.signers()) { "更新包签名与已安装版本不一致" }
    }

    private fun <T> get(address: String, read: (InputStream) -> T): T {
        var url = URL(address)
        repeat(5) {
            check(url.protocol == "https" && url.host in DOWNLOAD_HOSTS) { "更新包下载地址无效" }
            val connection = (url.openConnection() as HttpsURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "personal-reader-android")
            }
            try {
                when (val status = connection.responseCode) {
                    200 -> return connection.inputStream.use(read)
                    301, 302, 303, 307, 308 -> {
                        val location = connection.getHeaderField("Location") ?: throw IOException("更新服务返回无效的跳转地址")
                        url = URL(url, location)
                    }
                    else -> throw IOException("更新服务响应异常（HTTP $status）")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("更新服务跳转次数过多")
    }

    private fun parseVersion(value: String): List<Int> {
        val parts = value.split('.')
        require(parts.size == 3 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) && it.toIntOrNull() != null }) {
            "版本格式无效"
        }
        return parts.map(String::toInt)
    }

    companion object {
        const val REPOSITORY = "Steeefanie/personal-reader"
        private const val RELEASES = "https://github.com/$REPOSITORY/releases"
        private val APK_CHECKSUM = Regex("""([a-fA-F0-9]{64})\s+\*?(personal-reader-([0-9]+\.[0-9]+\.[0-9]+)-arm64\.apk)""")
        private val DOWNLOAD_HOSTS = setOf(
            "github.com", "release-assets.githubusercontent.com",
            "objects.githubusercontent.com", "github-releases.githubusercontent.com"
        )
    }
}
