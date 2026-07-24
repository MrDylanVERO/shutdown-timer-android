package com.mrdylan.shutdowntimerremote

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.TimePicker
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var addressInput: EditText
    private lateinit var pinInput: EditText
    private lateinit var timePicker: TimePicker
    private lateinit var statusText: TextView
    private lateinit var sendButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        addressInput = findViewById(R.id.addressInput)
        pinInput = findViewById(R.id.pinInput)
        timePicker = findViewById(R.id.timePicker)
        statusText = findViewById(R.id.statusText)
        sendButton = findViewById(R.id.sendButton)
        timePicker.setIs24HourView(true)

        val preferences = getSharedPreferences("connection", MODE_PRIVATE)
        addressInput.setText(preferences.getString("address", ""))
        pinInput.setText(preferences.getString("pin", ""))

        sendButton.setOnClickListener {
            val address = addressInput.text.toString().trim().removePrefix("http://").trimEnd('/')
            val pin = pinInput.text.toString().trim()
            if (address.isBlank() || pin.length != 6) {
                statusText.text = "Bitte PC-Adresse und 6-stelligen PIN eingeben."
                return@setOnClickListener
            }
            preferences.edit().putString("address", address).putString("pin", pin).apply()
            scheduleShutdown(address, pin, timePicker.hour, timePicker.minute)
        }
    }

    private fun scheduleShutdown(address: String, pin: String, hour: Int, minute: Int) {
        sendButton.isEnabled = false
        statusText.text = "Verbindung zum PC..."
        thread {
            try {
                val connection = URL("http://$address:8765/schedule").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                val payload = JSONObject()
                    .put("pin", pin)
                    .put("hour", hour)
                    .put("minute", minute)
                    .toString()
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                runOnUiThread {
                    statusText.text = when (code) {
                        200 -> "PC wird um %02d:%02d Uhr ausgeschaltet.".format(hour, minute)
                        403 -> "Falscher PIN."
                        409 -> "Auf dem PC läuft bereits ein Timer."
                        else -> "Der PC hat die Anfrage abgelehnt ($code)."
                    }
                    sendButton.isEnabled = true
                }
                connection.disconnect()
            } catch (_: Exception) {
                runOnUiThread {
                    statusText.text = "PC nicht erreichbar. Gleiches WLAN und Adresse prüfen."
                    sendButton.isEnabled = true
                }
            }
        }
    }
}
