package dev.quietube.account

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated directory/key alias; never reads, overwrites or clears the owner's session. */
@RunWith(AndroidJUnit4::class)
class EncryptedSessionStoreDeviceTest {
    private fun isolatedContext(name: String): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        return object : ContextWrapper(base) {
            override fun getPackageName(): String = base.packageName + ".sessiontests." + name
            override fun getNoBackupFilesDir(): File = File(base.cacheDir, "session-tests/$name").apply { mkdirs() }
        }
    }
    @Test fun encryptedSessionRestoresThenSignOutDeletesFileAndKey() = runBlocking {
        val context = isolatedContext("roundtrip")
        val store = EncryptedSessionStore(context)
        try {
            store.clear()
            val fake = SessionCookies.fromHeader("SAPISID=FAKE_PRIMARY_TEST_ONLY; LOGIN_INFO=FAKE_LOGIN_TEST_ONLY; SID=FAKE_SID_TEST_ONLY")
            store.save(fake)
            val file = File(context.noBackupFilesDir,"youtube-session.bin")
            assertTrue(file.exists())
            val ciphertext = file.readBytes()
            assertFalse(ciphertext.toString(Charsets.ISO_8859_1).contains("FAKE_"))
            assertEquals(1.toByte(),ciphertext[0])
            val restored = EncryptedSessionStore(context)
            restored.initialize()
            assertTrue(restored.status.value.signedIn)
            assertEquals(fake.header(),restored.read()!!.header())
            val revision = restored.status.value.revision
            restored.clear()
            assertFalse(file.exists()); assertNull(restored.read()); assertFalse(restored.status.value.signedIn)
            assertTrue(restored.status.value.revision > revision)
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            assertFalse(keys.containsAlias(context.packageName + ".youtube.session.v1"))
        } finally { store.clear(); context.noBackupFilesDir.deleteRecursively() }
    }
    @Test fun corruptedCiphertextFailsClosedWithoutCrashing() = runBlocking {
        val context = isolatedContext("corrupt")
        val store = EncryptedSessionStore(context)
        try {
            store.save(SessionCookies.fromHeader("SAPISID=FAKE_TEST_ONLY; LOGIN_INFO=FAKE_LOGIN_TEST_ONLY"))
            val file = File(context.noBackupFilesDir,"youtube-session.bin")
            val bytes = file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
            val restored = EncryptedSessionStore(context)
            restored.initialize()
            assertFalse(restored.status.value.signedIn); assertNull(restored.read()); assertFalse(file.exists())
        } finally { store.clear(); context.noBackupFilesDir.deleteRecursively() }
    }
    @Test fun rotationPersistsWithoutResettingAccountRevision() = runBlocking {
        val context = isolatedContext("rotation")
        val store = EncryptedSessionStore(context)
        try {
            store.save(SessionCookies.fromHeader("SAPISID=FAKE_PRIMARY_TEST_ONLY; LOGIN_INFO=FAKE_LOGIN_TEST_ONLY"))
            val snapshot = store.snapshot()!!
            val updated = store.applyResponseCookies(snapshot.revision,listOf("SAPISID=FAKE_ROTATED_TEST_ONLY; Path=/; Secure; Max-Age=3600"))!!
            assertEquals("FAKE_ROTATED_TEST_ONLY",updated.value("SAPISID"))
            assertEquals(snapshot.revision,store.status.value.revision)
            val restored = EncryptedSessionStore(context); restored.initialize()
            assertEquals("FAKE_ROTATED_TEST_ONLY",restored.read()!!.value("SAPISID"))
        } finally { store.clear(); context.noBackupFilesDir.deleteRecursively() }
    }
    @Test fun responseArrivingAfterSignOutCannotRestoreCredentials() = runBlocking {
        val context = isolatedContext("stale-response")
        val store = EncryptedSessionStore(context)
        try {
            val fake = SessionCookies.fromHeader("SAPISID=FAKE_TEST_ONLY; LOGIN_INFO=FAKE_LOGIN_TEST_ONLY")
            store.save(fake); val snapshot = store.snapshot()!!; store.clear()
            assertNull(store.applyResponseCookies(snapshot.revision,listOf("SAPISID=FAKE_ROTATED; Path=/")))
            assertFalse(store.saveIfRevision(fake,snapshot.revision))
            assertNull(store.read()); assertFalse(store.status.value.signedIn)
            assertFalse(File(context.noBackupFilesDir,"youtube-session.bin").exists())
        } finally { store.clear(); context.noBackupFilesDir.deleteRecursively() }
    }
    @Test fun serverLogoutDeletesStoredSession() = runBlocking {
        val context = isolatedContext("server-logout")
        val store = EncryptedSessionStore(context)
        try {
            store.save(SessionCookies.fromHeader("SAPISID=FAKE_TEST_ONLY; LOGIN_INFO=FAKE_LOGIN_TEST_ONLY"))
            val snapshot = store.snapshot()!!
            assertNull(store.applyResponseCookies(snapshot.revision,listOf("LOGIN_INFO=; Path=/; Max-Age=0")))
            assertFalse(store.status.value.signedIn); assertNull(store.read())
            assertFalse(File(context.noBackupFilesDir,"youtube-session.bin").exists())
        } finally { store.clear(); context.noBackupFilesDir.deleteRecursively() }
    }
    @Test fun deviceTokenRestoresAndRefreshPreservesRevision() = runBlocking {
        val context = isolatedContext("oauth")
        val store = EncryptedSessionStore(context)
        val config = dev.quietube.account.oauth.TvClientConfiguration("fixture.apps.googleusercontent.com","FAKE_CLIENT_SECRET","7.fixture","FAKE_VISITOR")
        try {
            store.initialize()
            val token = dev.quietube.account.oauth.DeviceSession("FAKE_ACCESS","FAKE_REFRESH",1000,config)
            assertTrue(store.saveDeviceIfRevision(token,store.status.value.revision))
            assertEquals(SessionMethod.DeviceCode,store.status.value.method)
            assertNull(store.read())
            val file = File(context.noBackupFilesDir,"youtube-session.bin")
            assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("FAKE_"))
            val restored = EncryptedSessionStore(context); restored.initialize()
            val snapshot = restored.deviceSnapshot()!!
            assertEquals("FAKE_REFRESH",snapshot.session.refreshToken)
            val refreshed = dev.quietube.account.oauth.DeviceSession("FAKE_NEW_ACCESS","FAKE_REFRESH",999999,config)
            assertTrue(restored.refreshDeviceIfRevision(refreshed,snapshot.revision))
            assertEquals(snapshot.revision,restored.status.value.revision)
            restored.clear()
            assertFalse(restored.refreshDeviceIfRevision(refreshed,snapshot.revision))
            assertFalse(restored.saveDeviceIfRevision(token,snapshot.revision))
            assertNull(restored.deviceSnapshot()); assertFalse(file.exists())
        } finally { store.clear(); context.noBackupFilesDir.deleteRecursively() }
    }
}
