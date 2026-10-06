package com.purrfectbytes.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.purrfectbytes.android.services.StudyItem
import com.purrfectbytes.android.services.TextSource

/** The parts of the YouTube card that decide what the description says. */

/** Stands for "no saved source" in the list of sources. */
private val GENERAL_CREDIT = TextSource(id = "", name = "— Generic credit —", credit = "", createdAt = "")

/**
 * Where the text is taken from. The credit of the source that is chosen stands in the
 * description in place of the general one, word for word.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourcePicker(
    sources: List<TextSource>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onSave: (name: String, credit: String) -> Boolean,
    onDelete: () -> Unit
) {
    var showForm by remember { mutableStateOf(false) }
    val selected = sources.firstOrNull { it.id == selectedId }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Text source (for the credit line):", style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.height(4.dp))
        DropdownField(
            value = (selected ?: GENERAL_CREDIT).name,
            options = listOf(GENERAL_CREDIT) + sources,
            optionLabel = { it.name },
            onSelect = { onSelect(it.id.ifEmpty { null }) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
        )

        Spacer(modifier = Modifier.height(4.dp))
        Hint(selected?.credit ?: "The credit of a saved source takes the place of the general one")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { showForm = !showForm }) {
                Text(if (showForm) "Cancel" else "+ New source")
            }
            TextButton(onClick = onDelete, enabled = selected != null) {
                Text("Delete source")
            }
        }

        if (showForm) {
            SourceForm(onSave = { name, credit -> if (onSave(name, credit)) showForm = false })
        }
    }
}

@Composable
private fun SourceForm(onSave: (name: String, credit: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var credit by remember { mutableStateOf("") }

    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Source name") },
        placeholder = { Text("e.g. Shin Kanzen Master N1") },
        singleLine = true
    )
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedTextField(
        value = credit,
        onValueChange = { credit = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Exact credit line") },
        placeholder = { Text("e.g. This sentence comes from the textbook 新完全マスター N1 by 3A Corporation.") },
        minLines = 2,
        maxLines = 4
    )
    Spacer(modifier = Modifier.height(4.dp))
    Button(
        onClick = { onSave(name, credit) },
        enabled = name.isNotBlank() && credit.isNotBlank()
    ) {
        Text("Save source")
    }
}

/**
 * The words and grammar points the model found, all ticked. What is unticked is left
 * out of the description; what is ticked is all that it explains.
 */
@Composable
internal fun StudyItemList(
    items: List<StudyItem>,
    approved: Set<Int>,
    onToggle: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Review items",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Hint(
                "Untick what was misread or is not worth explaining, then fill in title and " +
                    "description below. The description will explain exactly the ticked items."
            )

            StudyItemSection("🔤 Breakdown", grammar = false, items, approved, onToggle)
            StudyItemSection("📚 Grammar Points", grammar = true, items, approved, onToggle)
        }
    }
}

@Composable
private fun StudyItemSection(
    title: String,
    grammar: Boolean,
    items: List<StudyItem>,
    approved: Set<Int>,
    onToggle: (Int) -> Unit
) {
    Spacer(modifier = Modifier.height(8.dp))
    Text(text = title, style = MaterialTheme.typography.labelLarge)

    val section = items.withIndex().filter { it.value.isGrammar == grammar }
    if (section.isEmpty()) Hint("None found")

    section.forEach { (index, item) ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = index in approved,
                    role = Role.Checkbox,
                    onValueChange = { onToggle(index) }
                )
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = index in approved, onCheckedChange = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = item.label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
