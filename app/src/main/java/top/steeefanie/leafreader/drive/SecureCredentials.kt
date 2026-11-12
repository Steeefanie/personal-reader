package top.steeefanie.leafreader.drive

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface Credentials {
    fun read(connectionId: String): String?
    fun write(connectionId: String, token: String)
    fun clear(connectionId: String)
}

/** Only ciphertext goes to private preferences. Key material stays in Android Keystore. */
class SecureCredentials(context: Context) : Credentials {
    private val storage = context.getSharedPreferences("drive-credentials", Context.MODE_PRIVATE)
    private val alias = "personal-reader-device-key-v1"
    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun read(connectionId: String): String? {
        val stored = storage.getString(connectionId, null) ?: return null
        val parts = stored.split(':', limit = 2)
        require(parts.size == 2) { "设备凭证损坏，请重新绑定" }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = store.getKey(alias, null) as? SecretKey ?: error("设备密钥不存在，请重新绑定")
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            updateAAD(connectionId.toByteArray(Charsets.UTF_8))
            doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
        }
    }
    override fun write(connectionId: String, token: String) {
        require(token.isNotBlank() && token.none { it == '\r' || it == '\n' })
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key()); updateAAD(connectionId.toByteArray(Charsets.UTF_8))
        }
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val value = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(storage.edit().putString(connectionId, value).commit()) { "设备凭证保存失败，请重新绑定" }
    }
    override fun clear(connectionId: String) { check(storage.edit().remove(connectionId).commit()) { "清除凭证失败" } }
}
