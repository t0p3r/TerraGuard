package com.example.security

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object AlarmController {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private var alarmJob: Job? = null
  private var savedAlarmVolume: Int? = null
  private var wakeLock: PowerManager.WakeLock? = null
  private var audioFocusRequest: AudioFocusRequest? = null

  private val _isAlarmRinging = MutableStateFlow(false)
  val isAlarmRinging: StateFlow<Boolean> = _isAlarmRinging.asStateFlow()

  fun isNotificationPolicyAccessGranted(context: Context): Boolean {
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    return nm.isNotificationPolicyAccessGranted
  }

  fun startSiren(context: Context, overrideSilent: Boolean) {
    if (_isAlarmRinging.value) return
    val appContext = context.applicationContext
    _isAlarmRinging.value = true

    acquireWakeLock(appContext)

    val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val attributes =
      AudioAttributes.Builder()
        .setUsage(
          if (overrideSilent) AudioAttributes.USAGE_ALARM
          else AudioAttributes.USAGE_NOTIFICATION_RINGTONE
        )
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    requestAlarmAudioFocus(audioManager, attributes)

    if (overrideSilent) {
      runCatching {
        savedAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
      }
    }

    startVibration(appContext)

    alarmJob =
      scope.launch {
        val sampleRate = 22050
        val bufferSamples = sampleRate // 1 second cycle
        val pcm = ShortArray(bufferSamples)
        for (i in 0 until bufferSamples) {
          val t = i.toDouble() / sampleRate
          // Alternating emergency dual-tone siren (960 Hz <-> 1380 Hz)
          val freq = if ((i / (sampleRate / 4)) % 2 == 0) 960.0 else 1380.0
          val wave = sin(2.0 * PI * freq * t)
          pcm[i] = (wave * Short.MAX_VALUE * 0.94).toInt().toShort()
        }

        val minBufBytes =
          AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
          )
        val track =
          runCatching {
            AudioTrack.Builder()
              .setAudioAttributes(attributes)
              .setAudioFormat(
                AudioFormat.Builder()
                  .setSampleRate(sampleRate)
                  .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                  .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                  .build()
              )
              .setBufferSizeInBytes(maxOf(minBufBytes, pcm.size * 2))
              .setTransferMode(AudioTrack.MODE_STREAM)
              .build()
          }.getOrNull()

        if (track != null) {
          runCatching {
            track.play()
            while (isActive && _isAlarmRinging.value) {
              track.write(pcm, 0, pcm.size)
            }
          }
          runCatching {
            track.stop()
            track.release()
          }
        }
      }
  }

  fun stopSiren(context: Context) {
    _isAlarmRinging.value = false
    alarmJob?.cancel()
    alarmJob = null

    val appContext = context.applicationContext
    val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    savedAlarmVolume?.let { prev ->
      runCatching { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, prev, 0) }
    }
    savedAlarmVolume = null
    abandonAlarmAudioFocus(audioManager)
    stopVibration(appContext)
    releaseWakeLock()
  }

  private fun acquireWakeLock(context: Context) {
    runCatching {
      val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
      if (wakeLock?.isHeld == true) return
      wakeLock =
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TerraGuard::AlarmSirenWakeLock").apply {
          setReferenceCounted(false)
          acquire(10 * 60 * 1000L)
        }
    }
  }

  private fun releaseWakeLock() {
    runCatching {
      if (wakeLock?.isHeld == true) {
        wakeLock?.release()
      }
      wakeLock = null
    }
  }

  private fun requestAlarmAudioFocus(audioManager: AudioManager, attributes: AudioAttributes) {
    runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val req =
          AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(false)
            .build()
        audioFocusRequest = req
        audioManager.requestAudioFocus(req)
      } else {
        @Suppress("DEPRECATION")
        audioManager.requestAudioFocus(
          null,
          AudioManager.STREAM_ALARM,
          AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
        )
      }
    }
  }

  private fun abandonAlarmAudioFocus(audioManager: AudioManager) {
    runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
      } else {
        @Suppress("DEPRECATION")
        audioManager.abandonAudioFocus(null)
      }
    }
  }

  private fun startVibration(context: Context) {
    val pattern = longArrayOf(0L, 400L, 180L, 400L, 180L, 700L, 300L)
    runCatching {
      val vibrator = getVibrator(context)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
      } else {
        @Suppress("DEPRECATION")
        vibrator?.vibrate(pattern, 0)
      }
    }
  }

  private fun stopVibration(context: Context) {
    runCatching { getVibrator(context)?.cancel() }
  }

  private fun getVibrator(context: Context): Vibrator? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
      manager?.defaultVibrator
    } else {
      @Suppress("DEPRECATION")
      context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
  }
}
