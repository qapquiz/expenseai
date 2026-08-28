package com.example.expense_ai.scanner

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.util.Log
import com.example.expense_ai.GeminiManager
import com.example.expense_ai.data.Expense
import com.example.expense_ai.data.ExpenseDao
import com.example.expense_ai.data.FailedReceipt
import com.google.ai.client.generativeai.type.QuotaExceededException

class ReceiptScanner(
    private val context: Context,
    private val geminiManager: GeminiManager,
    private val expenseDao: ExpenseDao
) {
    private val bankFolders = listOf(
        "K PLUS", "SCB EASY", "Krungthai NEXT", "Bangkok Bank", "ttb touch"
    )

    suspend fun scanAndProcess(onProgress: (suspend (String) -> Unit)? = null) {
        if (!geminiManager.isConfigured) {
            Log.e("ReceiptScanner", "Gemini API key is not configured — scan aborted")
            onProgress?.invoke("Gemini API key is not configured — scan aborted")
            return
        }
        onProgress?.invoke("Searching for bank receipts...")
        val projection = mutableListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATA
        ).apply {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                add(MediaStore.Images.Media.VOLUME_NAME)
                add(MediaStore.Images.Media.IS_PENDING)
            }
        }.toTypedArray()

        val selection = "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} IN (${bankFolders.joinToString { "'$it'" }})"
        Log.d("ReceiptScanner", "Starting scan for buckets: $bankFolders")
        
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val bucketColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            
            val volumeColumn = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Images.Media.VOLUME_NAME)
            } else -1
            
            val pendingColumn = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Images.Media.IS_PENDING)
            } else -1
            
            val failed = expenseDao.getFailedImageIds().toSet()
            Log.d("ReceiptScanner", "Found ${cursor.count} total images in bank buckets. Failed count: ${failed.size}")

            val imagesToProcess = mutableListOf<Triple<Long, String, String>>()
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val imageId = id.toString()
                val bucket = cursor.getString(bucketColumn)
                
                val isPending = if (pendingColumn != -1) cursor.getInt(pendingColumn) else 0
                
                if (imageId in failed || isPending == 1) continue
                
                if (!expenseDao.isFileProcessed(imageId)) {
                    imagesToProcess.add(Triple(id, imageId, bucket))
                }
            }
            
            if (imagesToProcess.isEmpty()) {
                onProgress?.invoke("No new bank receipts found.")
                return@use
            }

            Log.d("ReceiptScanner", "Total new images to process: ${imagesToProcess.size}")

            imagesToProcess.forEachIndexed { index, (id, imageId, bucket) ->
                Log.d("ReceiptScanner", "Processing image $index/${imagesToProcess.size}: $imageId")
                onProgress?.invoke("Processing ${index + 1} of ${imagesToProcess.size} receipts...")

                cursor.moveToPosition(-1) // Reset for query details if needed, but we have enough
                // We need volume and path which are position dependent, let's find the row again
                cursor.moveToPosition(-1)
                var volume: String? = null
                var path: String? = null
                
                // Re-find the row in cursor to get volume/path (simpler than storing all in Triple)
                cursor.moveToFirst()
                do {
                    if (cursor.getLong(idColumn) == id) {
                        volume = if (volumeColumn != -1) cursor.getString(volumeColumn) else null
                        path = cursor.getString(dataColumn)
                        break
                    }
                } while (cursor.moveToNext())
                
                val contentUri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && volume != null) {
                    MediaStore.Images.Media.getContentUri(volume)
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                val uri = ContentUris.withAppendedId(contentUri, id)

                var retryCount = 0
                while (true) {
                    try {
                        processReceipt(uri, imageId, volume, path)
                        // Delay to stay within free tier 15 RPM limit (approx 4 seconds per request)
                        kotlinx.coroutines.delay(4000)
                        break
                    } catch (e: QuotaExceededException) {
                        retryCount++
                        if (retryCount < 3) {
                            val waitTime = 10000L * retryCount
                            Log.w("ReceiptScanner", "Quota exceeded for $imageId. Retry $retryCount/3 in ${waitTime/1000}s...")
                            onProgress?.invoke("Rate limit reached. Retrying in ${waitTime/1000}s...")
                            kotlinx.coroutines.delay(waitTime)
                        } else {
                            Log.e("ReceiptScanner", "Quota exceeded for $imageId after maximum retries. Aborting scan to retry later.")
                            throw e
                        }
                    }
                }
            }
        }
    }

    private suspend fun processReceipt(uri: android.net.Uri, imageId: String, volume: String?, path: String?) {
        val bitmap = decodeSampledBitmap(uri, volume, path) ?: run {
            markFailed(imageId)
            return
        }

        val jsonResponse = try {
            geminiManager.extractExpenseData(bitmap)
        } catch (e: QuotaExceededException) {
            throw e
        }

        Log.d("ReceiptScanner", "Gemini response for $imageId: $jsonResponse")
        if (jsonResponse == null) {
            Log.e("ReceiptScanner", "Gemini returned no text for $imageId")
            markFailed(imageId)
            return
        }

        val parsed = ExpenseParser.parse(jsonResponse)
        if (parsed == null) {
            Log.e("ReceiptScanner", "Failed to parse receipt JSON for $imageId")
            markFailed(imageId)
            return
        }
        
        try {
            val expense = Expense(
                date = parsed.date,
                amount = parsed.amount,
                merchantName = parsed.merchantName,
                category = parsed.category,
                bankName = parsed.bankName,
                type = parsed.type,
                imageId = imageId
            )
            expenseDao.clearFailedReceipt(imageId)
            expenseDao.insertExpense(expense)
            Log.d("ReceiptScanner", "Successfully inserted expense for $imageId")
        } catch (e: Exception) {
            Log.e("ReceiptScanner", "Failed to insert expense for $imageId", e)
            markFailed(imageId)
        }
    }

    private suspend fun markFailed(imageId: String) {
        Log.d("ReceiptScanner", "Marking image as failed: $imageId")
        expenseDao.insertFailedReceipt(FailedReceipt(imageId = imageId, reason = "extraction_or_parse_failed"))
    }

    private fun decodeSampledBitmap(uri: android.net.Uri, volume: String?, path: String?, maxDimension: Int = 1536): Bitmap? {
        Log.d("ReceiptScanner", "Attempting to decode URI: $uri, Volume: $volume, Path: $path")
        
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val fd = pfd.fileDescriptor
                
                // Decode bounds
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFileDescriptor(fd, null, bounds)
                
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    Log.e("ReceiptScanner", "Failed to decode bounds for $uri (width=${bounds.outWidth}, height=${bounds.outHeight})")
                    return null
                }

                // Calculate sample size
                var sampleSize = 1
                while (bounds.outWidth / (sampleSize * 2) >= maxDimension / 2 ||
                    bounds.outHeight / (sampleSize * 2) >= maxDimension / 2
                ) {
                    sampleSize *= 2
                }

                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                
                // Reset file descriptor position before actual decode
                try {
                    android.system.Os.lseek(fd, 0, android.system.OsConstants.SEEK_SET)
                } catch (e: Exception) {
                    Log.w("ReceiptScanner", "Failed to lseek for $uri, decode might fail if position advanced")
                }

                val bitmap = BitmapFactory.decodeFileDescriptor(fd, null, options)
                if (bitmap == null) {
                    Log.e("ReceiptScanner", "decodeFileDescriptor returned null for $uri")
                    path?.let { p ->
                        val file = java.io.File(p)
                        Log.d("ReceiptScanner", "Disk check: exists=${file.exists()}, canRead=${file.canRead()}, length=${file.length()}")
                    }
                }
                return bitmap
            } ?: run {
                Log.e("ReceiptScanner", "openFileDescriptor returned null for $uri")
                path?.let { p ->
                    val file = java.io.File(p)
                    Log.d("ReceiptScanner", "Disk check: exists=${file.exists()}, canRead=${file.canRead()}, length=${file.length()}")
                }
                return null
            }
        } catch (e: Exception) {
            Log.e("ReceiptScanner", "Exception decoding $uri: ${e.message}", e)
            path?.let { p ->
                val file = java.io.File(p)
                Log.d("ReceiptScanner", "Disk check on exception: exists=${file.exists()}, canRead=${file.canRead()}, length=${file.length()}")
            }
            return null
        }
    }
}
