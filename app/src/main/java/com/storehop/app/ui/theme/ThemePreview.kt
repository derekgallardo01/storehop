package com.storehop.app.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Preview(name = "Light", showBackground = true, showSystemUi = true)
@Composable
private fun ThemePreviewLight() {
    StorehopTheme(darkTheme = false) { ThemeSample() }
}

@Preview(name = "Dark", showBackground = true, showSystemUi = true)
@Composable
private fun ThemePreviewDark() {
    StorehopTheme(darkTheme = true) { ThemeSample() }
}

@Composable
private fun ThemeSample() {
    Surface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "DAIRY  (aisle 3)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            ItemRow(label = "Milk  2 L")
            ItemRow(label = "Eggs  dozen")

            Spacer(Modifier.height(8.dp))

            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Pingo Doce", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "12 items needed",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = {}) { Text("Add item") }
                OutlinedButton(onClick = {}) { Text("Edit aisles") }
            }
        }
    }
}

@Composable
private fun ItemRow(label: String) {
    var checked by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Checkbox(checked = checked, onCheckedChange = { checked = it })
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
