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
import java.util.Calendar
import java.util.Locale
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

        val listItems = remember(expenses) { groupExpensesByDate(expenses) }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = WindowInsets.navigationBars.asPaddingValues()
        ) {
            listItems.forEachIndexed { index, listItem ->
                when (listItem) {
                    is ExpenseListItem.Header -> item(key = index) {
                        DateHeader(label = listItem.label, expenseTotal = listItem.expenseTotal)
                    }
                    is ExpenseListItem.Row -> item(key = index) {
                        ExpenseItem(listItem.expense, onClick = { selectedExpense = listItem.expense })
                    }
                }
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

/** Flat list model for the grouped LazyColumn: a header before each date group. */
private sealed interface ExpenseListItem {
    data class Header(val label: String, val expenseTotal: Double) : ExpenseListItem
    data class Row(val expense: Expense) : ExpenseListItem
}

private val SHORT_MONTHS = arrayOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
)

/** Groups the date-sorted expense list into [header, rows...] sequences.
 *  Input MUST be sorted by date DESC (guaranteed by the ExpenseDao query);
 *  groupBy preserves first-encounter key order. Blank dates sort last and
 *  render under "Unknown date". Header totals sum EXPENSE rows only:
 *  TRANSFER is net-zero self-movement, INCOME is not spending (plan 014 rules). */
private fun groupExpensesByDate(expenses: List<Expense>): List<ExpenseListItem> {
    val listItems = mutableListOf<ExpenseListItem>()
    for ((date, rows) in expenses.groupBy { it.date }) {
        val expenseTotal = rows.filter { it.type == "EXPENSE" }.sumOf { it.amount }
        listItems += ExpenseListItem.Header(headerLabel(date), expenseTotal)
        rows.forEach { listItems += ExpenseListItem.Row(it) }
    }
    return listItems
}

/** Today / Yesterday / "28 Aug 2026"; blank dates -> "Unknown date"; drifted
 *  or unparseable date strings render raw (honest-display rule, plan 014). */
private fun headerLabel(date: String): String {
    if (date.isBlank()) return "Unknown date"
    val parts = date.split("-")
    if (parts.size != 3) return date
    val year = parts[0].toIntOrNull() ?: return date
    val month = parts[1].toIntOrNull() ?: return date
    val day = parts[2].toIntOrNull() ?: return date
    if (month !in 1..12 || day !in 1..31) return date
    val ymd = String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
    return when (ymd) {
        calendarYmd(0) -> "Today"
        calendarYmd(1) -> "Yesterday"
        else -> String.format(Locale.US, "%d %s %d", day, SHORT_MONTHS[month - 1], year)
    }
}

/** Local date as YYYY-MM-DD, [daysBack] days ago (Calendar, not java.time:
 *  minSdk 24 < 26 and no core-library desugaring). */
private fun calendarYmd(daysBack: Int): String {
    val calendar = Calendar.getInstance()
    calendar.add(Calendar.DAY_OF_YEAR, -daysBack)
    return String.format(
        Locale.US,
        "%04d-%02d-%02d",
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH) + 1,
        calendar.get(Calendar.DAY_OF_MONTH)
    )
}

/** Date-group header: label on the left, the day's EXPENSE total in red on the
 *  right (hidden when zero — e.g. a group containing only transfers/income). */
@Composable
fun DateHeader(label: String, expenseTotal: Double) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
        if (expenseTotal > 0.0) {
            Text(
                text = String.format(Locale.US, "-฿%.2f", expenseTotal),
                style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                color = androidx.compose.ui.graphics.Color(0xFFF44336)
            )
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