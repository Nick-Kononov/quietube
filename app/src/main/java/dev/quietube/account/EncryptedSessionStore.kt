package dev.quietube.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Contains status only, never cookies. Revision invalidates pending account responses. */
enum class SessionMethod { Cookies, DeviceCode }
data class SessionStatus(val ready: Boolean = false, val signedIn: Boolean = false, val revision: Long = 0, val method: SessionMethod? = null)
@Singleton
class EncryptedSessionStore @Inject constructor(@ApplicationContext context: Context) {
    private val alias = context.packageName + ".youtube.session.v1"
    private val file = AtomicFile(File(context.noBackupFilesDir, "youtube-session.bin"))
    private val mutex = Mutex()
    private var credentials: SessionCookies? = null
    private var deviceSession: dev.quietube.account.oauth.DeviceSession? = null
    private val mutableStatus = MutableStateFlow(SessionStatus())
    val status = mutableStatus.asStateFlow()
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key(): SecretKey {
        (keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    suspend fun initialize() = withContext(Dispatchers.IO) { mutex.withLock {
        if (mutableStatus.value.ready) return@withLock
        credentials = try {
            if (!file.baseFile.exists()) null else {
                val bytes = file.readFully()
                require(bytes.size in 29..600_000 && bytes[0] == 1.toByte())
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
                cipher.updateAAD(AAD)
                val plain = cipher.doFinal(bytes.copyOfRange(13,bytes.size))
                val text = try { plain.toString(Charsets.UTF_8) } finally { plain.fill(0) }
                if (text.startsWith("{\"oauth\":")) { deviceSession = dev.quietube.account.oauth.DeviceSession.decode(text); null }
                else SessionCookies.decoded(text)
            }
        } catch (_: Exception) { file.delete(); keyStore().deleteEntry(alias); null }
        mutableStatus.value = SessionStatus(true,credentials != null || deviceSession != null,mutableStatus.value.revision+1,
            if (deviceSession != null) SessionMethod.DeviceCode else if (credentials != null) SessionMethod.Cookies else null)
    } }
    suspend fun read(): SessionCookies? { initialize(); return mutex.withLock { credentials } }
    class Snapshot(val cookies: SessionCookies, val revision: Long) { override fun toString() = "SessionSnapshot([redacted])" }
    suspend fun snapshot(): Snapshot? { initialize(); return mutex.withLock { credentials?.let { Snapshot(it,mutableStatus.value.revision) } } }
    private fun writeLocked(cookies: SessionCookies) { writeTextLocked(cookies.encoded()); credentials = cookies; deviceSession = null }
    private fun writeTextLocked(text: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key()); cipher.updateAAD(AAD)
        val plain = text.toByteArray(Charsets.UTF_8)
        val bytes = try { byteArrayOf(1) + cipher.iv + cipher.doFinal(plain) } finally { plain.fill(0) }
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) } catch (e: Exception) { file.failWrite(output); throw e }
    }
    suspend fun save(cookies: SessionCookies) = withContext(Dispatchers.IO) { mutex.withLock {
        writeLocked(cookies)
        mutableStatus.value = SessionStatus(true,true,mutableStatus.value.revision + 1,SessionMethod.Cookies)
    } }
    suspend fun saveIfRevision(cookies: SessionCookies, expectedRevision: Long): Boolean = withContext(Dispatchers.IO) { mutex.withLock {
        if (mutableStatus.value.revision != expectedRevision) return@withLock false
        writeLocked(cookies)
        mutableStatus.value = SessionStatus(true,true,mutableStatus.value.revision + 1,SessionMethod.Cookies)
        true
    } }
    class DeviceSnapshot(val session: dev.quietube.account.oauth.DeviceSession,val revision: Long) { override fun toString() = "DeviceSnapshot([redacted])" }
    suspend fun deviceSnapshot(): DeviceSnapshot? { initialize(); return mutex.withLock { deviceSession?.let { DeviceSnapshot(it,mutableStatus.value.revision) } } }
    suspend fun saveDeviceIfRevision(session: dev.quietube.account.oauth.DeviceSession,expectedRevision: Long): Boolean = withContext(Dispatchers.IO) { mutex.withLock {
        if (mutableStatus.value.revision != expectedRevision) return@withLock false
        writeTextLocked(session.encoded()); credentials = null; deviceSession = session
        mutableStatus.value = SessionStatus(true,true,mutableStatus.value.revision+1,SessionMethod.DeviceCode); true
    } }
    suspend fun refreshDeviceIfRevision(session: dev.quietube.account.oauth.DeviceSession,expectedRevision: Long): Boolean = withContext(Dispatchers.IO) { mutex.withLock {
        if (mutableStatus.value.revision != expectedRevision || deviceSession == null) return@withLock false
        writeTextLocked(session.encoded()); deviceSession = session; true
    } }
    /** Rotation preserves the account revision so feeds and continuation tokens stay valid. */
    suspend fun applyResponseCookies(expectedRevision: Long, headers: List<String>): SessionCookies? = withContext(Dispatchers.IO) { mutex.withLock {
        if (mutableStatus.value.revision != expectedRevision) return@withLock null
        val current = credentials ?: return@withLock null
        val updated = current.updated(headers)
        if (!updated.usable()) {
            credentials = null; file.delete(); keyStore().deleteEntry(alias)
            mutableStatus.value = SessionStatus(true,false,mutableStatus.value.revision + 1)
            return@withLock null
        }
        if (!current.sameContents(updated)) writeLocked(updated)
        updated
    } }
    suspend fun clear() = withContext(Dispatchers.IO) { mutex.withLock {
        credentials = null; deviceSession = null
        file.delete()
        keyStore().deleteEntry(alias)
        mutableStatus.value = SessionStatus(true, false, mutableStatus.value.revision + 1)
    } }
    companion object {
        private val AAD = "dev.quietube.youtube.session.v1".toByteArray(Charsets.UTF_8)
    }
}
