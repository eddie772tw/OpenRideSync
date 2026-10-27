package org.openridesync.app.messages

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openridesync.app.OpenRideSyncApplication
import org.openridesync.app.ble.Gen1ControlExchange
import org.openridesync.app.ble.Gen1Input.Function
import org.openridesync.app.ble.Gen1InputMonitor
import org.openridesync.app.media.MediaAccessService
import org.openridesync.app.media.MediaState
import org.openridesync.app.runtime.*
import org.openridesync.app.ui.theme.OpenRideSyncTheme
import org.openridesync.diagnostics.DiagnosticJournal
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Own synthetic notification/RemoteInput only. Mode, recognition, readback and ACKs are synthetic.
 * The blank Compose host creates no production phone message owner, microphone or ride connection.
 * NativeMessages may transiently observe other notifications in bounded RAM; only this UUID title
 * enters RidePages. No source IDs, other notification content, recording or transport is exported. */
@RunWith(AndroidJUnit4::class)
class RideReplyDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val transcript = "Synthetic ride reply exact text"
    private val quickReplies = List(5) { "Synthetic quick reply ${it + 1}" }

    private fun <T> main(action: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(action()) }
        return result.get()
    }

    private fun awaitCondition(timeoutMs: Long = 5_000, check: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (check()) return true
            SystemClock.sleep(25)
        }
        return check()
    }

    private inner class Session(context: Context, journal: DiagnosticJournal, val backend: NativeMessages,
        title: String, val ui: MutableStateFlow<RidePageState>) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val source = Channel<ByteArray>(Channel.UNLIMITED)
        val receipts = Channel<ByteArray>(Channel.UNLIMITED)
        val frames = mutableListOf<ByteArray>()
        val monitor = Gen1InputMonitor(journal, journal.newOperation())
        val inbox = MutableStateFlow(MessageInboxState())
        var current = true
        val read = AtomicInteger()
        val recognized = AtomicInteger()
        val confirmed = AtomicInteger()
        val dispatched = AtomicInteger()
        val resultSpeechGate = CompletableDeferred<Unit>()
        val controls = Gen1ControlExchange(receipts, { frame ->
            check(frames.size < 512) { "Synthetic control output must stay bounded" }
            frames.add(frame.copyOf())
            receipts.send(byteArrayOf(frame[0], frame[1], 1)) // Synthetic memory ACK, never GATT.
        }, journal, journal.newOperation())
        lateinit var pages: RidePages
        init {
            val app = context.applicationContext as OpenRideSyncApplication
            pages = RidePages(monitor, controls, MutableStateFlow<MediaState?>(null),
                MutableStateFlow(CallContext(notificationUnknown = false)), MutableStateFlow(null), 4,
                RidePageLabels("Unknown", "Music", "Playing", "Choose", "Volume"), Locale.US, false,
                { current && app.ride.state.value.stage == RideStage.IDLE }, {},
                { _, _ -> error("Synthetic reply must not control media") },
                { error("Synthetic reply must not control audio volume") }, MutableStateFlow(false), messages = inbox,
                readMessage = { id -> check(inbox.value.messages.single().id == id); read.incrementAndGet() },
                replyAvailability = { RideReplyAvailability.READY },
                recognizeReply = { id -> check(inbox.value.messages.single().id == id); recognized.incrementAndGet(); transcript },
                confirmReply = { _, text -> check(text == transcript); confirmed.incrementAndGet() },
                reportReplyResult = { id, result ->
                    check(inbox.value.messages.single().id == id)
                    assertEquals(MessageReplyResult.DISPATCHED, result)
                    resultSpeechGate.await() // Hold only synthetic result speech while taking its screenshot.
                },
                dispatchReply = { id, text ->
                    dispatched.incrementAndGet()
                    backend.replyFromRide(id, text) { current && pages.messageReplyCurrent(id) && app.ride.state.value.stage == RideStage.IDLE }
                }, quickReplies = MutableStateFlow(quickReplies))
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                backend.state.collect { value ->
                    inbox.value = value.copy(messages = value.messages.filter { it.packageName == context.packageName && it.title == title })
                }
            }
            scope.launch(start = CoroutineStart.UNDISPATCHED) { monitor.consumeFunctions(source) }
            scope.launch(start = CoroutineStart.UNDISPATCHED) { controls.run() }
            scope.launch(start = CoroutineStart.UNDISPATCHED) { pages.run() }
            scope.launch(start = CoroutineStart.UNDISPATCHED) { pages.state.collect { ui.value = it } }
            source.trySend(byteArrayOf(9)) // Explicit synthetic MODE_SAB fixture, not observed vehicle feedback.
        }
        fun close() {
            current = false
            pages.close()
            controls.close()
            scope.cancel()
            source.cancel(); receipts.cancel()
        }
    }

    @Test fun ownSyntheticReplyRequiresConfirmationAndOneExplicitSendWhileStaleOwnersSendNothing() {
        val context = instrumentation.targetContext
        val notifications = context.getSystemService(NotificationManager::class.java)
        assumeTrue("Existing notification posting and listener grants required",
            notifications?.areNotificationsEnabled() == true && notifications.isNotificationListenerAccessGranted(
                ComponentName(context, MediaAccessService::class.java)))
        assumeTrue("Device must already be unlocked", context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == false)
        assumeTrue("Real ride must already be idle", main {
            (context.applicationContext as OpenRideSyncApplication).ride.state.value.stage == RideStage.IDLE
        })
        val uuid = UUID.randomUUID().toString()
        val title = "Synthetic ride reply $uuid"
        val channel = "ors-synthetic-ride-reply-$uuid"
        val action = "${context.packageName}.SYNTHETIC_RIDE_REPLY.$uuid"
        val resultKey = "synthetic_ride_reply"
        val deliveries = AtomicInteger()
        val voiceDeliveries = AtomicInteger()
        val quickDeliveries = AtomicInteger()
        val expectedText = AtomicReference(transcript)
        val exactText = AtomicBoolean(true)
        val delivered = AtomicReference(CountDownLatch(1))
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action != action || intent.`package` != context.packageName) return
                val actual = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(resultKey)?.toString()
                if (actual != expectedText.get()) exactText.set(false)
                when (actual) {
                    transcript -> voiceDeliveries.incrementAndGet()
                    quickReplies.last() -> quickDeliveries.incrementAndGet()
                    else -> exactText.set(false)
                }
                deliveries.incrementAndGet(); delivered.get().countDown()
            }
        }
        val output = InstrumentationRegistry.getArguments().getString("uiGalleryOutputDir")?.takeIf { it.isNotBlank() }
            ?.let(::File) ?: File(context.cacheDir, "synthetic-ride-reply-$uuid")
        check(output.isDirectory || output.mkdirs())
        val journal = DiagnosticJournal(File(context.cacheDir, "synthetic-ride-reply-journal-$uuid"))
        val ui = MutableStateFlow(RidePageState())
        var session: Session? = null
        var backend: NativeMessages? = null
        var pending: PendingIntent? = null
        var registered = false
        try {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
            else context.registerReceiver(receiver, IntentFilter(action))
            registered = true
            val replyIntent = PendingIntent.getBroadcast(context, 0, Intent(action).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            pending = replyIntent
            notifications!!.createNotificationChannel(NotificationChannel(channel, "Synthetic ride reply test", NotificationManager.IMPORTANCE_LOW))
            val replyAction = Notification.Action.Builder(android.R.drawable.ic_menu_send, "Synthetic Reply", replyIntent)
                .addRemoteInput(RemoteInput.Builder(resultKey).setLabel("Synthetic reply").build())
                .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY).build()
            fun post(version: String) = notifications.notify(uuid, 1, Notification.Builder(context, channel)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText("Synthetic source $version")
                .setCategory(Notification.CATEGORY_MESSAGE).addAction(replyAction).build())
            val messages = main { NativeMessages(context).also { it.start() } }
            backend = messages
            assertTrue("Granted listener must connect", awaitCondition(20_000) { main { MediaAccessService.callSummary.value.listenerConnected } })
            fun owned() = main { messages.state.value.messages.singleOrNull { it.packageName == context.packageName && it.title == title } }
            compose.setContent { OpenRideSyncTheme { Column {
                Text("測試：自發通知／合成 SAB 與辨識，非實車")
                val page by ui.collectAsState()
                Box(Modifier.weight(1f)) {
                    RideContent(RideState(RideStage.CONNECTED, mode = Function.MODE_SAB), false, null, page,
                        {}, {}, {}, {}, {}, {}, onAction = { session?.pages?.performAction(it) })
                }
            } } }
            fun stage(expected: RidePageStage) = assertTrue("Synthetic page must reach $expected", awaitCondition {
                main { session!!.pages.state.value.let { it.stage == expected && !it.pending && it.receiptStatus == 1 } }
            })
            fun input(function: Function) = assertTrue("Explicit synthetic action must be accepted", main { session!!.pages.performAction(function) })
            fun confirmation(version: String) {
                post(version)
                assertTrue("Own source must be available", awaitCondition { owned()?.canReply == true && owned()?.text == "Synthetic source $version" })
                session = main { Session(context, journal, messages, title, ui) }
                stage(RidePageStage.MENU)
                assertTrue(main { session!!.pages.openFunction(MainMenuSelection.Item.MESSAGE) })
                stage(RidePageStage.MESSAGE)
                input(Function.SELECT)
                assertTrue("Synthetic read must complete", awaitCondition {
                    main { session!!.pages.state.value.messageReadStatus == MessageReadStatus.COMPLETE }
                })
                input(Function.SELECT); stage(RidePageStage.MESSAGE_CATEGORY)
                if (version == "accepted") save(output, "category")
                input(Function.SELECT); stage(RidePageStage.MESSAGE_CONFIRMATION)
                assertTrue("Readback callback must run", awaitCondition { session!!.confirmed.get() == 1 })
                assertEquals(1, session!!.read.get()); assertEquals(1, session!!.recognized.get())
                assertEquals(0, session!!.dispatched.get()); assertEquals(0, deliveries.get())
            }

            confirmation("stale")
            val stale = checkNotNull(owned()).id
            post("replacement")
            assertTrue("Source replacement must invalidate the retained ID", awaitCondition { owned()?.let { it.id != stale && it.text == "Synthetic source replacement" } == true })
            assertTrue("Replaced source must lose reply ownership", awaitCondition { main { !session!!.pages.messageReplyCurrent(stale) } })
            assertEquals(MessageReplyResult.STALE, main { messages.replyFromRide(stale, transcript) { session!!.pages.messageReplyCurrent(stale) } })
            assertEquals(0, deliveries.get()); assertEquals(0, session!!.dispatched.get())
            main { session!!.close() }; session = null

            confirmation("expired-owner")
            val expired = checkNotNull(owned()).id
            main { session!!.current = false }
            assertFalse(main { session!!.pages.performAction(Function.SELECT) })
            assertEquals(MessageReplyResult.STALE, main { messages.replyFromRide(expired, transcript) { session!!.current && session!!.pages.messageReplyCurrent(expired) } })
            assertEquals(0, deliveries.get()); assertEquals(0, session!!.dispatched.get())
            main { session!!.close() }; session = null

            confirmation("accepted")
            save(output, "confirmation")
            SystemClock.sleep(250)
            assertEquals("Recognition/readback must never auto-send", 0, deliveries.get())
            input(Function.SELECT)
            assertTrue("Own RemoteInput receiver must receive the synthetic text", delivered.get().await(5, TimeUnit.SECONDS))
            stage(RidePageStage.MESSAGE_SENT)
            assertTrue("Only the exact synthetic text may be delivered", exactText.get())
            assertNull(main { session!!.pages.state.value.replyText })
            save(output, "result")
            input(Function.SELECT)
            val spent = checkNotNull(owned()).id
            assertEquals(MessageReplyResult.STALE, main { messages.replyFromRide(spent, transcript) { true } })
            SystemClock.sleep(250)
            assertEquals(1, deliveries.get()); assertEquals(1, session!!.dispatched.get())
            assertTrue(main { session!!.frames.isNotEmpty() })
            main { session!!.resultSpeechGate.complete(Unit) }
            assertTrue("Result must leave after synthetic speech and the product's one-second delay", awaitCondition {
                main { session!!.pages.state.value.let {
                    it.stage in setOf(RidePageStage.MESSAGE, RidePageStage.MENU) && it.replyText == null
                } }
            })
            assertEquals(1, deliveries.get()); assertEquals(1, session!!.dispatched.get())
            assertEquals(1, voiceDeliveries.get()); assertEquals(0, quickDeliveries.get())
            main { session!!.close() }; session = null

            expectedText.set(quickReplies.last())
            delivered.set(CountDownLatch(1))
            post("quick-accepted")
            assertTrue("Fresh own quick-reply source must be available", awaitCondition {
                owned()?.let { it.canReply && it.text == "Synthetic source quick-accepted" } == true
            })
            session = main { Session(context, journal, messages, title, ui) }
            stage(RidePageStage.MENU)
            assertTrue(main { session!!.pages.openFunction(MainMenuSelection.Item.MESSAGE) })
            stage(RidePageStage.MESSAGE)
            input(Function.SELECT)
            assertTrue("Synthetic quick source read must complete", awaitCondition {
                main { session!!.pages.state.value.messageReadStatus == MessageReadStatus.COMPLETE }
            })
            input(Function.SELECT); stage(RidePageStage.MESSAGE_CATEGORY)
            input(Function.NEXT)
            assertEquals(1, main { session!!.pages.state.value.replyChoice })
            input(Function.SELECT); stage(RidePageStage.MESSAGE_QUICK_LIST)
            assertEquals(quickReplies, main { session!!.pages.state.value.replyChoices })
            assertEquals(0, main { session!!.pages.state.value.replyChoice })
            assertEquals(1, deliveries.get()); assertEquals(0, session!!.dispatched.get())
            assertEquals(0, session!!.recognized.get()); assertEquals(0, session!!.confirmed.get())
            repeat(4) { index ->
                input(Function.NEXT)
                assertEquals(index + 1, main { session!!.pages.state.value.replyChoice })
                assertEquals(1, deliveries.get()); assertEquals(0, session!!.dispatched.get())
            }
            stage(RidePageStage.MESSAGE_QUICK_LIST)
            compose.onNodeWithTag("ride_reply_choice_4").assertIsSelected().assertIsDisplayed()
            save(output, "quick-list")
            SystemClock.sleep(250)
            assertEquals("Quick-list entry and focus must never auto-send", 1, deliveries.get())
            assertEquals(0, session!!.recognized.get()); assertEquals(0, session!!.confirmed.get())
            input(Function.SELECT)
            assertTrue("Own RemoteInput receiver must receive exactly the selected fifth quick reply",
                delivered.get().await(5, TimeUnit.SECONDS))
            stage(RidePageStage.MESSAGE_SENT)
            assertTrue("Each phase must deliver only its exact synthetic text", exactText.get())
            assertEquals(2, deliveries.get()); assertEquals(1, voiceDeliveries.get()); assertEquals(1, quickDeliveries.get())
            assertEquals(1, session!!.read.get()); assertEquals(1, session!!.dispatched.get())
            assertEquals(0, session!!.recognized.get()); assertEquals(0, session!!.confirmed.get())
            assertNull(main { session!!.pages.state.value.replyText })
            save(output, "quick-result")
            input(Function.SELECT)
            val quickSpent = checkNotNull(owned()).id
            assertEquals(MessageReplyResult.STALE, main { messages.replyFromRide(quickSpent, quickReplies.last()) { true } })
            SystemClock.sleep(250)
            assertEquals(2, deliveries.get()); assertEquals(1, session!!.dispatched.get())
            main { session!!.resultSpeechGate.complete(Unit) }
            assertTrue("Quick result must leave after synthetic speech and the product's one-second delay", awaitCondition {
                main { session!!.pages.state.value.let {
                    it.stage in setOf(RidePageStage.MESSAGE, RidePageStage.MENU) && it.replyText == null
                } }
            })
            assertEquals(2, deliveries.get()); assertEquals(1, voiceDeliveries.get()); assertEquals(1, quickDeliveries.get())
            File(output, "synthetic-ride-reply.fixture.txt").writeText(
                "notification=own-synthetic; mode=synthetic-SAB; recognition=callback; readback=callback\n" +
                "remoteInputDeliveries=2; voiceRemoteInputDeliveries=1; quickRemoteInputDeliveries=1; exactSyntheticText=true; staleSourceSends=0; expiredOwnerSends=0\n" +
                "quickReplies=5-synthetic; quickSelected=5; quickRecognition=0; quickReadback=0; quickFocusSends=0\n" +
                "microphone=false; tts=false; network=false; vehicle=false; controls=in-memory-synthetic-ACK\n")
        } finally {
            try { main { session?.close(); backend?.close() } }
            finally {
                try { notifications?.cancel(uuid, 1) }
                finally {
                    try { if (registered) context.unregisterReceiver(receiver) }
                    finally {
                        try { pending?.cancel() }
                        finally { notifications?.deleteNotificationChannel(channel); journal.close() }
                    }
                }
            }
        }
    }

    private fun save(directory: File, stage: String) {
        compose.onNodeWithTag(if (stage == "quick-list") "ride_reply_choice_4" else "ride_current_function")
            .performScrollTo().assertExists()
        File(directory, "synthetic-ride-reply-$stage.png").outputStream().use {
            check(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
