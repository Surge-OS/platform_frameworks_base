/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.android.systemui.shared.clocks

import android.app.ActivityManager
import android.app.UserSwitchObserver
import android.content.Context
import android.database.ContentObserver
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import androidx.annotation.OpenForTesting
import com.android.systemui.log.LogMessageImpl
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.core.LogMessage
import com.android.systemui.log.core.Logger
import com.android.systemui.log.core.MessageBuffer
import com.android.systemui.log.core.MessageInitializer
import com.android.systemui.log.core.MessagePrinter
import com.android.systemui.plugins.ClockController
import com.android.systemui.plugins.ClockId
import com.android.systemui.plugins.ClockMetadata
import com.android.systemui.plugins.ClockProvider
import com.android.systemui.plugins.ClockProviderPlugin
import com.android.systemui.plugins.ClockSettings
import com.android.systemui.plugins.PluginLifecycleManager
import com.android.systemui.plugins.PluginListener
import com.android.systemui.plugins.PluginManager
import com.android.systemui.util.Assert
import java.io.PrintWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val KEY_TIMESTAMP = "appliedTimestamp"
private val KNOWN_PLUGINS =
    mapOf<String, List<ClockMetadata>>(
        "com.android.systemui.clocks.bignum" to listOf(ClockMetadata("ANALOG_CLOCK_BIGNUM")),
        "com.android.systemui.clocks.calligraphy" to
            listOf(ClockMetadata("DIGITAL_CLOCK_CALLIGRAPHY")),
        "com.android.systemui.clocks.flex" to listOf(ClockMetadata("DIGITAL_CLOCK_FLEX")),
        "com.android.systemui.clocks.growth" to listOf(ClockMetadata("DIGITAL_CLOCK_GROWTH")),
        "com.android.systemui.clocks.handwritten" to
            listOf(ClockMetadata("DIGITAL_CLOCK_HANDWRITTEN")),
        "com.android.systemui.clocks.inflate" to listOf(ClockMetadata("DIGITAL_CLOCK_INFLATE")),
        "com.android.systemui.clocks.metro" to listOf(ClockMetadata("DIGITAL_CLOCK_METRO")),
        "com.android.systemui.clocks.numoverlap" to
            listOf(ClockMetadata("DIGITAL_CLOCK_NUMBEROVERLAP")),
        "com.android.systemui.clocks.weather" to listOf(ClockMetadata("DIGITAL_CLOCK_WEATHER")),
    )

private fun <TKey : Any, TVal : Any> ConcurrentHashMap<TKey, TVal>.concurrentGetOrPut(
    key: TKey,
    value: TVal,
    onNew: (TVal) -> Unit
): TVal {
    val result = this.putIfAbsent(key, value)
    if (result == null) {
        onNew(value)
    }
    return result ?: value
}

private val TMP_MESSAGE: LogMessage by lazy { LogMessageImpl.Factory.create() }

private inline fun Logger?.tryLog(
    tag: String,
    level: LogLevel,
    messageInitializer: MessageInitializer,
    noinline messagePrinter: MessagePrinter,
    ex: Throwable? = null,
) {
    if (this != null) {
        // Wrap messagePrinter to convert it from crossinline to noinline
        this.log(level, messagePrinter, ex, messageInitializer)
    } else {
        messageInitializer(TMP_MESSAGE)
        val msg = messagePrinter(TMP_MESSAGE)
        when (level) {
            LogLevel.VERBOSE -> Log.v(tag, msg, ex)
            LogLevel.DEBUG -> Log.d(tag, msg, ex)
            LogLevel.INFO -> Log.i(tag, msg, ex)
            LogLevel.WARNING -> Log.w(tag, msg, ex)
            LogLevel.ERROR -> Log.e(tag, msg, ex)
            LogLevel.WTF -> Log.wtf(tag, msg, ex)
        }
    }
}

/** ClockRegistry aggregates providers and plugins */
open class ClockRegistry(
    val context: Context,
    val pluginManager: PluginManager,
    val scope: CoroutineScope,
    val mainDispatcher: CoroutineDispatcher,
    val bgDispatcher: CoroutineDispatcher,
    val isEnabled: Boolean,
    val handleAllUsers: Boolean,
    defaultClockProvider: ClockProvider,
    val fallbackClockId: ClockId = DEFAULT_CLOCK_ID,
    messageBuffer: MessageBuffer? = null,
    val keepAllLoaded: Boolean,
    subTag: String,
    var isTransitClockEnabled: Boolean = false,
) {
    private val TAG = "${ClockRegistry::class.simpleName} ($subTag)"
    interface ClockChangeListener {
        // Called when the active clock changes
        fun onCurrentClockChanged() {}

        // Called when the list of available clocks changes
        fun onAvailableClocksChanged() {}
    }

    private val logger: Logger? = if (messageBuffer != null) Logger(messageBuffer, TAG) else null
    private val availableClocks = ConcurrentHashMap<ClockId, ClockInfo>()
    private val clockChangeListeners = mutableListOf<ClockChangeListener>()
    private val settingObserver =
        object : ContentObserver(null) {
            override fun onChange(
                selfChange: Boolean,
                uris: Collection<Uri>,
                flags: Int,
                userId: Int
            ) {
                scope.launch(bgDispatcher) { querySettings() }
            }
        }

    private val pluginListener =
        object : PluginListener<ClockProviderPlugin> {
            override fun onPluginAttached(
                manager: PluginLifecycleManager<ClockProviderPlugin>
            ): Boolean {
                manager.isDebug = !keepAllLoaded

                if (keepAllLoaded) {
                    // Always load new plugins if requested
                    return true
                }

                val knownClocks = KNOWN_PLUGINS.get(manager.getPackage())
                if (knownClocks == null) {
                    logger.tryLog(
                        TAG,
                        LogLevel.WARNING,
                        { str1 = manager.getPackage() },
                        { "Loading unrecognized clock package: $str1" }
                    )
                    return true
                }

                logger.tryLog(
                    TAG,
                    LogLevel.INFO,
                    { str1 = manager.getPackage() },
                    { "Skipping initial load of known clock package package: $str1" }
                )

                var isClockListChanged = false
                for (metadata in knownClocks) {
                    val id = metadata.clockId
                    val info =
                        availableClocks.concurrentGetOrPut(id, ClockInfo(metadata, null, manager)) {
                            isClockListChanged = true
                            onConnected(it)
                        }

                    if (manager != info.manager) {
                        logger.tryLog(
                            TAG,
                            LogLevel.ERROR,
                            {
                                str1 = id
                                str2 = info.manager.toString()
                                str3 = manager.toString()
                            },
                            {
                                "Clock Id conflict on attach: " +
                                    "$str1 is double registered by $str2 and $str3"
                            }
                        )
                        continue
                    }

                    info.provider = null
                }

                if (isClockListChanged) {
                    triggerOnAvailableClocksChanged()
                }
                verifyLoadedProviders()

                // Load executed via verifyLoadedProviders
                return false
            }

            override fun onPluginLoaded(
                plugin: ClockProviderPlugin,
                pluginContext: Context,
                manager: PluginLifecycleManager<ClockProviderPlugin>
            ) {
                var isClockListChanged = false
                for (clock in plugin.getClocks()) {
                    val id = clock.clockId
                    if (!isTransitClockEnabled && id == "DIGITAL_CLOCK_METRO") {
                        continue
                    }

                    val info =
                        availableClocks.concurrentGetOrPut(id, ClockInfo(clock, plugin, manager)) {
                            isClockListChanged = true
                            onConnected(it)
                        }

                    if (manager != info.manager) {
                        logger.tryLog(
                            TAG,
                            LogLevel.ERROR,
                            {
                                str1 = id
                                str2 = info.manager.toString()
                                str3 = manager.toString()
                            },
                            {
                                "Clock Id conflict on load: " +
                                    "$str1 is double registered by $str2 and $str3"
                            }
                        )
                        manager.unloadPlugin()
                        continue
                    }

                    info.provider = plugin
                    onLoaded(info)
                }

                if (isClockListChanged) {
                    triggerOnAvailableClocksChanged()
                }
                verifyLoadedProviders()
            }

            override fun onPluginUnloaded(
                plugin: ClockProviderPlugin,
                manager: PluginLifecycleManager<ClockProviderPlugin>
            ) {
                for (clock in plugin.getClocks()) {
                    val id = clock.clockId
                    val info = availableClocks[id]
                    if (info?.manager != manager) {
                        logger.tryLog(
                            TAG,
                            LogLevel.ERROR,
                            {
                                str1 = id
                                str2 = info?.manager.toString()
                                str3 = manager.toString()
                            },
                            {
                                "Clock Id conflict on unload: " +
                                    "$str1 is double registered by $str2 and $str3"
                            }
                        )
                        continue
                    }
                    info.provider = null
                    onUnloaded(info)
                }

                verifyLoadedProviders()
            }

            override fun onPluginDetached(manager: PluginLifecycleManager<ClockProviderPlugin>) {
                val removed = mutableListOf<ClockInfo>()
                availableClocks.entries.removeAll {
                    if (it.value.manager != manager) {
                        return@removeAll false
                    }

                    removed.add(it.value)
                    return@removeAll true
                }

                removed.forEach(::onDisconnected)
                if (removed.size > 0) {
                    triggerOnAvailableClocksChanged()
                }
            }
        }

    private val userSwitchObserver =
        object : UserSwitchObserver() {
            override fun onUserSwitchComplete(newUserId: Int) {
                scope.launch(bgDispatcher) { querySettings() }
            }
        }

    // TODO(b/267372164): Migrate to flows
    var settings: ClockSettings? = null
        get() = field
        protected set(value) {
            if (field != value) {
                field = value
                verifyLoadedProviders()
                triggerOnCurrentClockChanged()
            }
        }

    var isRegistered: Boolean = false
        private set

    @OpenForTesting
    open fun querySettings() {
        assertNotMainThread()
        val result =
            try {
                val json =
                    if (handleAllUsers) {
                        Settings.Secure.getStringForUser(
                            context.contentResolver,
                            Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE,
                            ActivityManager.getCurrentUser()
                        )
                    } else {
                        Settings.Secure.getString(
                            context.contentResolver,
                            Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE
                        )
                    }

                ClockSettings.deserialize(json)
            } catch (ex: Exception) {
                logger.tryLog(TAG, LogLevel.ERROR, {}, { "Failed to parse clock settings" }, ex)
                null
            }
        settings = result
    }

    @OpenForTesting
    open fun applySettings(value: ClockSettings?) {
        assertNotMainThread()

        try {
            value?.metadata?.put(KEY_TIMESTAMP, System.currentTimeMillis())

            val json = ClockSettings.serialize(value)
            if (handleAllUsers) {
                Settings.Secure.putStringForUser(
                    context.contentResolver,
                    Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE,
                    json,
                    ActivityManager.getCurrentUser()
                )
            } else {
                Settings.Secure.putString(
                    context.contentResolver,
                    Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE,
                    json
                )
            }
        } catch (ex: Exception) {
            logger.tryLog(TAG, LogLevel.ERROR, {}, { "Failed to set clock settings" }, ex)
        }
        settings = value
    }

    @OpenForTesting
    protected open fun assertMainThread() {
        Assert.isMainThread()
    }

    @OpenForTesting
    protected open fun assertNotMainThread() {
        Assert.isNotMainThread()
    }

    private var isClockChanged = AtomicBoolean(false)
    private fun triggerOnCurrentClockChanged() {
        val shouldSchedule = isClockChanged.compareAndSet(false, true)
        if (!shouldSchedule) {
            return
        }

        scope.launch(mainDispatcher) {
            assertMainThread()
            isClockChanged.set(false)
            clockChangeListeners.forEach { it.onCurrentClockChanged() }
        }
    }

    private var isClockListChanged = AtomicBoolean(false)
    private fun triggerOnAvailableClocksChanged() {
        val shouldSchedule = isClockListChanged.compareAndSet(false, true)
        if (!shouldSchedule) {
            return
        }

        scope.launch(mainDispatcher) {
            assertMainThread()
            isClockListChanged.set(false)
            clockChangeListeners.forEach { it.onAvailableClocksChanged() }
        }
    }

    public suspend fun mutateSetting(mutator: (ClockSettings) -> ClockSettings) {
        withContext(bgDispatcher) { applySettings(mutator(settings ?: ClockSettings())) }
    }

    var currentClockId: ClockId
        get() = settings?.clockId ?: fallbackClockId
        set(value) {
            scope.launch(bgDispatcher) { mutateSetting { it.copy(clockId = value) } }
        }

    var seedColor: Int?
        get() = settings?.seedColor
        set(value) {
            scope.launch(bgDispatcher) { mutateSetting { it.copy(seedColor = value) } }
        }

    // Returns currentClockId if clock is connected, otherwise DEFAULT_CLOCK_ID. Since this
    // is dependent on which clocks are connected, it may change when a clock is installed or
    // removed from the device (unlike currentClockId).
    // TODO: Merge w/ CurrentClockId when we convert to a flow. We shouldn't need both behaviors.
    val activeClockId: String
        get() {
            if (!availableClocks.containsKey(currentClockId)) {
                return DEFAULT_CLOCK_ID
            }
            return currentClockId
        }

    init {
        // Register default clock designs
        for (clock in defaultClockProvider.getClocks()) {
            availableClocks[clock.clockId] = ClockInfo(clock, defaultClockProvider, null)
        }

        // Something has gone terribly wrong if the default clock isn't present
        if (!availableClocks.containsKey(DEFAULT_CLOCK_ID)) {
            throw IllegalArgumentException(
                "$defaultClockProvider did not register clock at $DEFAULT_CLOCK_ID"
            )
        }
    }

    fun registerListeners() {
        if (!isEnabled || isRegistered) {
            return
        }

        isRegistered = true

        pluginManager.addPluginListener(
            pluginListener,
            ClockProviderPlugin::class.java,
            /*allowMultiple=*/ true
        )

        scope.launch(bgDispatcher) { querySettings() }
        if (handleAllUsers) {
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE),
                /*notifyForDescendants=*/ false,
                settingObserver,
                UserHandle.USER_ALL
            )

            ActivityManager.getService().registerUserSwitchObserver(userSwitchObserver, TAG)
        } else {
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE),
                /*notifyForDescendants=*/ false,
                settingObserver
            )
        }
    }

    fun unregisterListeners() {
        if (!isRegistered) {
            return
        }

        isRegistered = false

        pluginManager.removePluginListener(pluginListener)
        context.contentResolver.unregisterContentObserver(settingObserver)
        if (handleAllUsers) {
            ActivityManager.getService().unregisterUserSwitchObserver(userSwitchObserver)
        }
    }

    private var isQueued = AtomicBoolean(false)
    fun verifyLoadedProviders() {
        Log.i(TAG, Thread.currentThread().getStackTrace().toString())
        val shouldSchedule = isQueued.compareAndSet(false, true)
        if (!shouldSchedule) {
            logger.tryLog(
                TAG,
                LogLevel.VERBOSE,
                {},
                { "verifyLoadedProviders: shouldSchedule=false" }
            )
            return
        }

        scope.launch(bgDispatcher) {
            // TODO(b/267372164): Use better threading approach when converting to flows
            synchronized(availableClocks) {
                isQueued.set(false)
                if (keepAllLoaded) {
                    logger.tryLog(
                        TAG,
                        LogLevel.INFO,
                        {},
                        { "verifyLoadedProviders: keepAllLoaded=true" }
                    )
                    // Enforce that all plugins are loaded if requested
                    for ((_, info) in availableClocks) {
                        info.manager?.loadPlugin()
                    }
                    return@launch
                }

                val currentClock = availableClocks[currentClockId]
                if (currentClock == null) {
                    logger.tryLog(
                        TAG,
                        LogLevel.INFO,
                        {},
                        { "verifyLoadedProviders: currentClock=null" }
                    )
                    // Current Clock missing, load no plugins and use default
                    for ((_, info) in availableClocks) {
                        info.manager?.unloadPlugin()
                    }
                    return@launch
                }

                logger.tryLog(
                    TAG,
                    LogLevel.INFO,
                    {},
                    { "verifyLoadedProviders: load currentClock" }
                )
                val currentManager = currentClock.manager
                currentManager?.loadPlugin()

                for ((_, info) in availableClocks) {
                    val manager = info.manager
                    if (manager != null && currentManager != manager) {
                        manager.unloadPlugin()
                    }
                }
            }
        }
    }

    private fun onConnected(info: ClockInfo) {
        val isCurrent = currentClockId == info.metadata.clockId
        logger.tryLog(
            TAG,
            if (isCurrent) LogLevel.INFO else LogLevel.DEBUG,
            {
                str1 = info.metadata.clockId
                str2 = info.manager.toString()
                bool1 = isCurrent
            },
            { "Connected $str1 @$str2" + if (bool1) " (Current Clock)" else "" }
        )
    }

    private fun onLoaded(info: ClockInfo) {
        val isCurrent = currentClockId == info.metadata.clockId
        logger.tryLog(
            TAG,
            if (isCurrent) LogLevel.INFO else LogLevel.DEBUG,
            {
                str1 = info.metadata.clockId
                str2 = info.manager.toString()
                bool1 = isCurrent
            },
            { "Loaded $str1 @$str2" + if (bool1) " (Current Clock)" else "" }
        )

        if (isCurrent) {
            triggerOnCurrentClockChanged()
        }
    }

    private fun onUnloaded(info: ClockInfo) {
        val isCurrent = currentClockId == info.metadata.clockId
        logger.tryLog(
            TAG,
            if (isCurrent) LogLevel.WARNING else LogLevel.DEBUG,
            {
                str1 = info.metadata.clockId
                str2 = info.manager.toString()
                bool1 = isCurrent
            },
            { "Unloaded $str1 @$str2" + if (bool1) " (Current Clock)" else "" }
        )

        if (isCurrent) {
            triggerOnCurrentClockChanged()
        }
    }

    private fun onDisconnected(info: ClockInfo) {
        val isCurrent = currentClockId == info.metadata.clockId
        logger.tryLog(
            TAG,
            if (isCurrent) LogLevel.INFO else LogLevel.DEBUG,
            {
                str1 = info.metadata.clockId
                str2 = info.manager.toString()
                bool1 = isCurrent
            },
            { "Disconnected $str1 @$str2" + if (bool1) " (Current Clock)" else "" }
        )
    }

    fun getClocks(): List<ClockMetadata> {
        if (!isEnabled) {
            return listOf(availableClocks[DEFAULT_CLOCK_ID]!!.metadata)
        }
        return availableClocks.map { (_, clock) -> clock.metadata }
    }

    fun getClockThumbnail(clockId: ClockId): Drawable? =
        availableClocks[clockId]?.provider?.getClockThumbnail(clockId)

    fun createExampleClock(clockId: ClockId): ClockController? = createClock(clockId)

    /**
     * Adds [listener] to receive future clock changes.
     *
     * Calling from main thread to make sure the access is thread safe.
     */
    fun registerClockChangeListener(listener: ClockChangeListener) {
        assertMainThread()
        clockChangeListeners.add(listener)
    }

    /**
     * Removes [listener] from future clock changes.
     *
     * Calling from main thread to make sure the access is thread safe.
     */
    fun unregisterClockChangeListener(listener: ClockChangeListener) {
        assertMainThread()
        clockChangeListeners.remove(listener)
    }

    fun createCurrentClock(): ClockController {
        val clockId = currentClockId
        if (isEnabled && clockId.isNotEmpty()) {
            val clock = createClock(clockId)
            if (clock != null) {
                logger.tryLog(TAG, LogLevel.INFO, { str1 = clockId }, { "Rendering clock $str1" })
                return clock
            } else if (availableClocks.containsKey(clockId)) {
                logger.tryLog(
                    TAG,
                    LogLevel.WARNING,
                    { str1 = clockId },
                    { "Clock $str1 not loaded; using default" }
                )
                verifyLoadedProviders()
            } else {
                logger.tryLog(
                    TAG,
                    LogLevel.ERROR,
                    { str1 = clockId },
                    { "Clock $str1 not found; using default" }
                )
            }
        }

        return createClock(DEFAULT_CLOCK_ID)!!
    }

    private fun createClock(targetClockId: ClockId): ClockController? {
        var settings = this.settings ?: ClockSettings()
        if (targetClockId != settings.clockId) {
            settings = settings.copy(clockId = targetClockId)
        }
        return availableClocks[targetClockId]?.provider?.createClock(settings)
    }

    fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("ClockRegistry:")
        pw.println("  settings = $settings")
        for ((id, info) in availableClocks) {
            pw.println("  availableClocks[$id] = $info")
        }
    }

    private data class ClockInfo(
        val metadata: ClockMetadata,
        var provider: ClockProvider?,
        val manager: PluginLifecycleManager<ClockProviderPlugin>?,
    )
}
