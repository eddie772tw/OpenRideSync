package org.openridesync.app.vehicles

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openridesync.app.OpenRideSyncApplication
import org.openridesync.app.auth.AuthSession
import org.openridesync.app.auth.AuthStatus
import org.openridesync.app.honda.HondaSessionRead
import org.openridesync.app.guides.*
import org.openridesync.diagnostics.DiagnosticJournal.*
import org.openridesync.honda.VehicleStore
import org.openridesync.honda.PairedVehicleStore
import org.openridesync.honda.wire.HondaWire.*
import java.io.File
import java.security.MessageDigest

data class VehicleState(
    val browsingCatalog: Boolean = false,
    val catalogLoaded: Boolean = false,
    val seriesLoaded: Boolean = false,
    val cancelled: Boolean = false,
    val detailYear: Int? = null,
    val detailVehicleMac: String? = null,
    val catalogs: List<ModelCatalog> = emptyList(),
    val models: List<Model> = emptyList(),
    val activeSeries: ModelCatalog? = null,
    val detail: Model? = null,
    val guide: VehicleGuide? = null,
    val selection: VehicleStore.Selection? = null,
    val ownCountry: Boolean = true,
    val countryCode: String? = null,
    val busy: Boolean = false,
    val cached: Boolean = false,
    val errorCode: String? = null,
    val storageError: Boolean = false,
    val selectionSaved: Boolean = false,
    val pairedVehicles: List<PairedVehicleStore.Vehicle> = emptyList(),
    val garageLoading: Boolean = false,
    val garageLoaded: Boolean = false,
    val garageError: Boolean = false,
    val garageMutationBusy: Boolean = false,
    val garageMutationError: Boolean = false,
)

/** Catalog browsing and local model choice only; no BLE writer or pairing side effects. */
class VehicleViewModel(application: Application) : AndroidViewModel(application) {
    val session = AuthSession.get()
    private val app = application as OpenRideSyncApplication
    private val mutableState = MutableStateFlow(VehicleState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    private var garageJob: Job? = null
    private var garageGeneration = 0L
    private var catalogReturn: VehicleState? = null
    private var detailReturn: VehicleState? = null
    private val draftYears = mutableMapOf<String, Int>()
    internal val imageRequests = VehicleImageRequests()
    private val garageMutation = GarageMutation(viewModelScope,
        identity = { session.currentUser()?.uid?.takeIf { session.state.value.status == AuthStatus.SIGNED_IN }
            ?.let { GarageIdentity(it, session.revision) } },
        store = { identity -> pairedStore(identity.uid) })

    private fun pairedStore(uid: String): PairedVehicleStore {
        val hash = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return PairedVehicleStore(File(app.filesDir, "paired-vehicles/$hash"))
    }

    internal fun changeSavedVehicle(change: GarageChange, vehicle: PairedVehicleStore.Vehicle,
        allowed: () -> Boolean) {
        if (state.value.garageMutationBusy || state.value.garageMutationError || state.value.garageLoading ||
            state.value.pairedVehicles.none { it.mac == vehicle.mac }) return
        garageMutation.start(change, vehicle.mac, allowed,
            onBusy = { mutableState.value = state.value.copy(garageMutationBusy = true, garageMutationError = false) },
            onSuccess = { vehicles ->
                mutableState.value = state.value.copy(pairedVehicles = vehicles, garageLoaded = true, garageError = false,
                    garageMutationBusy = false, garageMutationError = false,
                    detailVehicleMac = state.value.detailVehicleMac?.takeIf { mac -> vehicles.any { it.mac == mac } })
            },
            onFailure = { mutableState.value = state.value.copy(garageMutationBusy = false, garageMutationError = true) })
    }

    init {
        viewModelScope.launch {
            session.state.collect {
                cancel()
                garageGeneration++
                garageJob?.cancel()
                garageMutation.cancel()
                catalogReturn = null; detailReturn = null; draftYears.clear(); imageRequests.reset()
                mutableState.value = VehicleState()
                if (it.status == AuthStatus.SIGNED_IN) {
                    reloadPairedVehicles()
                }
            }
        }
    }

    /** Local garage is independent of Honda configuration, RPCs and catalog cache failures. */
    fun reloadPairedVehicles() {
        if (state.value.garageMutationBusy) return
        val uid = session.currentUser()?.uid ?: return
        if (session.state.value.status != AuthStatus.SIGNED_IN) return
        val revision = session.revision
        val current = ++garageGeneration
        garageJob?.cancel()
        fun ownsResult() = current == garageGeneration && session.revision == revision &&
            session.currentUser()?.uid == uid && session.state.value.status == AuthStatus.SIGNED_IN
        mutableState.value = state.value.copy(garageLoading = true, garageError = false)
        garageJob = viewModelScope.launch {
            try {
                val vehicles = withContext(Dispatchers.IO) {
                    VehicleWrites.commit { pairedStore(uid).read() }
                }
                ensureActive()
                if (ownsResult()) mutableState.value = state.value.copy(pairedVehicles = vehicles,
                    garageLoading = false, garageLoaded = true, garageError = false, garageMutationError = false)
            } catch (_: CancellationException) { /* A later read or identity owns the result. */ }
            catch (_: java.io.IOException) {
                if (ownsResult()) mutableState.value = state.value.copy(garageLoading = false, garageError = true)
            }
        }
    }

    fun cancel() {
        garageMutation.cancel()
        if (state.value.garageMutationBusy) mutableState.value = state.value.copy(
            garageMutationBusy = false, garageMutationError = true)
        generation++
        job?.cancel()
        job = null
        mutableState.value = state.value.copy(busy = false, cancelled = state.value.cancelled || state.value.busy)
    }

    fun back(): Boolean {
        val current = state.value
        val parent = vehicleBackPage(current, catalogReturn, detailReturn)
        cancel()
        if (parent == null) return false
        mutableState.value = restoreVehiclePage(parent, state.value)
        if (current.guide == null && current.detail != null) detailReturn = null
        if (current.guide == null && current.detail == null && current.activeSeries != null) catalogReturn = null
        return true
    }

    /** Opening a catalog is explicit; returning to an already visited list does not re-fetch. */
    fun openCatalog() {
        if (session.state.value.status != AuthStatus.SIGNED_IN || state.value.busy) return
        mutableState.value = state.value.copy(browsingCatalog = true)
        if (vehicleCatalogNeedsLoad(state.value)) refreshCatalog()
    }

    fun selectYear(year: Int) {
        val model = state.value.detail ?: return
        if (year in model.modelYearsList) {
            draftYears[model.modelLabel] = year
            mutableState.value = state.value.copy(detailYear = year, selectionSaved = false)
        }
    }

    fun changeScope(ownCountry: Boolean) {
        cancel()
        catalogReturn = null; detailReturn = null
        mutableState.value = VehicleState(browsingCatalog = true, ownCountry = ownCountry, selection = state.value.selection,
            garageMutationBusy = state.value.garageMutationBusy, garageMutationError = state.value.garageMutationError,
            pairedVehicles = state.value.pairedVehicles, garageLoading = state.value.garageLoading, garageLoaded = state.value.garageLoaded, garageError = state.value.garageError)
        refreshCatalog()
    }

    fun refresh() {
        if (state.value.busy) return
        if (vehiclePage(state.value) == VehiclePage.GARAGE) { reloadPairedVehicles(); return }
        state.value.guide?.let { openGuide(it.kind); return }
        imageRequests.reset()
        state.value.activeSeries?.let(::openSeries) ?: refreshCatalog()
    }

    fun openGarage() {
        cancel()
        val parent = catalogReturn ?: detailReturn?.takeIf { it.activeSeries == null } ?: state.value
        mutableState.value = restoreVehiclePage(parent, state.value).copy(browsingCatalog = false,
            activeSeries = null, detail = null, detailVehicleMac = null, detailYear = null, guide = null, models = emptyList())
        catalogReturn = null; detailReturn = null
        reloadPairedVehicles()
    }

    private fun refreshCatalog() {
        if (session.state.value.status != AuthStatus.SIGNED_IN) return
        val own = state.value.ownCountry
        cancel()
        mutableState.value = state.value.copy(browsingCatalog = true, activeSeries = null, detail = null, detailVehicleMac = null, detailYear = null, guide = null, models = emptyList(),
            busy = true, cancelled = false, errorCode = null, selectionSaved = false, storageError = false)
        load { store, reader, publish ->
            try {
                val cached = store.readCatalog(own)
                if (cached != null) publish { it.copy(catalogs = cached.value.modelCatalogsList,
                    countryCode = cached.countryCode, cached = true, catalogLoaded = true) }
                else publish { it.copy(cached = it.catalogLoaded) }
            } catch (_: java.io.IOException) {
                publish { it.copy(cached = it.catalogLoaded, storageError = true) }
            }
            try {
                val selection = store.readSelection()
                publish { it.copy(selection = selection) }
            } catch (_: java.io.IOException) { publish { it.copy(selection = null, storageError = true) } }
            val country = runInterruptible(Dispatchers.IO) { reader.read(Event.HONDA_GET_COUNTRY) { client, key, token ->
                client.getCountryOfResidence(GetCountryOfResidenceRequest.getDefaultInstance(), key, token)
            } }.code
            check(country.matches(Regex("[A-Z]{2}")))
            publish { it.copy(countryCode = country,
                catalogs = if (own && it.countryCode != country) emptyList() else it.catalogs,
                cached = it.cached && (!own || it.countryCode == country),
                catalogLoaded = it.catalogLoaded && (!own || it.countryCode == country)) }
            val response = runInterruptible(Dispatchers.IO) { reader.read(Event.HONDA_LIST_MODEL_CATALOGS) { client, key, token ->
                client.listModelCatalogs(catalogRequest(own), key, token)
            } }
            VehicleStore.validateCatalog(response)
            var stored = true
            try { VehicleWrites.commit { store.writeCatalog(own, country, response) } }
            catch (_: java.io.IOException) { stored = false }
            publish { it.copy(catalogs = response.modelCatalogsList, countryCode = country, cached = false, catalogLoaded = true, storageError = it.storageError || !stored) }
        }
    }

    fun openSeries(catalog: ModelCatalog) {
        if (session.state.value.status != AuthStatus.SIGNED_IN || state.value.busy) return
        // OEM expands the complete series regardless of the catalog's residence filter.
        val own = false
        val country = state.value.countryCode ?: return
        if (state.value.activeSeries == null) catalogReturn = state.value
        cancel()
        mutableState.value = state.value.copy(browsingCatalog = true, activeSeries = catalog, detail = null, detailVehicleMac = null, detailYear = null, guide = null,
            models = if (state.value.activeSeries == catalog) state.value.models else emptyList(),
            seriesLoaded = state.value.activeSeries == catalog && state.value.seriesLoaded,
            busy = true, cancelled = false, errorCode = null, selectionSaved = false,
            cached = state.value.activeSeries == catalog && state.value.models.isNotEmpty(), storageError = false)
        val label = catalog.model.modelSeriesLabel
        load { store, reader, publish ->
            try {
                store.readSeries(label, own, country)?.let { cached ->
                    publish { it.copy(models = orderedModels(cached.value), cached = true, seriesLoaded = true) }
                }
            } catch (_: java.io.IOException) { publish { it.copy(storageError = true) } }
            val response = runInterruptible(Dispatchers.IO) { reader.read(Event.HONDA_LIST_MODEL_SERIES) { client, key, token ->
                client.listModelSeries(seriesRequest(label), key, token)
            } }
            VehicleStore.validateSeries(response, label)
            var stored = true
            try { VehicleWrites.commit { store.writeSeries(label, own, country, response) } }
            catch (_: java.io.IOException) { stored = false }
            publish { it.copy(models = orderedModels(response), cached = false, seriesLoaded = true, storageError = !stored) }
        }
    }

    fun showModel(model: Model, year: Int? = null) {
        if (!canOpenVehicleDetail(state.value)) return
        detailReturn = state.value
        cancel()
        val previous = year ?: draftYears[model.modelLabel] ?: state.value.selection?.takeIf { it.model.modelLabel == model.modelLabel }?.year
        mutableState.value = state.value.copy(detail = model, detailVehicleMac = null, detailYear = if (year == 0) 0 else selectedYear(model, previous), guide = null,
            selectionSaved = false, cancelled = false, errorCode = null)
    }

    /** Keep the physical vehicle identity: two saved bikes can share model and year. */
    fun showSavedVehicle(vehicle: PairedVehicleStore.Vehicle) {
        if (!canOpenVehicleDetail(state.value)) return
        val saved = state.value.pairedVehicles.singleOrNull { it.mac == vehicle.mac } ?: return
        showModel(saved.model, saved.year)
        mutableState.value = state.value.copy(detailVehicleMac = saved.mac)
    }

    fun selectCatalog(catalog: ModelCatalog) {
        if (catalog.modelCount <= 1) showModel(catalog.model) else openSeries(catalog)
    }

    fun openGuide(kind: GuideKind) {
        if (session.state.value.status != AuthStatus.SIGNED_IN || state.value.busy) return
        val model = state.value.detail ?: return
        val language = (app.resources.configuration.locales[0] ?: java.util.Locale.getDefault()).toLanguageTag()
        cancel()
        mutableState.value = state.value.copy(guide = VehicleGuide(kind), busy = true, cancelled = false, errorCode = null)
        load { _, reader, publish ->
            val url = runInterruptible(Dispatchers.IO) {
                when (kind) {
                    GuideKind.SETUP -> reader.read(Event.HONDA_GET_SETUP_PAGE) { client, key, token ->
                        client.getModelSetupPageUrl(setupRequest(model.modelLabel, language), key, token).url
                    }
                    GuideKind.TUTORIAL -> reader.read(Event.HONDA_GET_TUTORIAL_PAGE) { client, key, token ->
                        client.getModelTutorialPageUrl(tutorialRequest(model.modelLabel, language), key, token).url
                    }
                }
            }
            app.journal?.record(Component.MEDIA, reader.operationId, Event.GUIDE_DOWNLOAD, Outcome.STARTED, ErrorCode.NONE)
            val document = try {
                val loaded = GuideDownload.fetch(url)
                (if (kind == GuideKind.SETUP) setupTemplate(loaded) else loaded).also {
                    app.journal?.record(Component.MEDIA, reader.operationId, Event.GUIDE_DOWNLOAD, Outcome.SUCCEEDED, ErrorCode.NONE)
                }
            } catch (error: Exception) {
                val code = HondaSessionRead.errorCode(error)
                app.journal?.record(Component.MEDIA, reader.operationId, Event.GUIDE_DOWNLOAD,
                    if (error is CancellationException) Outcome.CANCELLED else Outcome.FAILED, HondaSessionRead.diagnosticError(code))
                throw error
            }
            publish { it.copy(guide = VehicleGuide(kind, document, reader.operationId)) }
        }
    }

    fun guideRendered(operationId: String?, succeeded: Boolean, error: ErrorCode = ErrorCode.IO) {
        if (operationId == null || operationId != state.value.guide?.operationId) return
        app.journal?.record(Component.UI, operationId, Event.STATE_CHANGE,
            if (succeeded) Outcome.SUCCEEDED else Outcome.FAILED, if (succeeded) ErrorCode.NONE else error)
    }

    fun finishGuide(operationId: String?) {
        val guide = state.value.guide ?: return
        if (operationId == null || operationId != guide.operationId || guide.kind != GuideKind.TUTORIAL || guide.document == null) return
        // A document action closes this preview; it does not complete pairing or advance DFU state.
        app.journal?.record(Component.UI, guide.operationId, Event.STOP, Outcome.OBSERVED, ErrorCode.NONE)
        back()
    }

    fun saveSelection(model: Model, year: Int) {
        if (session.state.value.status != AuthStatus.SIGNED_IN || state.value.busy || !canSaveVehiclePreference(model, year)) return
        mutableState.value = state.value.copy(busy = true, cancelled = false, selectionSaved = false, errorCode = null)
        load { store, _, publish ->
            try {
                val selection = VehicleWrites.commit {
                    store.writeSelection(model, year)
                    checkNotNull(store.readSelection())
                }
                app.journal?.record(Component.UI, null, Event.STATE_CHANGE, Outcome.SUCCEEDED, ErrorCode.NONE)
                publish { it.copy(selection = selection, selectionSaved = true, storageError = false) }
            } catch (_: java.io.IOException) {
                app.journal?.record(Component.UI, null, Event.STATE_CHANGE, Outcome.FAILED, ErrorCode.IO)
                publish { it.copy(storageError = true) }
            }
        }
    }

    override fun onCleared() {
        imageRequests.close()
        super.onCleared()
    }

    private fun load(block: suspend (VehicleStore, HondaSessionRead, suspend ((VehicleState) -> VehicleState) -> Unit) -> Unit) {
        val uid = session.currentUser()?.uid ?: return
        val revision = session.revision
        val current = ++generation
        fun currentIdentity() = current == generation && session.revision == revision &&
            session.currentUser()?.uid == uid && session.state.value.status == AuthStatus.SIGNED_IN
        val operation = app.journal?.newOperation() ?: java.util.UUID.randomUUID().toString()
        job = viewModelScope.launch {
            try {
                // Files and blocking RPCs run on IO; publish only if the initiating identity still owns this screen.
                withContext(Dispatchers.IO) {
                    val hash = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
                    val store = VehicleStore(File(app.filesDir, "vehicles/$hash"))
                    HondaSessionRead(session, app.journal, operation, uid, revision).use { reader ->
                        block(store, reader) { transform ->
                            withContext(Dispatchers.Main) { if (currentIdentity()) mutableState.value = transform(state.value) }
                        }
                    }
                }
                ensureActive()
                if (currentIdentity()) mutableState.value = state.value.copy(busy = false)
            } catch (_: CancellationException) {
                app.journal?.record(Component.UI, operation, Event.STOP, Outcome.CANCELLED, ErrorCode.CANCELLED)
            } catch (error: Exception) {
                val code = HondaSessionRead.errorCode(error)
                app.journal?.record(Component.UI, operation, Event.STATE_CHANGE, Outcome.FAILED, HondaSessionRead.diagnosticError(code))
                if (currentIdentity()) mutableState.value = state.value.copy(busy = false, errorCode = code)
            }
        }
    }
}

/** Short file commits are serialized even when an older operation is cancelled mid-write. */
internal object VehicleWrites {
    private val mutex = Mutex()
    suspend fun <T> commit(action: () -> T): T = mutex.withLock {
        currentCoroutineContext().ensureActive()
        action()
    }
}

// Catalog browsing includes known API generations. It never enables a transport or claims vehicle support.
internal fun catalogRequest(ownCountry: Boolean): ListModelCatalogsRequest = ListModelCatalogsRequest.newBuilder()
    .addAllMeterGenerations(listOf(1, 2, 3)).setOnlyOwnCountry(ownCountry).build()

internal fun seriesRequest(label: String): ListModelSeriesRequest = ListModelSeriesRequest.newBuilder()
    .addAllMeterGenerations(listOf(1, 2, 3)).addModelSeriesLabels(label).build()

internal fun orderedModels(response: ListModelSeriesResponse): List<Model> = response.modelSeriesListList
    .flatMap { it.modelsList }.sortedByDescending { it.modelYearsList.firstOrNull() ?: 0 }

internal fun modelName(model: Model): String = model.modelName.displayName.fullName.ifBlank {
    model.modelName.defaultName.fullName.ifBlank { model.modelLabel }
}

internal fun imageId(model: Model): String? = (model.imagesList.firstOrNull { it.description.isDefault }
    ?: model.imagesList.firstOrNull())?.id?.takeIf { it.isNotBlank() }

internal fun selectedYear(model: Model?, previousYear: Int?): Int =
    previousYear?.takeIf { it in model?.modelYearsList.orEmpty() } ?: model?.modelYearsList?.firstOrNull() ?: 0
