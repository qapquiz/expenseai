package dev.nullphase.expense_ai.scanner

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.nullphase.expense_ai.GeminiManager
import dev.nullphase.expense_ai.data.ExpenseDatabase

class ScanWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val database = ExpenseDatabase.getDatabase(applicationContext)
        val expenseDao = database.expenseDao()
        val geminiManager = GeminiManager()
        val scanner = ReceiptScanner(applicationContext, geminiManager, expenseDao)

        return try {
            scanner.scanAndProcess { status ->
                setProgress(androidx.work.workDataOf("status" to status))
            }
            Result.success()
        } catch (e: Exception) {
            Log.e("ScanWorker", "Scan failed on attempt $runAttemptCount", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
