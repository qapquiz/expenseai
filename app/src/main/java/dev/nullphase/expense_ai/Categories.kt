package dev.nullphase.expense_ai

/**
 * Single source of truth for expense categories.
 *
 * These exact strings are:
 *  - offered in the manual category picker (MainActivity CategoryPickerSheet)
 *  - injected into the Gemini prompt ("choose EXACTLY ONE of ...")
 *  - the canonical set for future summaries (anything off-list rolls up to
 *    "Other" at query time)
 *
 * Stored as display labels directly in the DB (`expenses.category`) — single
 * locale app, no id/label mapping layer.
 */
object Categories {
    val ALL = listOf(
        "Food & Drink",
        "Groceries",
        "Transport",
        "Bills & Utilities",
        "Shopping",
        "Health",
        "Entertainment",
        "Travel",
        "Other"
    )
}
