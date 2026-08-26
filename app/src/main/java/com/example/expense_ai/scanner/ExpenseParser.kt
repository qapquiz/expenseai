package com.example.expense_ai.scanner

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ExpenseDraft(
    val date: String = "",
    val amount: Double = 0.0,
    val merchantName: String = "",
    val category: String = "",
    val bankName: String = "",
    val type: String = "EXPENSE"
)

object ExpenseParser {
    /** Returns null when the input cannot be parsed into an Expense draft. */
    fun parse(responseText: String): ParsedExpense? {
        return try {
            var cleaned = responseText.trim()
            
            // Handle markdown code blocks if the model insists on using them
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.substringAfter("\n").substringBeforeLast("```").trim()
                // Some models put "json" after the first triple-backtick
                if (cleaned.startsWith("json", ignoreCase = true)) {
                    cleaned = cleaned.removePrefix("json").trim()
                }
            }

            val draft = Json { ignoreUnknownKeys = true }
                .decodeFromString(ExpenseDraft.serializer(), cleaned)
            ParsedExpense(
                date = draft.date,
                amount = draft.amount,
                merchantName = draft.merchantName,
                category = draft.category,
                bankName = draft.bankName,
                type = draft.type
            )
        } catch (e: Exception) {
            null
        }
    }
}

data class ParsedExpense(
    val date: String,
    val amount: Double,
    val merchantName: String,
    val category: String,
    val bankName: String,
    val type: String
)
