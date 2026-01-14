package com.ivor.kriptex

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.ivor.kriptex.db.Database
import com.ivor.kriptex.ui.compose.KriptexTheme
import com.ivor.kriptex.utils.Settings

class CreateNewComposeActivity : AppCompatActivity() {

    private var nameState by mutableStateOf(TextFieldValue(""))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val db = Database.getInstance(this)
        nameState = TextFieldValue(db.getName())

        setContent {
            KriptexTheme {
                Surface {
                    CreateNewScreen(
                        name = nameState,
                        onNameChange = { if (it.text.length <= 32) nameState = it },
                        onStart = { startWithName() },
                    )
                }
            }
        }
    }

    private fun startWithName() {
        val name = nameState.text.trim()
        if (name.isEmpty()) return

        val db = Database.getInstance(this)
        db.setName(name)
        Settings.putBoolean(applicationContext, "start_setup_completed", true)

        startActivity(
            Intent(this, MainComposeActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }
}

@androidx.compose.runtime.Composable
private fun CreateNewScreen(
    name: TextFieldValue,
    onNameChange: (TextFieldValue) -> Unit,
    onStart: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = "NEW IDENTITY",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "alias only",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            singleLine = true,
            label = { Text("ALIAS") },
            supportingText = { Text("max 32 chars") },
        )

        Button(
            onClick = onStart,
            enabled = name.text.trim().isNotEmpty(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color(0xFF050507),
                disabledContainerColor = MaterialTheme.colorScheme.outline,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Text(text = "ENTER", style = MaterialTheme.typography.labelLarge)
        }
    }
}
