package com.mrdylan.shutdowntimerremote

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.TimePicker
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var pinInput: EditText
    private lateinit var timePicker: TimePicker
    private lateinit var statusText: TextView
    private lateinit var countdownText: TextView
    private lateinit var sendButton: Button
    private lateinit var cancelButton: Button
    @Volatile private var polling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        pinInput = findViewById(R.id.pinInput)
        timePicker = findViewById(R.id.timePicker)
        statusText = findViewById(R.id.statusText)
        countdownText = findViewById(R.id.countdownText)
        sendButton = findViewById(R.id.sendButton)
        cancelButton = findViewById(R.id.cancelButton)
        timePicker.setIs24HourView(true)

        val preferences = getSharedPreferences("connection", MODE_PRIVATE)
        pinInput.setText(preferences.getString("pin", ""))
        cancelButton.isEnabled = false

        sendButton.setOnClickListener {
            val pin = pinInput.text.toString().trim()
            if (pin.length != 6 || !pin.all { it.isDigit() }) {
                statusText.text = "Bitte den 6-stelligen PIN eingeben."
                return@setOnClickListener
            }
            preferences.edit().putString("pin", pin).apply()
            findPcAndSchedule(pin, timePicker.hour, timePicker.minute)
        }

        cancelButton.setOnClickListener {
            val pin = pinInput.text.toString().trim()
            val address = preferences.getString("address", "") ?: ""
            val port = preferences.getInt("port", 8765)
            if (address.isBlank() || pin.length != 6) {
                statusText.text = "Keine aktive PC-Verbindung."
            } else {
                cancelTimer(address, port, pin)
            }
        }

        val savedAddress = preferences.getString("address", "") ?: ""
        val savedPin = preferences.getString("pin", "") ?: ""
        if (savedAddress.isNotBlank() && savedPin.length == 6) {
            startCountdownPolling(savedAddress, preferences.getInt("port", 8765), savedPin)
        }
    }

    private fun findPcAndSchedule(pin: String, hour: Int, minute: Int) {
        sendButton.isEnabled = false
        statusText.text = "PC wird im WLAN gesucht..."
        thread {
            try {
                val socket = DatagramSocket().apply { broadcast = true; soTimeout = 3500 }
                val request = "SHUTDOWN_TIMER_DISCOVER:$pin".toByteArray(Charsets.UTF_8)
                socket.send(DatagramPacket(request, request.size, InetAddress.getByName("255.255.255.255"), 8766))
                val buffer = ByteArray(1024)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                val reply = JSONObject(String(response.data, 0, response.length, Charsets.UTF_8))
                if (reply.optString("service") != "shutdown_timer") throw IllegalStateException()
                val address = response.address.hostAddress
                val port = reply.optInt("port", 8765)
                socket.close()
                getSharedPreferences("connection", MODE_PRIVATE).edit()
                    .putString("address", address).putInt("port", port).apply()
                scheduleShutdown(address, port, pin, hour, minute)
            } catch (_: SocketTimeoutException) {
                showError("PC nicht gefunden. PC-App, PIN und WLAN prüfen.")
            } catch (_: Exception) {
                showError("Verbindung fehlgeschlagen. PC-App und WLAN prüfen.")
            }
        }
    }

    private fun scheduleShutdown(address: String, port: Int, pin: String, hour: Int, minute: Int) {
        runOnUiThread { statusText.text = "PC gefunden. Timer wird gesendet..." }
        try {
            val connection = URL("http://$address:$port/schedule").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            val payload = JSONObject().put("pin", pin).put("hour", hour).put("minute", minute).toString()
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            runOnUiThread {
                statusText.text = when (code) {
                    200 -> "Timer wurde erfolgreich gestartet."
                    403 -> "Falscher PIN."
                    409 -> "Auf dem PC läuft bereits ein Timer."
                    else -> "Der PC hat die Anfrage abgelehnt ($code)."
                }
                sendButton.isEnabled = true
                if (code == 200) cancelButton.isEnabled = true
            }
            connection.disconnect()
            if (code == 200) startCountdownPolling(address, port, pin)
        } catch (_: Exception) {
            showError("PC nicht erreichbar. WLAN und Windows-Firewall prüfen.")
        }
    }

    private fun startCountdownPolling(address: String, port: Int, pin: String) {
        if (polling) return
        polling = true
        thread {
            while (polling) {
                try {
                    val connection = URL("http://$address:$port/status").openConnection() as HttpURLConnection
                    connection.connectTimeout = 2500
                    connection.readTimeout = 2500
                    connection.setRequestProperty("X-Shutdown-Pin", pin)
                    if (connection.responseCode == 200) {
                        val response = connection.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(response)
                        val running = json.optBoolean("running")
                        val remaining = json.optInt("remaining")
                        val hours = remaining / 3600
                        val minutes = (remaining % 3600) / 60
                        val seconds = remaining % 60
                        runOnUiThread {
                            countdownText.text = "%02d:%02d:%02d".format(hours, minutes, seconds)
                            cancelButton.isEnabled = running
                        }
                        if (!running) polling = false
                    }
                    connection.disconnect()
                } catch (_: Exception) {
                    polling = false
                }
                if (polling) Thread.sleep(1000)
            }
        }
    }

    private fun cancelTimer(address: String, port: Int, pin: String) {
        cancelButton.isEnabled = false
        thread {
            try {
                val connection = URL("http://$address:$port/cancel").openConnection() as HttpURLConnection
                connection.requestMethod = "DELETE"
                connection.connectTimeout = 4000
                connection.readTimeout = 4000
                connection.setRequestProperty("X-Shutdown-Pin", pin)
                val code = connection.responseCode
                connection.disconnect()
                polling = false
                runOnUiThread {
                    statusText.text = if (code == 200) "Timer wurde abgebrochen." else "Abbrechen fehlgeschlagen ($code)."
                    countdownText.text = "00:00:00"
                    sendButton.isEnabled = true
                }
            } catch (_: Exception) {
                showError("Timer konnte nicht abgebrochen werden.")
            }
        }
    }

    private fun showError(message: String) {
        runOnUiThread {
            statusText.text = message
            sendButton.isEnabled = true
            cancelButton.isEnabled = false
        }
    }

    override fun onDestroy() {
        polling = false
        super.onDestroy()
    }
}
