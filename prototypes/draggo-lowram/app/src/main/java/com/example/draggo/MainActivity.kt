package com.example.draggo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 28, 24, 24) }
        root.addView(TextView(this).apply { text = "Drag & Go — Low RAM prototype"; textSize = 22f })
        root.addView(TextView(this).apply {
            textSize = 16f
            text = "\n1. Enable the accessibility service below.\n2. Long-press selectable text or a text-containing item in another app.\n3. Choose an action in the floating pill.\n\nOCR is experimental and uses a separate process. Some apps prevent screenshots."
        })
        root.addView(Button(this).apply { text = "Open Accessibility settings"; setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } })
        setContentView(root)
    }
}
