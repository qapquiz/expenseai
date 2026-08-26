package com.example.expense_ai

import android.graphics.Bitmap
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.QuotaExceededException
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GeminiManager {
    private val generativeModel = GenerativeModel(
        modelName = "gemini-3-flash-preview", // Updated to the latest Gemini 3 model for August 2026
        apiKey = BuildConfig.GEMINI_API_KEY
    )

    private val jsonGenerativeModel = GenerativeModel(
        modelName = "gemini-3-flash-preview",
        apiKey = BuildConfig.GEMINI_API_KEY,
        generationConfig = com.google.ai.client.generativeai.type.generationConfig {
            responseMimeType = "application/json"
        }
    )

    suspend fun extractExpenseData(bitmap: Bitmap): String? {
        if (BuildConfig.GEMINI_API_KEY.isBlank()) return "Gemini API key is not configured. Add GEMINI_API_KEY=<key> to local.properties (debug builds only)."
        return withContext(Dispatchers.IO) {
            val prompt = """
                Extract the following information from this bank receipt image:
                - Date (ISO format YYYY-MM-DD)
                - Amount (Number only)
                - Merchant Name
                - Category (e.g., Food, Transport, Shopping, Bills)
                - Bank Name (e.g., K-Plus, SCB, PromptPay)
                - Type (Identify if this is an "EXPENSE" or "INCOME" based on whether money was sent or received)
                
                Return ONLY a JSON object with these keys: date, amount, merchantName, category, bankName, type.
            """.trimIndent()

            try {
                val inputContent = content {
                    image(bitmap)
                    text(prompt)
                }
                val response = jsonGenerativeModel.generateContent(inputContent)
                response.text
            } catch (e: QuotaExceededException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("GeminiManager", "extractExpenseData failed", e)
                null
            }
        }
    }

    suspend fun generateResponse(prompt: String): String? {
        if (BuildConfig.GEMINI_API_KEY.isBlank()) return "Gemini API key is not configured. Add GEMINI_API_KEY=<key> to local.properties (debug builds only)."
        return withContext(Dispatchers.IO) {
            try {
                val response = generativeModel.generateContent(prompt)
                response.text
            } catch (e: Exception) {
                android.util.Log.e("GeminiManager", "generateResponse failed", e)
                val errorMessage = e.localizedMessage ?: ""
                when {
                    errorMessage.contains("429") || errorMessage.contains("quota") ->
                        "Rate limit reached. Please wait a minute before trying again."
                    errorMessage.contains("401") || errorMessage.contains("API_KEY_INVALID") ->
                        "Invalid API Key. Please check your local.properties."
                    else -> "Error: $errorMessage"
                }
            }
        }
    }
}
