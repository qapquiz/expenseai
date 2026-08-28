package dev.nullphase.expense_ai.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExpenseParserTest {

    @Test
    fun parse_plainJson_populatesAllFields() {
        val input = """
            {
                "date": "2026-08-26",
                "amount": 150.50,
                "merchantName": "7-Eleven",
                "category": "Food",
                "bankName": "K-Plus",
                "type": "EXPENSE"
            }
        """.trimIndent()
        val result = ExpenseParser.parse(input)
        assertEquals("2026-08-26", result?.date)
        assertEquals(150.50, result?.amount ?: 0.0, 0.001)
        assertEquals("7-Eleven", result?.merchantName)
        assertEquals("Food", result?.category)
        assertEquals("K-Plus", result?.bankName)
        assertEquals("EXPENSE", result?.type)
    }

    @Test
    fun parse_fencedJson_returnsNull() {
        val input = """
            ```json
            {
                "date": "2026-08-26",
                "amount": 150.50,
                "merchantName": "7-Eleven",
                "category": "Food",
                "bankName": "K-Plus",
                "type": "EXPENSE"
            }
            ```
        """.trimIndent()
        // JSON-mode responses are raw; fences indicate a non-compliant response and are treated as parse failure.
        val result = ExpenseParser.parse(input)
        assertNull(result)
    }

    @Test
    fun parse_missingType_defaultsToExpense() {
        val input = """
            {
                "date": "2026-08-26",
                "amount": 100.0,
                "merchantName": "Shop",
                "category": "Shopping",
                "bankName": "SCB"
            }
        """.trimIndent()
        val result = ExpenseParser.parse(input)
        assertEquals("EXPENSE", result?.type)
    }

    @Test
    fun parse_nonNumericAmount_defaultsToZero() {
        val input = """
            {
                "date": "2026-08-26",
                "amount": "abc",
                "merchantName": "Shop",
                "category": "Shopping",
                "bankName": "SCB",
                "type": "EXPENSE"
            }
        """.trimIndent()
        val result = ExpenseParser.parse(input)
        assertEquals(0.0, result?.amount ?: 0.0, 0.001)
    }

    @Test
    fun parse_invalidJson_returnsNull() {
        val input = "not json"
        val result = ExpenseParser.parse(input)
        assertNull(result)
    }

    @Test
    fun parse_emptyString_returnsNull() {
        val input = ""
        val result = ExpenseParser.parse(input)
        assertNull(result)
    }

    @Test
    fun parse_bareFence_returnsNull() {
        // Characterization: markdown fences are no longer supported in raw JSON mode
        val input = """
            ```
            { "date": "2026-08-26", "amount": 100 }
            ```
        """.trimIndent()
        val result = ExpenseParser.parse(input)
        assertNull(result)
    }

    @Test
    fun parse_unknownKeys_ignoresThem() {
        val input = """
            {
                "date": "2026-08-26",
                "amount": 100.0,
                "merchantName": "Shop",
                "category": "Shopping",
                "bankName": "SCB",
                "type": "EXPENSE",
                "currency": "THB"
            }
        """.trimIndent()
        val result = ExpenseParser.parse(input)
        assertEquals("2026-08-26", result?.date)
        assertEquals(100.0, result?.amount ?: 0.0, 0.001)
    }
}
