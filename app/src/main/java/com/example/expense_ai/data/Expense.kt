package com.example.expense_ai.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "expenses")
data class Expense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val amount: Double,
    val merchantName: String,
    val category: String,
    val bankName: String,
    val type: String, // "INCOME" or "EXPENSE"
    val imageId: String,
    val timestamp: Long = System.currentTimeMillis()
)

@androidx.room.Entity(tableName = "failed_receipts", primaryKeys = ["imageId"])
data class FailedReceipt(
    val imageId: String,
    val reason: String,
    val failedAt: Long = System.currentTimeMillis()
)
