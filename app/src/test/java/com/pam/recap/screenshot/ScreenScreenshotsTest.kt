package com.pam.recap.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.security.FingerprintFactory
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.data.ai.AiPayloadBuilder
import com.pam.recap.data.backend.DisabledServerAnalysisRepository
import com.pam.recap.data.ai.AndroidAnalysisCopySource
import com.pam.recap.data.ai.OnDeviceAnalysisEngine
import com.pam.recap.data.gmail.FixtureGmailSource
import com.pam.recap.data.gmail.FixtureScenario
import com.pam.recap.data.gmail.LivinQrisParser
import com.pam.recap.data.gmail.LivinQueryBuilder
import com.pam.recap.data.gmail.LivinTemplateRegistry
import com.pam.recap.data.seed.DemoDataset
import com.pam.recap.domain.model.AccountIdentity
import com.pam.recap.domain.model.AiAggregatePayload
import com.pam.recap.domain.model.Consent
import com.pam.recap.domain.model.ConsentDecision
import com.pam.recap.domain.model.ConsentPurpose
import com.pam.recap.domain.model.GmailConnection
import com.pam.recap.domain.model.GmailConnectionState
import com.pam.recap.domain.model.MetricKeys
import com.pam.recap.domain.model.RawAnalysis
import com.pam.recap.domain.model.RawObservation
import com.pam.recap.domain.model.Session
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.repository.AnalysisEngine
import com.pam.recap.domain.repository.AnalysisEngineConfiguration
import com.pam.recap.domain.repository.DemoDataPort
import com.pam.recap.domain.repository.SessionRepository
import com.pam.recap.domain.usecase.AddManualEntryUseCase
import com.pam.recap.domain.usecase.AnalysisValidator
import com.pam.recap.domain.usecase.ComputeMonthlySummaryUseCase
import com.pam.recap.domain.usecase.ConnectGmailUseCase
import com.pam.recap.domain.usecase.DisconnectGmailUseCase
import com.pam.recap.domain.usecase.ImportMonthUseCase
import com.pam.recap.domain.usecase.IncrementalSyncUseCase
import com.pam.recap.domain.usecase.LinkRefundUseCase
import com.pam.recap.domain.usecase.MarkInternalTransferUseCase
import com.pam.recap.domain.usecase.MerchantCategoryRules
import com.pam.recap.domain.usecase.RecordConsentUseCase
import com.pam.recap.domain.usecase.RequestAnalysisUseCase
import com.pam.recap.domain.usecase.RequestDeletionUseCase
import com.pam.recap.domain.usecase.ResolveInitialMonthUseCase
import com.pam.recap.domain.usecase.RetryImportUseCase
import com.pam.recap.domain.usecase.SignInUseCase
import com.pam.recap.domain.usecase.SignOutUseCase
import com.pam.recap.domain.usecase.UpdateTransactionCategoryUseCase
import com.pam.recap.domain.usecase.UpdateTransactionNoteUseCase
import com.pam.recap.domain.usecase.WithdrawAiConsentUseCase
import com.pam.recap.testing.FakeAccountRepository
import com.pam.recap.testing.FakeAnalysisRepository
import com.pam.recap.testing.FakeConsentRepository
import com.pam.recap.testing.FakeGoogleSignInGateway
import com.pam.recap.testing.FakeSessionRepository
import com.pam.recap.testing.FakeDeletionRepository
import com.pam.recap.testing.FakeAppPasswordVault
import com.pam.recap.testing.FakeCredentialValidator
import com.pam.recap.testing.FakeDemoDataMaintenance
import com.pam.recap.testing.FakeGmailConnectionRepository
import com.pam.recap.testing.FakeImportRepository
import com.pam.recap.testing.FakeSummaryRepository
import com.pam.recap.testing.FakeTransactionRepository
import com.pam.recap.testing.NoopBackendConsentSync
import com.pam.recap.testing.NoopBackendDeletionSync
import com.pam.recap.testing.NoopBackendSessionRepository
import com.pam.recap.testing.NoopBackendSummarySync
import com.pam.recap.testing.SequentialIdGenerator
import com.pam.recap.testing.completeJob
import com.pam.recap.testing.fixedClock
import com.pam.recap.ui.feature.analysis.AnalysisScreen
import com.pam.recap.ui.feature.analysis.AnalysisViewModel
import com.pam.recap.ui.feature.transactions.RecordingServerFinanceRepository
import com.pam.recap.ui.feature.consent.ConsentScreen
import com.pam.recap.ui.feature.consent.ConsentViewModel
import com.pam.recap.ui.feature.entry.ManualEntryScreen
import com.pam.recap.ui.feature.entry.ManualEntryViewModel
import com.pam.recap.ui.feature.gmail.GmailConnectScreen
import com.pam.recap.ui.feature.gmail.GmailConnectViewModel
import com.pam.recap.ui.feature.overview.OverviewScreen
import com.pam.recap.ui.feature.overview.OverviewViewModel
import com.pam.recap.ui.feature.settings.ConsentHistoryScreen
import com.pam.recap.ui.feature.settings.ConsentHistoryViewModel
import com.pam.recap.ui.feature.settings.DataDeletionScreen
import com.pam.recap.ui.feature.settings.DataDeletionViewModel
import com.pam.recap.ui.feature.settings.SettingsScreen
import com.pam.recap.ui.feature.settings.SettingsViewModel
import com.pam.recap.ui.feature.transactions.TransactionDetailScreen
import com.pam.recap.ui.feature.transactions.TransactionDetailViewModel
import com.pam.recap.ui.feature.transactions.TransactionsScreen
import com.pam.recap.ui.feature.transactions.TransactionsViewModel
import com.pam.recap.ui.feature.account.AccountMode
import com.pam.recap.ui.feature.account.AccountScreen
import com.pam.recap.ui.feature.account.AccountViewModel
import com.pam.recap.ui.feature.welcome.WelcomeScreen
import com.pam.recap.ui.theme.PamTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.time.Instant

/**
 * Renders every screen of the app on the JVM (Robolectric native graphics) against the sealed demo
 * dataset, so layout and design-system treatment can be reviewed without a device.
 *
 * The seam that makes this possible: ViewModels take plain constructor dependencies, so each screen
 * is built with the real use cases over in-memory fakes and a fixed clock. View models are created
 * outside the composable so lint stays clean and recomposition cannot replace them.
 *
 * Record with:
 * `./scripts/build.sh :app:recordRoborazziDebug --tests "com.pam.recap.screenshot.ScreenScreenshotsTest"`
 * Images land in `app/build/screenshots/`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [34],
    // The app pins id-ID in Application + MainActivity; Robolectric's app-context resources ignore
    // attachBaseContext (verified), so the device qualifier is set to the configuration the app
    // pins. That makes these captures show what a device renders.
    qualifiers = "id-rID-w411dp-h891dp-xxhdpi",
)
class ScreenScreenshotsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val ownerId = "u_demo"
    private val now = Instant.parse("2026-03-01T02:00:00Z")
    private val clock: ClockProvider = fixedClock(now)

    private val transactions = FakeTransactionRepository()
    private val summaries = FakeSummaryRepository()
    private val imports = FakeImportRepository()
    private val connections = FakeGmailConnectionRepository()
    private val analyses = FakeAnalysisRepository()
    private val deletion = FakeDeletionRepository()
    private val consents = FakeConsentRepository(
        listOf(
            consent(ConsentPurpose.GMAIL_READ, ConsentDecision.GRANTED),
            consent(ConsentPurpose.AI_PROCESSING, ConsentDecision.GRANTED),
        ),
    )
    private val sessions = DemoSessions(ownerId, now)
    private val registry = LivinTemplateRegistry()
    private val gmail = FixtureGmailSource(registry)
    private val fingerprints = FingerprintFactory { id -> "screenshot-key::$id".toByteArray() }
    private val ids = SequentialIdGenerator("shot")
    private val noopVault = FakeAppPasswordVault()
    private val demoData = FakeDemoDataMaintenance()
    private val noopValidator = FakeCredentialValidator()

    private val computeSummary = ComputeMonthlySummaryUseCase(
        transactions = transactions,
        summaries = summaries,
        imports = imports,
        gmailConnection = connections,
        clock = clock,
    )
    private val resolveMonth = ResolveInitialMonthUseCase(transactions, clock)
    private val recordConsent = RecordConsentUseCase(
        consents,
        analyses,
        clock,
        ids,
        NoopBackendConsentSync(),
    )

    private val importMonth = ImportMonthUseCase(
        gmailSource = gmail,
        queryBuilder = LivinQueryBuilder(registry),
        parser = LivinQrisParser(registry),
        fingerprints = fingerprints,
        categoryRules = MerchantCategoryRules(),
        transactions = transactions,
        imports = imports,
        summaries = summaries,
        analyses = analyses,
        gmailConnection = connections,
        deletion = deletion,
        clock = clock,
        idGenerator = ids,
    )

    private val sync = IncrementalSyncUseCase(
        sessions = sessions,
        gmail = gmail,
        importMonth = importMonth,
        uploader = com.pam.recap.domain.usecase.ImportUploader { _, _ ->
            com.pam.recap.core.result.Outcome.Success("screenshot")
        },
        cursors = com.pam.recap.testing.FakeSyncCursorStore(),
        deletion = deletion,
        clock = clock,
        status = com.pam.recap.domain.usecase.SyncStatusHolder(),
    )

    private val requestAnalysis = RequestAnalysisUseCase(
        computeSummary = computeSummary,
        validator = AnalysisValidator(),
        consents = consents,
        analyses = analyses,
        payloadSource = AiPayloadBuilder(),
        // The real engine and the real resource copy, so the screenshot shows shipped text.
        onDeviceEngine = OnDeviceAnalysisEngine(
            AndroidAnalysisCopySource(ApplicationProvider.getApplicationContext()),
        ),
        remoteEngine = EvidenceEngine,
        remoteConfiguration = object : AnalysisEngineConfiguration {
            override val isConfigured: Boolean = false
        },
        gmailConnection = connections,
        clock = clock,
        idGenerator = ids,
        backendSummary = NoopBackendSummarySync(),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        transactions.replaceAll(demoRows())
        runBlocking {
            // The demo seeder writes a COMPLETE job for the reference month; without it the
            // overview would (correctly) report partial coverage for data of unknown provenance.
            imports.create(completeJob(ownerId, DemoDataset.REFERENCE_MONTH, now))
            connections.save(
                GmailConnection(
                    ownerId = ownerId,
                    state = GmailConnectionState.CONNECTED,
                    mailboxLabel = "pengguna.uji@example.test",
                    scope = GmailConnection.READONLY_SCOPE,
                    connectedAt = now,
                    lastImportAt = now,
                    lastAiAt = null,
                    updatedAt = now,
                ),
            )
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun demoRows(): List<Transaction> = DemoDataset.build(
        ownerId = ownerId,
        now = now,
        parser = LivinQrisParser(registry),
        rules = MerchantCategoryRules(),
        fingerprints = fingerprints,
    )

    private fun consent(purpose: ConsentPurpose, decision: ConsentDecision) = Consent(
        id = "consent-${purpose.name}",
        ownerId = ownerId,
        purpose = purpose,
        noticeVersion = "v1",
        decision = decision,
        decidedAt = now,
    )

    private fun shot(
        name: String,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = base.density, fontScale = fontScale),
            ) {
                PamTheme { content() }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    // ---------------------------------------------------------------------------------------
    // 01 Overview
    // ---------------------------------------------------------------------------------------
    @Test
    fun overview() {
        val viewModel = OverviewViewModel(
            sessions = sessions,
            transactions = transactions,
            connections = connections,
            computeSummary = computeSummary,
            resolveInitialMonth = resolveMonth,
        )

        shot("01-overview") {
            OverviewScreen(
                onOpenTransactions = { _, _ -> },
                onOpenTransaction = {},
                viewModel = viewModel,
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // 02 Transactions (populated) and 02b (empty month)
    // ---------------------------------------------------------------------------------------
    @Test
    fun transactions() {
        val viewModel = TransactionsViewModel(
            savedStateHandle = SavedStateHandle(mapOf("monthId" to "2026-02")),
            sessions = sessions,
            transactions = transactions,
            resolveInitialMonth = resolveMonth,
        )

        shot("02-transactions") {
            TransactionsScreen(
                onOpenTransaction = {},
                onOpenManualEntry = {},
                onBack = null,
                viewModel = viewModel,
            )
        }
    }

    @Test
    fun transactionsEmpty() {
        val viewModel = TransactionsViewModel(
            savedStateHandle = SavedStateHandle(mapOf("monthId" to "2026-06")),
            sessions = sessions,
            transactions = transactions,
            resolveInitialMonth = resolveMonth,
        )

        shot("02b-transactions-empty") {
            TransactionsScreen(
                onOpenTransaction = {},
                onOpenManualEntry = {},
                onBack = null,
                viewModel = viewModel,
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // 03 Transaction detail
    // ---------------------------------------------------------------------------------------
    @Test
    fun transactionDetail() {
        val target = demoRows().first { it.category.code == "housing" && it.direction.name == "DEBIT" }
        val viewModel = TransactionDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("transactionId" to target.id)),
            sessions = sessions,
            transactions = transactions,
            updateCategory = UpdateTransactionCategoryUseCase(transactions, summaries, analyses, clock),
            markTransfer = MarkInternalTransferUseCase(transactions, summaries, analyses, clock),
            linkRefund = LinkRefundUseCase(transactions, summaries, analyses, clock),
            updateNote = UpdateTransactionNoteUseCase(transactions, clock),
        )

        shot("03-transaction-detail") {
            TransactionDetailScreen(onBack = {}, viewModel = viewModel)
        }
    }

    // ---------------------------------------------------------------------------------------
    // 04 Analysis
    // ---------------------------------------------------------------------------------------
    @Test
    fun analysis() {
        val viewModel = AnalysisViewModel(
            savedStateHandle = SavedStateHandle(mapOf("monthId" to "2026-02")),
            sessions = sessions,
            analyses = analyses,
            consents = consents,
            computeSummary = computeSummary,
            requestAnalysis = requestAnalysis,
            resolveInitialMonth = resolveMonth,
            serverAnalysis = DisabledServerAnalysisRepository,
            serverFinance = RecordingServerFinanceRepository(),
        )

        shot("04-analysis") {
            AnalysisScreen(onOpenConsent = {}, viewModel = viewModel)
        }
    }

    // ---------------------------------------------------------------------------------------
    // 06 Settings
    // ---------------------------------------------------------------------------------------
    @Test
    fun settingsWithDemoRows() {
        val demoWithRows = FakeDemoDataMaintenance(fixedCount = 19)
        val viewModel = SettingsViewModel(
            sessions = sessions,
            connections = connections,
            consents = consents,
            disconnectGmail = DisconnectGmailUseCase(gmail, connections, sessions, noopVault, clock),
            demoData = demoWithRows,
            signOut = SignOutUseCase(
                sessions = sessions,
                gmailSource = gmail,
                gmailConnection = connections,
                transactions = transactions,
                summaries = summaries,
                analyses = analyses,
                        imports = imports,
                backendSessions = NoopBackendSessionRepository(),
                appPasswordVault = noopVault,
                clock = clock,
            ),
        )

        shot("06b-settings-demo-purge") {
            SettingsScreen(
                onOpenGmailLink = {},
                onOpenConsentHistory = {},
                onOpenCategories = {},
                onOpenDeletion = {},
                onSignedOut = {},
                viewModel = viewModel,
            )
        }
    }

    @Test
    fun settings() {
        val viewModel = SettingsViewModel(
            sessions = sessions,
            connections = connections,
            consents = consents,
            disconnectGmail = DisconnectGmailUseCase(gmail, connections, sessions, noopVault, clock),
            demoData = demoData,
            signOut = SignOutUseCase(
                sessions = sessions,
                gmailSource = gmail,
                gmailConnection = connections,
                transactions = transactions,
                summaries = summaries,
                analyses = analyses,
                        imports = imports,
                backendSessions = NoopBackendSessionRepository(),
                appPasswordVault = noopVault,
                clock = clock,
            ),
        )

        shot("06-settings") {
            SettingsScreen(
                onOpenGmailLink = {},
                onOpenConsentHistory = {},
                onOpenCategories = {},
                onOpenDeletion = {},
                onSignedOut = {},
                viewModel = viewModel,
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // 07 Consent history
    // ---------------------------------------------------------------------------------------
    @Test
    fun consentHistory() {
        val viewModel = ConsentHistoryViewModel(sessions = sessions, consents = consents)

        shot("07-consent-history") {
            ConsentHistoryScreen(onBack = {}, viewModel = viewModel)
        }
    }

    // ---------------------------------------------------------------------------------------
    // 08 Data deletion
    // ---------------------------------------------------------------------------------------
    @Test
    fun dataDeletion() {
        val viewModel = DataDeletionViewModel(
            sessions = sessions,
            deletion = deletion,
            requestDeletion = RequestDeletionUseCase(
                deletion = deletion,
                transactions = transactions,
                summaries = summaries,
                analyses = analyses,
                        imports = imports,
                consents = consents,
                gmailConnection = connections,
                sessions = sessions,
                clock = clock,
                idGenerator = ids,
                backendDeletion = NoopBackendDeletionSync(),
                appPasswordVault = noopVault,
            ),
            withdrawAiConsent = WithdrawAiConsentUseCase(recordConsent),
        )

        shot("08-data-deletion") {
            DataDeletionScreen(onBack = {}, onDeleted = {}, viewModel = viewModel)
        }
    }

    // ---------------------------------------------------------------------------------------
    // 09 Welcome
    // ---------------------------------------------------------------------------------------
    @Test
    fun welcome() {
        // Production shape: a server-backed build, so the demo notice must not appear.
        shot("09-welcome") {
            WelcomeScreen(
                onSignIn = {},
                onCreateAccount = {},
                onOpenPrivacy = {},
                showDemoNotice = false,
            )
        }
    }

    @Test
    fun welcomeDemo() {
        shot("09-welcome-demo") {
            WelcomeScreen(
                onSignIn = {},
                onCreateAccount = {},
                onOpenPrivacy = {},
                showDemoNotice = true,
            )
        }
    }

    @Test
    fun accountSignIn() {
        val accounts = FakeAccountRepository(isBackendEnabled = true)
        val viewModel = AccountViewModel(
            accounts,
            FakeSessionRepository(),
            FakeGoogleSignInGateway(),
            SavedStateHandle(mapOf("mode" to "SIGN_IN")),
        )

        shot("09b-account-signin") {
            AccountScreen(onBack = {}, viewModel = viewModel)
        }
    }

    @Test
    fun accountSignInGoogleEnabled() {
        val accounts = FakeAccountRepository(isBackendEnabled = true, googleSsoEnabled = true)
        val viewModel = AccountViewModel(
            accounts,
            FakeSessionRepository(),
            FakeGoogleSignInGateway(),
            SavedStateHandle(mapOf("mode" to "SIGN_IN")),
        )

        shot("09b2-account-signin-google") {
            AccountScreen(onBack = {}, viewModel = viewModel)
        }
    }

    @Test
    fun accountRegister() {
        val accounts = FakeAccountRepository(isBackendEnabled = true)
        val viewModel = AccountViewModel(
            accounts,
            FakeSessionRepository(),
            FakeGoogleSignInGateway(),
            SavedStateHandle(mapOf("mode" to "REGISTER")),
        )

        shot("09c-account-register") {
            AccountScreen(onBack = {}, viewModel = viewModel)
        }
    }

    @Test
    fun accountOffline() {
        val accounts = FakeAccountRepository(isBackendEnabled = false)
        val viewModel = AccountViewModel(
            accounts,
            FakeSessionRepository(),
            FakeGoogleSignInGateway(),
            SavedStateHandle(mapOf("mode" to "SIGN_IN")),
        )

        shot("09d-account-offline") {
            AccountScreen(onBack = {}, viewModel = viewModel)
        }
    }

    @Test
    fun accountSignedInGoogleLink() {
        val owner = ownerId
        val accounts = FakeAccountRepository(isBackendEnabled = true, googleSsoEnabled = true)
        val viewModel = AccountViewModel(
            accounts,
            FakeSessionRepository(
                Session(
                    ownerId = owner,
                    displayName = "Pengguna Uji",
                    mailboxLabel = "pengguna.uji@example.test",
                    signedInAt = now,
                    isDemo = false,
                    googleLinked = false,
                ),
            ),
            FakeGoogleSignInGateway(),
            SavedStateHandle(),
        )

        shot("09e-account-google-link") {
            AccountScreen(onBack = {}, viewModel = viewModel)
        }
    }

    // ---------------------------------------------------------------------------------------
    // 10 Consent
    // ---------------------------------------------------------------------------------------
    @Test
    fun consent() {
        val viewModel = ConsentViewModel(sessions = sessions, recordConsent = recordConsent)

        shot("10-consent") {
            ConsentScreen(onFinished = {}, viewModel = viewModel)
        }
    }

    // ---------------------------------------------------------------------------------------
    // 11 Gmail connect
    // ---------------------------------------------------------------------------------------
    @Test
    fun gmailConnect() {
        val viewModel = GmailConnectViewModel(
            sessions = sessions,
            connections = connections,
            consents = consents,
            validator = noopValidator,
            vault = noopVault,
            demoData = demoData,
            connectGmail = ConnectGmailUseCase(gmail, gmail, consents, connections, sessions, clock),
            disconnectGmail = DisconnectGmailUseCase(
                gmail,
                connections,
                sessions,
                noopVault,
                clock,
            ),
            accounts = FakeAccountRepository(isBackendEnabled = true),
        )

        shot("11-gmail-connect") {
            GmailConnectScreen(onBack = {}, onOpenConsent = {}, viewModel = viewModel)
        }
    }

    @Test
    fun gmailConnectWithCredentials() {
        runBlocking {
            connections.save(
                GmailConnection(
                    ownerId = ownerId,
                    state = GmailConnectionState.NOT_CONNECTED,
                    mailboxLabel = null,
                    scope = GmailConnection.IMAP_SCOPE,
                    connectedAt = null,
                    lastImportAt = null,
                    lastAiAt = null,
                    updatedAt = now,
                ),
            )
        }
        val viewModel = GmailConnectViewModel(
            sessions = sessions,
            connections = connections,
            consents = consents,
            validator = noopValidator,
            vault = noopVault,
            demoData = demoData,
            connectGmail = ConnectGmailUseCase(gmail, gmail, consents, connections, sessions, clock),
            disconnectGmail = DisconnectGmailUseCase(gmail, connections, sessions, noopVault, clock),
            accounts = FakeAccountRepository(isBackendEnabled = true),
        )

        shot("11b-gmail-credentials") {
            GmailConnectScreen(onBack = {}, onOpenConsent = {}, viewModel = viewModel)
        }
    }

    // ---------------------------------------------------------------------------------------
    // 12 Manual entry
    // ---------------------------------------------------------------------------------------
    @Test
    fun manualEntry() {
        val viewModel = ManualEntryViewModel(
            savedStateHandle = SavedStateHandle(mapOf("monthId" to "2026-02")),
            sessions = sessions,
            addManualEntry = AddManualEntryUseCase(transactions, summaries, analyses, fingerprints, clock, ids),
            clock = clock,
        )

        shot("12-manual-entry") {
            ManualEntryScreen(onBack = {}, onSaved = {}, viewModel = viewModel)
        }
    }

    /** Deterministic engine that only cites real metric keys, so validation passes. */
    private object EvidenceEngine : AnalysisEngine {
        override val providerId: String = "on-device"
        override val modelId: String = "deterministic-v1"

        override suspend fun analyze(request: com.pam.recap.domain.repository.AnalysisRequest): Outcome<RawAnalysis> = Outcome.Success(
            RawAnalysis(
                providerId = providerId,
                modelId = modelId,
                promptVersion = "v1",
                observations = listOf(
                    RawObservation(
                        text = "Pengeluaran terbesar bulan ini ada pada kategori perumahan.",
                        metricKeys = listOf(MetricKeys.GROSS_OUTFLOW, MetricKeys.category("housing")),
                    ),
                ),
                suggestions = emptyList(),
            ),
        )
    }

    private object NoopDemoData : DemoDataPort {
        override suspend fun seedIfNeeded(ownerId: String): Boolean = false
    }

    private class DemoSessions(private val ownerId: String, private val now: Instant) : SessionRepository {
        private val session = Session(
            ownerId = ownerId,
            displayName = "Pengguna Uji",
            mailboxLabel = "pengguna.uji@example.test",
            signedInAt = now,
            isDemo = true,
        )
        private val flow = MutableStateFlow<Session?>(session)

        override fun observe(): Flow<Session?> = flow
        override suspend fun current(): Session? = session
        override suspend fun signIn(session: Session) = Unit
        override suspend fun updateMailbox(ownerId: String, mailboxLabel: String?) = Unit
        override suspend fun signOut() = Unit
        override suspend fun isDemoSeeded(ownerId: String): Boolean = true
        override suspend fun markDemoSeeded(ownerId: String) = Unit
        override suspend fun clearDemoSeeded(ownerId: String) = Unit
    }
}
