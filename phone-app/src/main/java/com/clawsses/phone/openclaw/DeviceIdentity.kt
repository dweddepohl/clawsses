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
        // Ed25519 SPKI prefix (same as OpenClaw gateway uses)
        private val ED25519_SPKI_PREFIX = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65,
            0x70, 0x03, 0x21, 0x00
        )
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

        // Delete any existing key that might be the wrong type (e.g., EC instead of Ed25519)
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            val existingEntry = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.PrivateKeyEntry
            val existingAlgo = existingEntry?.privateKey?.algorithm ?: "unknown"
            Log.d(TAG, "Existing key algorithm: $existingAlgo")
            if (existingAlgo != "Ed25519" && existingAlgo != "EdDSA") {
                Log.w(TAG, "Existing key is $existingAlgo, not Ed25519 — deleting and regenerating")
                keyStore.deleteEntry(KEYSTORE_ALIAS)
            }
        }

        if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
            // Generate new Ed25519 keypair in Android Keystore
            try {
                val kpg = KeyPairGenerator.getInstance("Ed25519", "AndroidKeyStore")
                kpg.initialize(
                    KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setDigests(KeyProperties.DIGEST_NONE)
                        .build()
                )
                kpg.generateKeyPair()
                Log.i(TAG, "Generated new Ed25519 keypair in Android Keystore")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to generate Ed25519 in AndroidKeyStore, trying EdDSA", e)
                // Some devices use "EdDSA" instead of "Ed25519"
                val kpg = KeyPairGenerator.getInstance("EdDSA", "AndroidKeyStore")
                kpg.initialize(
                    KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setDigests(KeyProperties.DIGEST_NONE)
                        .build()
                )
                kpg.generateKeyPair()
                Log.i(TAG, "Generated new EdDSA keypair in Android Keystore")
            }
        } else {
            Log.d(TAG, "Restored existing Ed25519 keypair from Android Keystore")
        }

        val entry = keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.PrivateKeyEntry
        privateKey = entry.privateKey
        publicKey = entry.certificate.publicKey

        Log.d(TAG, "Private key algorithm: ${privateKey.algorithm}")
        Log.d(TAG, "Public key algorithm: ${publicKey.algorithm}")
        Log.d(TAG, "Public key format: ${publicKey.format}")
        Log.d(TAG, "Public key encoded length: ${publicKey.encoded.size}")

        // Extract raw 32-byte Ed25519 public key from SPKI encoding
        val spki = publicKey.encoded
        val rawPublicKey = if (spki.size == ED25519_SPKI_PREFIX.size + 32 &&
            spki.take(ED25519_SPKI_PREFIX.size) == ED25519_SPKI_PREFIX.toList()) {
            // Standard Ed25519 SPKI: 12-byte prefix + 32-byte raw key
            spki.copyOfRange(ED25519_SPKI_PREFIX.size, spki.size)
        } else {
            // Fallback: take last 32 bytes
            Log.w(TAG, "Non-standard SPKI encoding (${spki.size} bytes), taking last 32")
            spki.takeLast(32).toByteArray()
        }

        // Device ID = SHA-256 hex fingerprint
        deviceId = MessageDigest.getInstance("SHA-256")
            .digest(rawPublicKey)
            .joinToString("") { "%02x".format(it) }

        // Base64url-encoded raw public key (no padding)
        publicKeyBase64Url = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(rawPublicKey)

        Log.d(TAG, "Device ID: ${deviceId.take(16)}...")
        Log.d(TAG, "Raw public key size: ${rawPublicKey.size}")
    }

    /**
     * Sign the challenge nonce with our Ed25519 private key.
     * Returns the signature as a base64url-encoded string (no padding).
     */
    fun signNonce(nonce: String): String {
        return sign(nonce.toByteArray(Charsets.UTF_8))
    }

    /**
     * Build and sign the device auth payload per OpenClaw protocol v2.
     * Payload format: "v2|deviceId|clientId|clientMode|role|scopes|signedAtMs|token|nonce"
     */
    fun signAuthPayload(
        clientId: String,
        clientMode: String,
        role: String,
        scopes: List<String>,
        signedAtMs: Long,
        token: String,
        nonce: String
    ): String {
        val payload = listOf(
            "v2",
            deviceId,
            clientId,
            clientMode,
            role,
            scopes.joinToString(","),
            signedAtMs.toString(),
            token,
            nonce
        ).joinToString("|")
        Log.d(TAG, "Auth payload to sign: $payload")
        val sig = sign(payload.toByteArray(Charsets.UTF_8))
        Log.d(TAG, "Signature (base64url): $sig (${Base64.getUrlDecoder().decode(sig + "==").size} bytes)")
        return sig
    }

    private fun sign(data: ByteArray): String {
        // Use the same provider as the key to ensure Ed25519 signing
        val algoName = if (privateKey.algorithm == "EdDSA") "EdDSA" else "Ed25519"
        val signer = Signature.getInstance(algoName, "AndroidKeyStore")
        signer.initSign(privateKey)
        signer.update(data)
        val sig = signer.sign()
        Log.d(TAG, "Raw signature size: ${sig.size} bytes (expected 64 for Ed25519)")
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sig)
    }
}
