package dev.nullphase.expense_ai.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses ORDER BY date DESC, timestamp DESC")
    fun getAllExpenses(): Flow<List<Expense>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: Expense)

    /** Targeted category correction from the manual picker (leaves type alone). */
    @Query("UPDATE expenses SET category = :category WHERE id = :id")
    suspend fun updateCategory(id: Long, category: String)

    /** Two-way EXPENSE<->TRANSFER correction; conversion clears the category
     *  because transfers carry no spending category. */
    @Query("UPDATE expenses SET type = :type, category = :category WHERE id = :id")
    suspend fun updateTypeAndCategory(id: Long, type: String, category: String)

    @Query("SELECT EXISTS(SELECT 1 FROM expenses WHERE imageId = :imageId)")
    suspend fun isFileProcessed(imageId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFailedReceipt(receipt: FailedReceipt)

    @Query("DELETE FROM failed_receipts WHERE imageId = :imageId")
    suspend fun clearFailedReceipt(imageId: String)

    @Query("SELECT imageId FROM failed_receipts")
    suspend fun getFailedImageIds(): List<String>
}
