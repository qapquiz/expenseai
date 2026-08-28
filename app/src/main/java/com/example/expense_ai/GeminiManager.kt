package com.example.expense_ai

import android.graphics.Bitmap
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.QuotaExceededException
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GeminiManager {
    val isConfigured: Boolean
        get() = BuildConfig.GEMINI_API_KEY.isNotBlank()

    private val generativeModel = GenerativeModel(
        modelName = "gemma-4-31b-it", // Updated to the latest Gemini 3 model for August 2026
        apiKey = BuildConfig.GEMINI_API_KEY
    )

    private val jsonGenerativeModel = GenerativeModel(
        modelName = "gemma-4-31b-it",
        apiKey = BuildConfig.GEMINI_API_KEY,
        systemInstruction = content {
            text("""
                You are a strict JSON extraction assistant. 
                Your output must always be of type: application/json.
                Extract receipt data into a single JSON object with these keys: date, amount, merchantName, category, bankName, type.
                Output ONLY raw JSON. Do not include any reasoning, commentary, or markdown formatting outside the JSON object.
            """.trimIndent())
        },
        generationConfig = com.google.ai.client.generativeai.type.generationConfig {
            responseMimeType = "application/json"
        }
    )

    suspend fun extractExpenseData(bitmap: Bitmap): String? {
        if (!isConfigured) return null
        return withContext(Dispatchers.IO) {
            val prompt = """
                Extract the following information from this bank receipt image:
                - Date (ISO format YYYY-MM-DD). If the year is in Thai Buddhist Era (e.g., 67, 68, 69), convert to AD (2024, 2025, 2026).
                - Amount (Number only)
                - Merchant Name (The recipient name)
                - Category (e.g., Food, Transport, Shopping, Bills, Travel)
                - Bank Name (e.g., K-Plus, SCB, PromptPay)
                - Type: Identify if this is "EXPENSE" or "INCOME" or "TRANSFER".
                    * CRITICAL: Most Thai bank slips are EXPENSES.
                    * If the action is "โอนเงินสำเร็จ" (Transfer), "เติมเงิน" (Top-up), or "ชำระเงิน" (Payment), it is an EXPENSE.
                    * If the recipient name (receiver) is "อริยะ" or "Ariya", it is an "EXPENSE" (categorize as "TRANSFER").
                    * Only use "INCOME" if the slip explicitly says "รับเงิน" (Received money).
                
                Return ONLY a JSON object with these keys: date, amount, merchantName, category, bankName, type.
            """.trimIndent()

            try {
                val inputContent = content {
                    image(bitmap)
                    text(prompt)
                }
                val response = jsonGenerativeModel.generateContent(inputContent)
                val rawText = response.text
                
                // Fallback: If the model still returns commentary, try to extract only the JSON part
                if (rawText != null && !rawText.trim().startsWith("{")) {
                    val firstBrace = rawText.indexOf("{")
                    val lastBrace = rawText.lastIndexOf("}")
                    if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
                        rawText.substring(firstBrace, lastBrace + 1)
                    } else {
                        rawText
                    }
                } else {
                    rawText
                }
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
