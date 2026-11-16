package com.example.security

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lightweight SMTP over TLS/SSL client supporting port 465 (SMTPS) and port 587 (STARTTLS)
 * with MIME multipart JPEG photo attachments and zero external dependencies.
 */
class SmtpEmailSender {

  suspend fun sendEmail(
    host: String,
    port: Int,
    username: String,
    password: String,
    toEmail: String,
    subject: String,
    bodyText: String,
    attachmentFile: File? = null,
  ): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
      val cleanHost = host.trim()
      val cleanUser = username.trim()
      val cleanPass =
        if (cleanHost.contains("gmail", ignoreCase = true) || cleanHost.contains("googlemail", ignoreCase = true)) {
          password.replace(" ", "").trim()
        } else {
          password.trim()
        }
      val cleanTo = toEmail.trim()

      require(cleanHost.isNotBlank()) { "SMTP host is required" }
      require(cleanUser.isNotBlank() && cleanPass.isNotBlank()) {
        "SMTP username and app password are required"
      }
      require(cleanTo.isNotBlank()) { "Recipient email is required" }

      val socket: Socket =
        if (port == 465) {
          val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
          val ssl = sslFactory.createSocket() as SSLSocket
          ssl.connect(InetSocketAddress(cleanHost, port), 15_000)
          ssl.soTimeout = 20_000
          ssl.startHandshake()
          ssl
        } else {
          val plain = Socket()
          plain.connect(InetSocketAddress(cleanHost, port), 15_000)
          plain.soTimeout = 20_000
          plain
        }

      socket.use { activeSocket ->
        var reader = BufferedReader(InputStreamReader(activeSocket.getInputStream(), Charsets.UTF_8))
        var writer = BufferedWriter(OutputStreamWriter(activeSocket.getOutputStream(), Charsets.UTF_8))

        expectCode(reader, 220)
        sendCommand(writer, "EHLO terraguard.local")
        expectCode(reader, 250)

        if (port != 465) {
          sendCommand(writer, "STARTTLS")
          expectCode(reader, 220)
          val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
          val tlsSocket =
            sslFactory.createSocket(activeSocket, cleanHost, port, true) as SSLSocket
          tlsSocket.soTimeout = 20_000
          tlsSocket.startHandshake()
          reader = BufferedReader(InputStreamReader(tlsSocket.getInputStream(), Charsets.UTF_8))
          writer = BufferedWriter(OutputStreamWriter(tlsSocket.getOutputStream(), Charsets.UTF_8))
          sendCommand(writer, "EHLO terraguard.local")
          expectCode(reader, 250)
        }

        val authLoginResult = runCatching {
          sendCommand(writer, "AUTH LOGIN")
          expectCode(reader, 334)
          sendCommand(writer, Base64.encodeToString(cleanUser.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
          expectCode(reader, 334)
          sendCommand(writer, Base64.encodeToString(cleanPass.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
          expectCode(reader, 235)
        }

        if (authLoginResult.isFailure) {
          val plainAuthBytes = byteArrayOf(0) + cleanUser.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + cleanPass.toByteArray(Charsets.UTF_8)
          val base64Auth = Base64.encodeToString(plainAuthBytes, Base64.NO_WRAP)
          sendCommand(writer, "AUTH PLAIN $base64Auth")
          val plainResult = runCatching { expectCode(reader, 235) }
          if (plainResult.isFailure) {
            throw authLoginResult.exceptionOrNull() ?: plainResult.exceptionOrNull()!!
          }
        }

        sendCommand(writer, "MAIL FROM:<$cleanUser>")
        expectCode(reader, 250)
        sendCommand(writer, "RCPT TO:<$cleanTo>")
        expectCode(reader, 250)
        sendCommand(writer, "DATA")
        expectCode(reader, 354)

        val boundary = "TerraGuardBoundary_${UUID.randomUUID().toString().replace("-", "")}"
        val cleanSubject = subject.replace("\r", " ").replace("\n", " ")
        val attachmentBytes =
          if (attachmentFile != null && attachmentFile.exists()) {
            compressImageForEmail(attachmentFile)
          } else {
            null
          }

        val message = buildString {
          append("From: TerraGuard Security <$cleanUser>\r\n")
          append("To: <$cleanTo>\r\n")
          append("Subject: $cleanSubject\r\n")
          append("MIME-Version: 1.0\r\n")
          if (attachmentBytes != null && attachmentFile != null) {
            append("Content-Type: multipart/mixed; boundary=\"$boundary\"\r\n\r\n")
            append("--$boundary\r\n")
            append("Content-Type: text/plain; charset=UTF-8\r\n")
            append("Content-Transfer-Encoding: 8bit\r\n\r\n")
            append(escapeDotStuffing(bodyText))
            append("\r\n\r\n")
            append("--$boundary\r\n")
            append("Content-Type: image/jpeg; name=\"${attachmentFile.name}\"\r\n")
            append("Content-Transfer-Encoding: base64\r\n")
            append("Content-Disposition: attachment; filename=\"${attachmentFile.name}\"\r\n\r\n")
            append(Base64.encodeToString(attachmentBytes, Base64.CRLF))
            append("\r\n--$boundary--\r\n")
          } else {
            append("Content-Type: text/plain; charset=UTF-8\r\n\r\n")
            append(escapeDotStuffing(bodyText))
            append("\r\n")
          }
          append(".\r\n")
        }

        writer.write(message)
        writer.flush()
        expectCode(reader, 250)

        runCatching { sendCommand(writer, "QUIT") }
      }
      Unit
    }
  }

  private fun compressImageForEmail(file: File): ByteArray {
    if (file.length() <= 400_000L) {
      return file.readBytes()
    }
    return runCatching {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(file.absolutePath, bounds)
      var sampleSize = 1
      val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
      while (maxDim / sampleSize > 1280) {
        sampleSize *= 2
      }
      val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
      val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return@runCatching file.readBytes()
      val out = ByteArrayOutputStream()
      bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
      bitmap.recycle()
      out.toByteArray()
    }.getOrElse { file.readBytes() }
  }

  private fun escapeDotStuffing(text: String): String {
    return text.replace("\r\n", "\n").split("\n").joinToString("\r\n") { line ->
      if (line.startsWith(".")) ".$line" else line
    }
  }

  private fun sendCommand(writer: BufferedWriter, cmd: String) {
    writer.write("$cmd\r\n")
    writer.flush()
  }

  private fun expectCode(reader: BufferedReader, expectedCode: Int) {
    var line: String?
    var lastLine = ""
    while (true) {
      line = reader.readLine()
      if (line == null) {
        throw IllegalStateException("SMTP connection closed unexpectedly (expected $expectedCode)")
      }
      lastLine = line
      if (line.length < 4 || line[3] == ' ') break
    }
    val code = lastLine.take(3).toIntOrNull() ?: -1
    if (code != expectedCode) {
      throw IllegalStateException("SMTP error (expected $expectedCode): $lastLine")
    }
  }
}
