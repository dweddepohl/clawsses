package com.clawsses.phone.tts

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * ElevenLabs API client for text-to-speech synthesis.
 */
class ElevenLabsClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * Fetch available voices from ElevenLabs API.
     */
    suspend fun getVoices(apiKey: String): Result<List<Voice>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/voices")
                .header("xi-api-key", apiKey)
                .get()
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("Failed to fetch voices: ${response.code} ${response.message}")
                )
            }

            val body = response.body?.string()
                ?: return@withContext Result.failure(Exception("Empty response body"))

            val voicesResponse = gson.fromJson(body, VoicesResponse::class.java)
            Result.success(voicesResponse.voices)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Synthesize text to speech and return audio stream.
     * Returns MP3 audio data as InputStream.
     */
    suspend fun synthesize(
        apiKey: String,
        voiceId: String,
        text: String
    ): Result<InputStream> = withContext(Dispatchers.IO) {
        try {
            val requestBody = SynthesisRequest(
                text = text,
                modelId = MODEL_ID
            )

            val request = Request.Builder()
                .url("$BASE_URL/text-to-speech/$voiceId/stream")
                .header("xi-api-key", apiKey)
                .header("Content-Type", "application/json")
                .post(gson.toJson(requestBody).toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                return@withContext Result.failure(
                    Exception("TTS synthesis failed: ${response.code} - $errorBody")
                )
            }

            val inputStream = response.body?.byteStream()
                ?: return@withContext Result.failure(Exception("Empty response body"))

            Result.success(inputStream)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        private const val BASE_URL = "https://api.elevenlabs.io/v1"
        private const val MODEL_ID = "eleven_turbo_v2_5"
    }
}

// API response models

data class Voice(
    @SerializedName("voice_id") val voiceId: String,
    @SerializedName("name") val name: String,
    @SerializedName("preview_url") val previewUrl: String? = null,
    @SerializedName("category") val category: String? = null
)

data class VoicesResponse(
    @SerializedName("voices") val voices: List<Voice>
)

data class SynthesisRequest(
    @SerializedName("text") val text: String,
    @SerializedName("model_id") val modelId: String
)
