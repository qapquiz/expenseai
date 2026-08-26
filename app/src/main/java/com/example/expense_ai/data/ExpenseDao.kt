package com.example.expense_ai.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses ORDER BY timestamp DESC")
    fun getAllExpenses(): Flow<List<Expense>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: Expense)

    @Query("SELECT EXISTS(SELECT 1 FROM expenses WHERE imageId = :imageId)")
    suspend fun isFileProcessed(imageId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFailedReceipt(receipt: FailedReceipt)

    @Query("DELETE FROM failed_receipts WHERE imageId = :imageId")
    suspend fun clearFailedReceipt(imageId: String)

    @Query("SELECT imageId FROM failed_receipts")
    suspend fun getFailedImageIds(): List<String>
}
