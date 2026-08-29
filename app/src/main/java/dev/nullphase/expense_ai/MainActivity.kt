package dev.nullphase.expense_ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import dev.nullphase.expense_ai.ui.theme.ExpenseaiTheme
import kotlinx.coroutines.launch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.nullphase.expense_ai.data.Expense
import dev.nullphase.expense_ai.data.ExpenseDatabase
import dev.nullphase.expense_ai.scanner.ReceiptScanner
import kotlinx.coroutines.delay

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.nullphase.expense_ai.scanner.ScanWorker
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ExpenseaiTheme {
                ExpenseTrackerApp(geminiManager = GeminiManager())
            }
        }
    }
}

@Composable
fun ExpenseTrackerApp(geminiManager: GeminiManager, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val database = remember { ExpenseDatabase.getDatabase(context) }
    val expenseDao = database.expenseDao()
    
    val expenses by expenseDao.getAllExpenses().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var selectedExpense by remember { mutableStateOf<Expense?>(null) }
    val workManager = remember { WorkManager.getInstance(context) }
    val scanWorkInfos by workManager.getWorkInfosForUniqueWorkFlow("receipt_scan")
        .collectAsState(initial = emptyList())
    val periodicWorkInfos by workManager.getWorkInfosForUniqueWorkFlow("periodic_receipt_scan")
        .collectAsState(initial = emptyList())
    
    val isScanning = scanWorkInfos.any { 
        it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED 
    } || periodicWorkInfos.any { it.state == WorkInfo.State.RUNNING }
    val scanningStatus = (scanWorkInfos + periodicWorkInfos)
        .find { it.state == WorkInfo.State.RUNNING }
        ?.progress
        ?.getString("status")

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Schedule periodic scan once permission is granted
            val periodicRequest = PeriodicWorkRequestBuilder<ScanWorker>(1, TimeUnit.HOURS).build()
            workManager.enqueueUniquePeriodicWork(
                "periodic_receipt_scan",
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest
            )
        }
    }

    LaunchedEffect(Unit) {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            // Already granted, schedule periodic scan
            val periodicRequest = PeriodicWorkRequestBuilder<ScanWorker>(1, TimeUnit.HOURS).build()
            workManager.enqueueUniquePeriodicWork(
                "periodic_receipt_scan",
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest
            )
        } else {
            permissionLauncher.launch(permission)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            )
            .padding(16.dp)
    ) {
        Button(
            onClick = {
                val scanRequest = OneTimeWorkRequestBuilder<ScanWorker>().build()
                workManager.enqueueUniqueWork(
                    "receipt_scan",
                    androidx.work.ExistingWorkPolicy.REPLACE,
                    scanRequest
                )
            },
            enabled = !isScanning,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Status lives inside the button (a fixed-size slot) so showing scan
            // progress never pushes the content below it around. The label is
            // throttled so rapid WorkManager progress updates don't flicker it.
            val buttonLabel = rememberThrottledText(
                scanningStatus
                    ?: if (isScanning) "Scanning in Background..." else "Scan Bank Receipts Now"
            )
            Text(
                text = buttonLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "Expenses", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = WindowInsets.navigationBars.asPaddingValues()
        ) {
            items(expenses) { expense ->
                ExpenseItem(expense, onClick = { selectedExpense = expense })
            }
        }

        selectedExpense?.let { selected ->
            CategoryPickerSheet(
                expense = selected,
                onDismiss = { selectedExpense = null },
                onSelectCategory = { category ->
                    scope.launch { expenseDao.updateCategory(selected.id, category) }
                    selectedExpense = null
                },
                onConvertType = { newType ->
                    scope.launch { expenseDao.updateTypeAndCategory(selected.id, newType, "") }
                    selectedExpense = null
                }
            )
        }
    }
}

/**
 * Throttles rapid text changes: at most one update per [intervalMs] (leading edge),
 * with a trailing delay so the latest value always lands. Updates arriving faster
 * than the window never reach the UI, preventing label flicker.
 */
@Composable
fun rememberThrottledText(source: String, intervalMs: Long = 500L): String {
    var display by remember { mutableStateOf(source) }
    var lastAppliedAt by remember { mutableLongStateOf(0L) }

    LaunchedEffect(source) {
        if (source == display) return@LaunchedEffect
        val elapsed = SystemClock.elapsedRealtime() - lastAppliedAt
        if (elapsed < intervalMs) delay(intervalMs - elapsed)
        lastAppliedAt = SystemClock.elapsedRealtime()
        display = source
    }
    return display
}

@Composable
fun ExpenseItem(expense: Expense, onClick: () -> Unit) {
    val amountColor = if (expense.type == "INCOME") {
        androidx.compose.ui.graphics.Color(0xFF4CAF50) // Green
    } else {
        androidx.compose.ui.graphics.Color(0xFFF44336) // Red
    }
    val amountPrefix = if (expense.type == "INCOME") "+" else "-"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(
                enabled = expense.type == "EXPENSE" || expense.type == "TRANSFER",
                onClick = onClick
            )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
                Text(text = expense.merchantName, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                Text(
                    text = "$amountPrefix ฿${expense.amount}",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    color = amountColor
                )
            }
            val subtitle = listOf(expense.bankName, expense.category, expense.type)
                .filter { it.isNotBlank() }
                .joinToString(" • ")
            Text(text = subtitle, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            Text(text = expense.date, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Bottom sheet for manual classification of one expense row.
 *
 * EXPENSE rows: 9 category chips (instant commit on tap) + convert-to-TRANSFER
 * action. TRANSFER rows: chips hidden, only convert-back-to-EXPENSE (category
 * is cleared on conversion — transfers carry no spending category). INCOME
 * rows never reach this sheet (cards are not clickable).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CategoryPickerSheet(
    expense: Expense,
    onDismiss: () -> Unit,
    onSelectCategory: (String) -> Unit,
    onConvertType: (String) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(text = expense.merchantName, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            if (expense.type == "EXPENSE") {
                Spacer(modifier = Modifier.height(12.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Categories.ALL.forEach { category ->
                        FilterChip(
                            selected = expense.category == category,
                            onClick = { onSelectCategory(category) },
                            label = { Text(category) }
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        onConvertType(if (expense.type == "EXPENSE") "TRANSFER" else "EXPENSE")
                    }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "⇄", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (expense.type == "EXPENSE") "This was a transfer between my own accounts"
                    else "This is actually an expense",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ExpenseTrackerPreview() {
    ExpenseaiTheme {
        ExpenseTrackerApp(GeminiManager())
    }
}