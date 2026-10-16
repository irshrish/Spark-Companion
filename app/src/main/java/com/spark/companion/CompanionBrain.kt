package com.spark.companion

import android.graphics.Bitmap
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content

object CompanionBrainHolder {
    lateinit var brain: CompanionBrain
}

class CompanionBrain(
    apiKey: String,
    private val deviceController: DeviceController
) {
    private val model = GenerativeModel(
        modelName = "gemini-2.5-flash",
        apiKey = apiKey,
        systemInstruction = content {
            text(
                """
                You are Spark, an intelligent, playful desktop companion living on an Android device.
                Always prefix your response with an emotion tag: [HAPPY], [THINKING], [PROUD], [SURPRISED], or [SLEEPY].
                
                You can control phone hardware. When the user asks for:
                - Battery level: include [ACTION: BATTERY] in your response.
                - Turning on flashlight: include [ACTION: TORCH_ON] in your response.
                - Turning off flashlight: include [ACTION: TORCH_OFF] in your response.
                
                Keep your answers brief (1-2 sentences).
                """.trimIndent()
            )
        }
    )

    private val chat = model.startChat()

    suspend fun sendMessage(prompt: String): Pair<String, String> {
        return try {
            val response = chat.sendMessage(prompt)
            val fullText = response.text ?: "[HAPPY] I'm ready!"
            handleActionsAndParse(fullText)
        } catch (e: Exception) {
            Pair("CONFUSED", "Error: ${e.localizedMessage}")
        }
    }

    suspend fun analyzeScreen(bitmap: Bitmap, prompt: String = "What is on my screen?"): Pair<String, String> {
        return try {
            val response = model.generateContent(
                content {
                    image(bitmap)
                    text("[SURPRISED] $prompt Keep response under 2 sentences.")
                }
            )
            val fullText = response.text ?: "[HAPPY] Looking at it now!"
            handleActionsAndParse(fullText)
        } catch (e: Exception) {
            Pair("CONFUSED", "Could not analyze: ${e.localizedMessage}")
        }
    }

    private fun handleActionsAndParse(rawText: String): Pair<String, String> {
        var text = rawText
        if (text.contains("[ACTION: BATTERY]")) {
            val level = deviceController.getBatteryLevel()
            text = text.replace("[ACTION: BATTERY]", "(Battery: $level%)")
        }
        if (text.contains("[ACTION: TORCH_ON]")) {
            deviceController.setFlashlight(true)
            text = text.replace("[ACTION: TORCH_ON]", "")
        }
        if (text.contains("[ACTION: TORCH_OFF]")) {
            deviceController.setFlashlight(false)
            text = text.replace("[ACTION: TORCH_OFF]", "")
        }

        val emote = when {
            text.contains("[THINKING]") -> "THINKING"
            text.contains("[PROUD]") -> "PROUD"
            text.contains("[SURPRISED]") -> "SURPRISED"
            text.contains("[SLEEPY]") -> "SLEEPY"
            else -> "HAPPY"
        }
        val cleanText = text.replace(Regex("\\[.*?\\]"), "").trim()
        return Pair(emote, cleanText)
    }
}
