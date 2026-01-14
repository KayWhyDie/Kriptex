package com.ivor.kriptex

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentContainerView
import com.ivor.kriptex.ui.compose.KriptexTheme
import com.mikepenz.aboutlibraries.LibsBuilder

class AboutComposeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            KriptexTheme {
                AboutScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ABOUT") },
                navigationIcon = { TextButton(onClick = onBack) { Text("<") } }
            )
        }
    ) { padding ->
        val tag = remember { "libs" }
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            factory = { context ->
                val activity = context as AppCompatActivity
                val container = FragmentContainerView(context).apply {
                    id = View.generateViewId()
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
                container
            },
            update = { container ->
                val activity = container.context as AppCompatActivity
                if (activity.supportFragmentManager.findFragmentByTag(tag) != null) return@AndroidView

                // The FragmentContainerView must be attached to the view hierarchy before
                // the fragment can create its view. Posting avoids "No view found for id".
                container.post {
                    if (activity.isFinishing || activity.isDestroyed) return@post
                    if (activity.supportFragmentManager.findFragmentByTag(tag) != null) return@post

                    val libsFragment = LibsBuilder()
                        .withActivityTitle("Open-source Libraries")
                        .supportFragment()
                    activity.supportFragmentManager
                        .beginTransaction()
                        .replace(container.id, libsFragment, tag)
                        .commitAllowingStateLoss()
                }
            }
        )
    }
}
