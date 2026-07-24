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
    private lateinit var sendButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pinInput = findViewById(R.id.pinInput)
        timePicker = findViewById(R.id.timePicker)
        statusText = findViewById(R.id.statusText)
        sendButton = findViewById(R.id.sendButton)
        timePicker.setIs24HourView(true)

        val preferences = getSharedPreferences("connection", MODE_PRIVATE)
        pinInput.setText(preferences.getString("pin", ""))

        sendButton.setOnClickListener {
            val pin = pinInput.text.toString().trim()
            if (pin.length != 6 || !pin.all { it.isDigit() }) {
                statusText.text = "Bitte den 6-stelligen PIN eingeben."
                return@setOnClickListener
            }
            preferences.edit().putString("pin", pin).apply()
            findPcAndSchedule(pin, timePicker.hour, timePicker.minute)
        }
    }

    private fun findPcAndSchedule(pin: String, hour: Int, minute: Int) {
        sendButton.isEnabled = false
        statusText.text = "PC wird im WLAN gesucht..."
        thread {
            try {
                val socket = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 3500
                }
                val request = "SHUTDOWN_TIMER_DISCOVER:$pin".toByteArray(Charsets.UTF_8)
                socket.send(
                    DatagramPacket(
                        request,
                        request.size,
                        InetAddress.getByName("255.255.255.255"),
                        8766
                    )
                )
                val buffer = ByteArray(1024)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                val reply = JSONObject(
                    String(response.data, 0, response.length, Charsets.UTF_8)
                )
                if (reply.optString("service") != "shutdown_timer") {
                    throw IllegalStateException("Unknown service")
                }
                val address = response.address.hostAddress
                val port = reply.optInt("port", 8765)
                socket.close()
                scheduleShutdown(address, port, pin, hour, minute)
            } catch (_: SocketTimeoutException) {
                showError("PC nicht gefunden. PC-App öffnen, PIN prüfen und dasselbe WLAN verwenden.")
            } catch (_: Exception) {
                showError("Verbindung fehlgeschlagen. PC-App und WLAN prüfen.")
            }
        }
    }

    private fun scheduleShutdown(
        address: String,
        port: Int,
        pin: String,
        hour: Int,
        minute: Int
    ) {
        runOnUiThread { statusText.text = "PC gefunden. Timer wird gesendet..." }
        try {
            val connection =
                URL("http://$address:$port/schedule").openConnection() as HttpURLConnection
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
            showError("PC nicht erreichbar. Gleiches WLAN und Windows-Firewall prüfen.")
        }
    }

    private fun showError(message: String) {
        runOnUiThread {
            statusText.text = message
            sendButton.isEnabled = true
        }
    }
}
