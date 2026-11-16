package com.chymaster.octopusagiledashboard.data.repository

import android.util.Log
import com.chymaster.octopusagiledashboard.core.network.OctopusGraphQLClient
import com.chymaster.octopusagiledashboard.core.util.Constants
import com.chymaster.octopusagiledashboard.data.mapper.TariffAgreementMapper
import com.chymaster.octopusagiledashboard.data.mapper.selectMeterPoint
import com.chymaster.octopusagiledashboard.data.mapper.toDomainAgreements
import com.chymaster.octopusagiledashboard.data.prefs.UserPreferencesRepository
import com.chymaster.octopusagiledashboard.data.remote.api.OctopusApiService
import com.chymaster.octopusagiledashboard.domain.model.ApiError
import com.chymaster.octopusagiledashboard.domain.model.TariffAgreement
import com.chymaster.octopusagiledashboard.domain.model.TariffSchedule
import com.chymaster.octopusagiledashboard.domain.model.TariffSource
import com.chymaster.octopusagiledashboard.domain.model.TariffConfig
import com.chymaster.octopusagiledashboard.domain.model.toUserMessage
import com.chymaster.octopusagiledashboard.domain.tariff.TariffScheduleResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for "which tariff applied when".
 *
 * The **effective** schedule is a derived flow over four inputs:
 *  - the last detected schedule (persisted in DataStore, survives restarts and
 *    works offline);
 *  - the auto-detect preference (the manual override pins one product code);
 *  - demo mode (no credentials → one synthetic agreement);
 *  - the configured product code + GSP (the fallback).
 *
 * Because the state is derived, toggling the override or entering demo mode
 * needs no cache invalidation: every price read is tariff-scoped, so rows
 * belonging to a tariff that is no longer selected are simply not read.
 */
@Singleton
class TariffScheduleRepository @Inject constructor(
    private val apiService: OctopusApiService,
    private val graphQLClient: OctopusGraphQLClient,
    private val preferencesRepository: UserPreferencesRepository,
    private val json: Json
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()

    /** Last time a detection attempt was made (successful or not). */
    @Volatile
    private var lastAttemptAt: Instant? = null

    private val persistedFlow: Flow<TariffSchedule?> = combine(
        preferencesRepository.tariffScheduleJsonFlow,
        preferencesRepository.tariffScheduleFetchedAtFlow,
        preferencesRepository.tariffScheduleIdentityFlow,
        preferencesRepository.mpanFlow,
        preferencesRepository.accountNumberFlow
    ) { rawJson, fetchedAt, identity, mpan, account ->
        val agreements = TariffAgreementMapper.decode(rawJson, json) ?: return@combine null
        if (agreements.isEmpty()) return@combine null

        // Identity guard: a schedule detected for other credentials is stale.
        val (storedMpan, storedAccount) = identity
        if (!storedMpan.isNullOrBlank() && !mpan.isNullOrBlank() && storedMpan != mpan) {
            return@combine null
        }
        if (!storedAccount.isNullOrBlank() && !account.isNullOrBlank() && storedAccount != account) {
            return@combine null
        }

        TariffSchedule(
            agreements = agreements,
            source = TariffSource.AUTO,
            fetchedAt = fetchedAt.takeIf { it > 0 }?.let(Instant::ofEpochMilli),
            mpan = mpan,
            accountNumber = account
        )
    }

    /**
     * The raw detected history (ignores the manual override / demo mode), for
     * the Settings screen to show what was found on the account.
     */
    val detected: StateFlow<List<TariffAgreement>> = persistedFlow
        .map { it?.agreements.orEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * The schedule in force: detected history when available and enabled,
     * otherwise the manual override / demo / fallback single agreement.
     */
    val state: StateFlow<TariffSchedule> = combine(
        persistedFlow,
        preferencesRepository.autoDetectTariffFlow,
        preferencesRepository.isDemoMode,
        preferencesRepository.tariffConfig,
        preferencesRepository.mpanFlow
    ) { detected, autoDetect, isDemoMode, config, mpan ->
        when {
            isDemoMode -> singleAgreement(config, TariffSource.DEMO, mpan)
            !autoDetect -> singleAgreement(config, TariffSource.MANUAL_OVERRIDE, mpan)
            detected != null -> detected.copy(mpan = mpan ?: detected.mpan)
            else -> singleAgreement(
                config,
                TariffSource.FALLBACK,
                mpan,
                error = "Your tariff could not be detected — using the product code from Settings."
            )
        }
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = TariffSchedule(emptyList(), TariffSource.FALLBACK)
    )

    /**
     * Current schedule, refreshing first when it is missing or stale.
     *
     * Waits for the derived flow's first real value: the `stateIn` initial
     * value is an empty placeholder, and callers must never see it.
     */
    suspend fun get(): TariffSchedule {
        val current = state.first { it.agreements.isNotEmpty() }
        if (needsFetch(current)) {
            refresh()
        }
        return state.value
    }

    /**
     * Detect the tariff history from the account.
     *
     * @param force bypass the TTL / retry throttle (pull-to-refresh, Settings).
     */
    suspend fun refresh(force: Boolean = false): Result<TariffSchedule> {
        val snapshot = state.value
        if (!force && !needsFetch(snapshot)) return Result.success(snapshot)

        return refreshMutex.withLock {
            val now = Instant.now()
            if (!force) {
                val last = lastAttemptAt
                if (last != null &&
                    Duration.between(last, now).seconds < Constants.TARIFF_SCHEDULE_RETRY_SECONDS
                ) {
                    return@withLock Result.success(state.value)
                }
            }
            lastAttemptAt = now

            if (preferencesRepository.isDemoMode.first()) {
                // No credentials — the demo agreement is already the effective one.
                return@withLock Result.success(state.value)
            }

            val mpan = preferencesRepository.mpanFlow.first()
            val savedAccount = preferencesRepository.accountNumberFlow.first()

            var lastError: String? = null

            // 1. The account endpoint carries every meter point's agreements.
            // Try the saved account first; fall back to the viewer's accounts
            // when it was never saved or does not hold this MPAN.
            suspend fun tryAccounts(accounts: List<String>): Result<TariffSchedule>? {
                for (accountNumber in accounts.distinct().filter { it.isNotBlank() }) {
                    try {
                        val response = apiService.getAccount(accountNumber)
                        if (response.isSuccessful) {
                            val meterPoint = response.body()?.selectMeterPoint(mpan)
                            val agreements =
                                meterPoint?.agreements?.toDomainAgreements().orEmpty()
                            if (agreements.isNotEmpty()) {
                                preferencesRepository.saveAccountNumber(accountNumber)
                                return persist(agreements, mpan, accountNumber)
                            }
                            lastError = "No tariff agreements found on account $accountNumber."
                        } else {
                            lastError = ApiError.fromHttpCode(response.code()).toUserMessage()
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastError = e.toUserMessage()
                    }
                }
                return null
            }

            if (!savedAccount.isNullOrBlank()) {
                tryAccounts(listOf(savedAccount))?.let { return@withLock it }
            }
            tryAccounts(discoverAccountNumbers())?.let { return@withLock it }

            // 2. Fall back to the meter-point endpoint (some API versions
            // include agreements here).
            if (!mpan.isNullOrBlank()) {
                try {
                    val response = apiService.getMeterPoint(mpan)
                    if (response.isSuccessful) {
                        val agreements = response.body()?.agreements?.toDomainAgreements().orEmpty()
                        if (agreements.isNotEmpty()) {
                            return@withLock persist(agreements, mpan, savedAccount)
                        }
                        lastError = lastError ?: "No tariff agreements found for this meter point."
                    } else if (lastError == null) {
                        lastError = ApiError.fromHttpCode(response.code()).toUserMessage()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (lastError == null) lastError = e.toUserMessage()
                }
            }

            Log.w(TAG, "Tariff detection failed: $lastError")
            Result.success(
                state.value.copy(
                    source = TariffSource.FALLBACK,
                    error = lastError ?: "Your tariff could not be detected."
                )
            )
        }
    }

    /** Detect now regardless of the override toggle (used by Settings). */
    suspend fun detectNow(): Result<TariffSchedule> = refresh(force = true)

    /**
     * Resolves the viewer's account numbers via the Kraken query. Used only
     * when the user has never saved an account number; each candidate account
     * is then probed for a meter point matching the configured MPAN.
     */
    private suspend fun discoverAccountNumbers(): List<String> {
        return try {
            val result = graphQLClient.execute("{ viewer { accounts { number } } }")
            val accounts = result.getOrNull()
                ?.get("viewer")
                ?.jsonObject
                ?.get("accounts")
                ?.jsonArray
                ?: return emptyList()
            accounts.mapNotNull { it.jsonObject["number"]?.jsonPrimitive?.content }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Account discovery failed", e)
            emptyList()
        }
    }

    /** Forget the detected history (credential change). */
    suspend fun clear() {
        refreshMutex.withLock {
            lastAttemptAt = null
            preferencesRepository.clearTariffSchedule()
        }
    }

    // ── Internals ─────────────────────────────────────────────────────

    private suspend fun persist(
        detected: List<TariffAgreement>,
        mpan: String?,
        accountNumber: String?
    ): Result<TariffSchedule> {
        val agreements = TariffScheduleResolver.normalise(detected)
        val now = Instant.now()
        preferencesRepository.saveTariffSchedule(
            json = TariffAgreementMapper.encode(agreements, json),
            fetchedAt = now.toEpochMilli(),
            mpan = mpan,
            accountNumber = accountNumber
        )
        return Result.success(
            TariffSchedule(
                agreements = agreements,
                source = TariffSource.AUTO,
                fetchedAt = now,
                mpan = mpan,
                accountNumber = accountNumber
            )
        )
    }

    private fun needsFetch(schedule: TariffSchedule): Boolean = when (schedule.source) {
        // No credentials / explicit override: nothing to fetch.
        TariffSource.DEMO, TariffSource.MANUAL_OVERRIDE -> false
        TariffSource.AUTO -> isStale(schedule)
        TariffSource.FALLBACK -> true
    }

    private fun isStale(schedule: TariffSchedule): Boolean {
        val fetchedAt = schedule.fetchedAt ?: return true
        val now = Instant.now()
        if (Duration.between(fetchedAt, now).seconds >= Constants.TARIFF_SCHEDULE_TTL_SECONDS) {
            return true
        }
        // The detected history says the current tariff has ended — the feed is
        // behind an actual switch, so re-detect.
        val lastEnd = schedule.agreements.lastOrNull()?.validTo
        return lastEnd != null && lastEnd.isBefore(now)
    }

    private fun singleAgreement(
        config: TariffConfig,
        source: TariffSource,
        mpan: String?,
        error: String? = null
    ): TariffSchedule = TariffSchedule(
        agreements = listOf(
            TariffAgreement(
                tariffCode = config.tariffCode,
                productCode = config.productCode,
                validFrom = Instant.EPOCH,
                validTo = null
            )
        ),
        source = source,
        mpan = mpan,
        error = error
    )

    private companion object {
        const val TAG = "TariffSchedule"
    }
}
