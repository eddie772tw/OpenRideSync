package org.openridesync.app

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.os.Bundle
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openridesync.app.auth.AuthSession
import org.openridesync.app.auth.AuthStatus
import org.openridesync.app.ble.BleKeyStore
import org.openridesync.app.runtime.RideStage
import org.openridesync.app.runtime.VehicleOperationGate
import org.openridesync.honda.PairedVehicleStore
import org.openridesync.honda.VehicleStore
import org.openridesync.honda.wire.HondaWire.Model
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/** Explicit ADB-only migration, excluded from the application APK. Never starts a vehicle connection. */
@RunWith(AndroidJUnit4::class)
class OemVehicleMigrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as OpenRideSyncApplication
    private val requestFile get() = File(app.cacheDir, "ors-vehicle-request.json")
    private val sealedFile get() = File(app.cacheDir, "ors-vehicle-sealed.bin")
    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun report(status: String) = instrumentation.sendStatus(0, Bundle().apply { putString("migration", status) })
    private fun account(): String {
        val session = AuthSession.get()
        check(session.state.value.status == AuthStatus.SIGNED_IN)
        return hash(checkNotNull(session.currentUser()).uid.toByteArray(Charsets.UTF_8))
    }

    @Test fun authorizedMigration() {
        val action = InstrumentationRegistry.getArguments().getString("migrationAction")
        assumeTrue("Requires an explicit migrationAction; never included in routine device tests", action != null)
        try {
            when (action) {
                "prepare" -> prepare()
                "import" -> importVehicle()
                "cleanup" -> cleanup()
                else -> error("ACTION")
            }
        } catch (failure: Throwable) {
            // Do not attach a cause, SDK message, assertion value, MAC or key to a runner failure.
            throw AssertionError("MIGRATION_FAILED_${failure.javaClass.simpleName}")
        }
    }

    private fun prepare() {
        check(!requestFile.exists() && !sealedFile.exists() && !keystore().containsAlias(ALIAS))
        val uidHash = account()
        check(app.ride.state.value.stage == RideStage.IDLE)
        val pair = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP).build())
        }.generateKeyPair()
        val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
        requestFile.writeText(JSONObject().put("version", 1).put("uidHash", uidHash).put("nonce", nonce)
            .put("createdElapsed", SystemClock.elapsedRealtime())
            .put("publicKey", Base64.encodeToString(pair.public.encoded, B64)).toString())
        report("PREPARED")
    }

    private fun cleanup() {
        check(requestFile.delete() || !requestFile.exists())
        check(sealedFile.delete() || !sealedFile.exists())
        keystore().deleteEntry(ALIAS)
        report("TEMPORARY_MATERIAL_REMOVED")
    }

    private fun decrypt(request: ByteArray, sealed: ByteArray, key: PrivateKey): ByteArray {
        require(request.size in 1..4096 && sealed.size in 289..16384)
        val input = DataInputStream(ByteArrayInputStream(sealed))
        require(input.readInt() == 256)
        val wrapped = ByteArray(256).also(input::readFully)
        val iv = ByteArray(12).also(input::readFully)
        val ciphertext = ByteArray(input.available()).also(input::readFully)
        val rsa = Cipher.getInstance("RSA/ECB/OAEPPadding")
        rsa.init(Cipher.DECRYPT_MODE, key, OAEP)
        val transport = rsa.doFinal(wrapped)
        try {
            require(transport.size == 32)
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(transport, "AES"), GCMParameterSpec(128, iv))
                updateAAD(request); doFinal(ciphertext)
            }
        } finally { transport.fill(0) }
    }

    private fun importVehicle() {
        val session = AuthSession.get()
        val uidHash = account()
        val revision = session.revision
        check(requestFile.length() in 1..4096 && sealedFile.length() in 289..16384)
        val request = requestFile.readBytes()
        val pending = JSONObject(String(request, Charsets.UTF_8))
        check(pending.getInt("version") == 1 && pending.getString("uidHash") == uidHash)
        check(SystemClock.elapsedRealtime() - pending.getLong("createdElapsed") in 0..900_000)
        val store = keystore()
        check(Base64.encodeToString(store.getCertificate(ALIAS).publicKey.encoded, B64) == pending.getString("publicKey"))
        val plaintext = decrypt(request, sealedFile.readBytes(), store.getKey(ALIAS, null) as PrivateKey)
        var key = ByteArray(0)
        try {
            val input = DataInputStream(ByteArrayInputStream(plaintext))
            check(input.readInt() == 0x4f52564d && input.readInt() == 1)
            check(input.readUTF() == uidHash && input.readUTF() == pending.getString("nonce"))
            val mac = input.readUTF()
            val name = input.readUTF(); val series = input.readUTF(); val firmware = input.readUTF()
            val vehicleName = input.readUTF(); val modelId = input.readUTF()
            key = ByteArray(16).also(input::readFully)
            check(input.available() == 0 && mac.matches(Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")))
            for ((value, limit) in listOf(name to 1024, series to 512, firmware to 256, vehicleName to 1024, modelId to 512))
                check(value.length <= limit && value.none(Char::isISOControl))
            check(series.isNotBlank() && modelId.isNotBlank())
            val model = findCachedModel(uidHash, modelId)
            check(model.meter.generation in 1..2)
            val device = app.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(mac)
            check(device.bondState == BluetoothDevice.BOND_BONDED)
            val garage = PairedVehicleStore(File(app.filesDir, "paired-vehicles/$uidHash"))
            val before = garage.read()
            // This bounded first-vehicle transfer never replaces a user's existing garage record.
            check(before.isEmpty())
            val keys = BleKeyStore(app)
            val oldKey = keys.read(mac)
            try { check(oldKey == null || MessageDigest.isEqual(oldKey, key)) }
            finally { oldKey?.fill(0) }
            keys.prepareForWrite(mac)
            report("ACCOUNT_MODEL_BOND_AND_EMPTY_GARAGE_VERIFIED")
            // Serialize the short commit on Main: account changes and UI radio actions cannot interleave.
            var commitFailure: Throwable? = null
            instrumentation.runOnMainSync {
                try {
                    check(account() == uidHash && session.revision == revision && app.ride.state.value.stage == RideStage.IDLE)
                    val ticket = checkNotNull(app.vehicleOperations.tryAcquire(VehicleOperationGate.Kind.PAIRING))
                    try {
                        check(garage.read().isEmpty() && device.bondState == BluetoothDevice.BOND_BONDED)
                        keys.write(mac, key)
                        garage.upsert(mac, name, series, firmware, vehicleName, model, 0, PairedVehicleStore.State.PAIRED)
                    } finally { app.vehicleOperations.release(ticket) }
                } catch (failure: Throwable) { commitFailure = failure }
            }
            commitFailure?.let { throw it }
            val saved = garage.read().single()
            check(saved.mac == mac && saved.model == model && saved.inFocus && saved.year == 0 && saved.state == PairedVehicleStore.State.PAIRED)
            val savedKey = checkNotNull(keys.read(mac))
            try { check(MessageDigest.isEqual(key, savedKey)) } finally { savedKey.fill(0) }
            check(app.ride.state.value.stage == RideStage.IDLE)
            report("IMPORTED_ONE_PAIRED_VEHICLE_NO_CONNECTION")
        } finally { key.fill(0); plaintext.fill(0) }
        cleanup()
    }

    private fun findCachedModel(uidHash: String, modelId: String): Model {
        val cache = VehicleStore(File(app.filesDir, "vehicles/$uidHash"))
        val models = mutableListOf<Model>()
        cache.readSelection()?.model?.let(models::add)
        for (own in listOf(true, false)) {
            val catalog = cache.readCatalog(own) ?: continue
            for (entry in catalog.value.modelCatalogsList) {
                models += entry.model
                for (seriesOwn in listOf(true, false)) {
                    val series = cache.readSeries(entry.model.modelSeriesLabel, seriesOwn, catalog.countryCode) ?: continue
                    series.value.modelSeriesListList.forEach { models += it.modelsList }
                }
            }
        }
        return models.filter { it.modelLabel == modelId }.distinct().single()
    }

    @Test fun sealedEnvelopeRejectsTamperingAndAnotherRequest() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val request = "isolated synthetic request".toByteArray()
        val transport = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val aes = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(transport, "AES")); updateAAD(request)
        }
        val ciphertext = aes.doFinal(byteArrayOf(1, 2, 3))
        val wrapped = Cipher.getInstance("RSA/ECB/OAEPPadding").run { init(Cipher.ENCRYPT_MODE, pair.public, OAEP); doFinal(transport) }
        val bytes = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use { it.writeInt(wrapped.size); it.write(wrapped); it.write(aes.iv); it.write(ciphertext) }
        val sealed = bytes.toByteArray()
        check(decrypt(request, sealed, pair.private).contentEquals(byteArrayOf(1, 2, 3)))
        for ((aad, envelope) in listOf(request + 0.toByte() to sealed,
            request to sealed.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })) {
            var rejected = false
            try { decrypt(aad, envelope, pair.private) } catch (_: AEADBadTagException) { rejected = true }
            check(rejected)
        }
        transport.fill(0)
    }

    private companion object {
        const val ALIAS = "org.openridesync.migration.temporary.v1"
        const val B64 = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        val OAEP = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
    }
}
