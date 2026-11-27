package com.example.satchel.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.satchel.BuildConfig
import com.example.satchel.MainActivity
import com.example.satchel.R
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Phone notifications through Firebase Cloud Messaging (project satchel-367a6,
 * app/google-services.json).
 *
 * The server sends with FCM's HTTP v1 API to each phone's registration token
 * (backend satchel/push.py, docs/ANDROID_APP.md "Push notifications"), which the app
 * registers at POST /me/devices after every sign-in and whenever the token changes
 * (see AppContainer). Firebase Messaging 25.1 deprecated getToken/onNewToken in favour
 * of installation IDs (register/onRegistered), but still fully supports them, and an
 * installation ID is not a token the server can send to: hence the suppressed warnings.
 * In debug builds the token is also logged, for test messages from the Firebase console.
 */
class Push(private val context: Context) {
    private val prefs = context.getSharedPreferences("satchel.push", Context.MODE_PRIVATE)

    /** This phone's registration token, once Firebase has given one. */
    val token: String? get() = prefs.getString(TOKEN, null)

    /** Called with every token Firebase gives; AppContainer registers it with the server. */
    var onToken: (String) -> Unit = {}

    /** Notifications appear in Settings under this one channel. Safe to call every launch. */
    fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            context.getString(R.string.notification_channel_id),
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.notification_channel_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Asks Firebase for the current token; later changes arrive through SatchelMessagingService.onNewToken. */
    @Suppress("DEPRECATION")   // see the class comment: the server sends to registration tokens
    fun refreshToken() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) task.result?.let(::tokenChanged)
            else Log.w(TAG, "Could not get a push token", task.exception)
        }
    }

    fun tokenChanged(token: String) {
        prefs.edit().putString(TOKEN, token).apply()
        // Never in release builds: the token addresses this phone.
        if (BuildConfig.DEBUG) Log.d(TAG, "Push token: $token")
        onToken(token)
    }

    /** Whether notifications may be shown (Android 13 and later ask the person). */
    fun allowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Shows a message that arrived while the app was open (Android shows the others
     * itself). Tapping it opens the app, and the document when [documentId] is set.
     */
    fun show(title: String, body: String?, documentId: Int?, notificationId: Int?) {
        if (!allowed()) return
        // The same extras Android puts on the launch intent for background messages.
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply {
                documentId?.let { putExtra(EXTRA_DOCUMENT_ID, it.toString()) }
                notificationId?.let { putExtra(EXTRA_NOTIFICATION_ID, it.toString()) }
            }
        val tap = PendingIntent.getActivity(
            context, notificationId ?: documentId ?: 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, context.getString(R.string.notification_channel_id))
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.satchel_accent))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), notification)
        } catch (_: SecurityException) {
            // Permission withdrawn between the check and now: nothing to show.
        }
    }

    companion object {
        private const val TAG = "SatchelPush"
        private const val TOKEN = "token"

        /**
         * Keys of the message's data part (all strings; document_id may be empty). When
         * Android shows a message itself, they reach MainActivity as launch-intent extras.
         */
        const val EXTRA_DOCUMENT_ID = "document_id"

        /** The server's notification, to mark read when tapped (POST /notifications/{id}/read). */
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
