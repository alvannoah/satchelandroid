package com.example.satchel

import com.example.satchel.data.Push
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives Firebase messages. With the app in the background, Android shows messages
 * that have a notification part by itself (with the channel, icon and colour from the
 * manifest); this service sees messages while the app is open, and data-only ones.
 */
class SatchelMessagingService : FirebaseMessagingService() {
    @Deprecated("Firebase prefers installation IDs, but the server sends to registration tokens; see Push")
    override fun onNewToken(token: String) {
        applicationContext.container.push.tokenChanged(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"] ?: return
        val body = message.notification?.body ?: message.data["body"]
        val documentId = message.data[Push.EXTRA_DOCUMENT_ID]?.toIntOrNull()
        val notificationId = message.data[Push.EXTRA_NOTIFICATION_ID]?.toIntOrNull()
        applicationContext.container.push.show(title, body, documentId, notificationId)
    }
}
