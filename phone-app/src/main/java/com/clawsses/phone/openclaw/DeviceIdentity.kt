package com.clawsses.phone.openclaw

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

/**
 * Manages Ed25519 device identity for OpenClaw Gateway authentication.
 *
 * On first launch, generates an Ed25519 keypair in the Android Keystore
 * and derives a deviceId (SHA-256 fingerprint of the raw public key).
 * The keypair is hardware-backed and persisted by the Keystore across restarts.
 *
 * On each connect, signs the server's challenge nonce with the private key
 * to prove device identity.
 */
class DeviceIdentity(context: Context) {

    companion object {
        private const val TAG = "DeviceIdentity"
        private const val PREFS_NAME = "clawsses_device_identity"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEYSTORE_ALIAS = "clawsses_ed25519_device_key"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var publicKey: PublicKey
    private var privateKey: PrivateKey

    /** SHA-256 hex fingerprint of the raw public key bytes */
    val deviceId: String

    /** Raw 32-byte public key, base64url-encoded (no padding) */
    val publicKeyBase64Url: String

    /** Persisted device token from a successful pairing (null if not yet paired) */
    var deviceToken: String?
        get() = prefs.getString(KEY_DEVICE_TOKEN, null)
        set(value) {
            prefs.edit().putString(KEY_DEVICE_TOKEN, value).apply()
        }

    init {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
            // Generate new keypair in Android Keystore
            val kpg = KeyPairGenerator.getInstance("Ed25519", "AndroidKeyStore")
            kpg.initialize(
                KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setDigests(KeyProperties.DIGEST_NONE)
                    .build()
            )
            kpg.generateKeyPair()
            Log.i(TAG, "Generated new Ed25519 keypair in Android Keystore")
        } else {
            Log.d(TAG, "Restored existing Ed25519 keypair from Android Keystore")
        }

        val entry = keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.PrivateKeyEntry
        privateKey = entry.privateKey
        publicKey = entry.certificate.publicKey

        // Derive raw 32-byte public key (last 32 bytes of X.509 encoding)
        val rawPublicKey = publicKey.encoded.takeLast(32).toByteArray()

        // Device ID = SHA-256 hex fingerprint
        deviceId = MessageDigest.getInstance("SHA-256")
            .digest(rawPublicKey)
            .joinToString("") { "%02x".format(it) }

        // Base64url-encoded raw public key (no padding)
        publicKeyBase64Url = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(rawPublicKey)

        Log.d(TAG, "Device ID: ${deviceId.take(16)}...")
    }

    /**
     * Sign the challenge nonce with our Ed25519 private key.
     * Returns the signature as a base64url-encoded string (no padding).
     */
    fun signNonce(nonce: String): String {
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(privateKey)
        signer.update(nonce.toByteArray(Charsets.UTF_8))
        val sig = signer.sign()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sig)
    }
}
