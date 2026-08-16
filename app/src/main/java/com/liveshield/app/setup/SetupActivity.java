package com.liveshield.app.setup;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Trace;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.fragment.app.FragmentActivity;
import com.liveshield.app.BuildConfig;
import com.liveshield.app.R;
import com.liveshield.app.session.CameraSessionGraph;
import com.liveshield.app.diagnostics.AppDiagnostics;
import com.liveshield.app.session.LiveSessionCoordinator;
import com.liveshield.app.session.SetupSessionFactory;
import com.liveshield.privacy.policy.SessionPrivacyConfigurationView;
import com.liveshield.privacy.session.SessionState;
import com.liveshield.transport.destination.StreamDestination;
import com.liveshield.video.geometry.FrameTransform;
import java.util.ArrayList;
import java.util.List;

/** Privacy-gated setup UI. Camera/render/session wiring is supplied later by T038/T042. */
public final class SetupActivity extends FragmentActivity
        implements SetupView,
        ScopeDisclosureFragment.Listener,
        StreamDestinationFragment.Listener {
    private static final int CAMERA_PERMISSION_REQUEST = 1001;
    private static final String SETUP_CREATE_TRACE = "LiveShieldSetupCreate";
    private static final String DISCLOSURE_ACCEPTED_STATE = "scopeDisclosureAccepted";
    private static final String CAMERA_PERMISSION_REQUESTED_STATE =
            "cameraPermissionRequested";
    private static final String DISCLOSURE_FRAGMENT_TAG = "scope-disclosure";
    static final String DEBUG_ALLOW_SCREEN_CAPTURE =
            "com.liveshield.app.debug.ALLOW_SCREEN_CAPTURE";
    private static final CameraPermissionPort SYSTEM_CAMERA_PERMISSION =
            new CameraPermissionPort() {
                @Override
                public boolean isGranted(SetupActivity activity) {
                    return activity.checkSelfPermission(Manifest.permission.CAMERA)
                            == PackageManager.PERMISSION_GRANTED;
                }

                @Override
                public void request(SetupActivity activity, int requestCode) {
                    activity.requestPermissions(
                            new String[]{Manifest.permission.CAMERA}, requestCode);
                }
            };

    private SetupReadinessState readiness = SetupReadinessState.initial();
    private boolean hostReselectionRequired;
    private SetupUiListener listener = SetupUiListener.NO_OP;
    private FrameLayout sanitizedPreviewContainer;
    private View setupContent;
    private View scopeDisclosureContainer;
    private FaceSelectionOverlayView faceOverlay;
    private View faceSelectionIndicator;
    private TextView faceSelectionStatus;
    private TextView permissionStatus;
    private TextView privacyStatus;
    private Button startButton;
    private LiveSessionCoordinator sessionCoordinator;
    private SetupSessionFactory.PendingCreation pendingCreation;
    private boolean uiContractHarnessInstalledForTest;
    private boolean scopeDisclosureAccepted;
    private boolean cameraPermissionRequestIssued;
    private CameraPermissionPort cameraPermissionPort = SYSTEM_CAMERA_PERMISSION;
    private StreamDestination streamDestination;
    private EditText watchlistInput;
    private LinearLayout watchlistDetails;
    private LinearLayout watchlistTermsContainer;
    private TextView watchlistStatus;
    private View watchlistIndicator;
    private PrivacyZoneEditorView zoneEditor;
    private View privacyZoneIndicator;
    private boolean cameraTransformValidated;
    private FrameTransform privacyZoneTransform;
    private Runnable privacyConfigurationListener = () -> { };
    private final IndoorPrivacySetupController indoorPrivacySetup =
            new IndoorPrivacySetupController();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Trace.beginSection(SETUP_CREATE_TRACE);
        try {
            super.onCreate(savedInstanceState);
            setContentView(R.layout.activity_setup);
            boolean debugCaptureRequested = getIntent().getBooleanExtra(
                    DEBUG_ALLOW_SCREEN_CAPTURE, false);
            if (!allowDebugScreenCapture(BuildConfig.DEBUG, debugCaptureRequested)) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            }
            setupContent = findViewById(R.id.setup_content);
            scopeDisclosureContainer = findViewById(R.id.scope_disclosure_container);
            applySystemBarInsets(setupContent);
            applySystemBarInsets(scopeDisclosureContainer);
            sanitizedPreviewContainer = findViewById(R.id.sanitized_preview_container);
            faceOverlay = findViewById(R.id.face_selection_overlay);
            faceSelectionIndicator = findViewById(R.id.face_selection_indicator);
            faceSelectionStatus = findViewById(R.id.face_selection_status);
            permissionStatus = findViewById(R.id.camera_permission_status);
            privacyStatus = findViewById(R.id.privacy_readiness_status);
            startButton = findViewById(R.id.start_protected_live);
            bindIndoorPrivacyControls();

            faceOverlay.setSelectionListener(trackId -> listener.onHostSelectionRequested(trackId));
            sanitizedPreviewContainer.setOnClickListener(ignored -> requestCameraPermission());
            setupContent.addOnLayoutChangeListener(
                    (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                            updatePreviewHeight());
            startButton.setOnClickListener(ignored -> {
                if (readiness.canStart()) {
                    listener.onStartRequested();
                }
            });
            scopeDisclosureAccepted = savedInstanceState != null
                    && savedInstanceState.getBoolean(DISCLOSURE_ACCEPTED_STATE, false);
            cameraPermissionRequestIssued = savedInstanceState != null
                    && savedInstanceState.getBoolean(
                            CAMERA_PERMISSION_REQUESTED_STATE, false);
            if (scopeDisclosureAccepted) {
                showSetupContent();
                requestCameraPermissionAutomatically();
            } else {
                setupContent.setVisibility(View.GONE);
                scopeDisclosureContainer.setVisibility(View.VISIBLE);
                if (getSupportFragmentManager().findFragmentByTag(DISCLOSURE_FRAGMENT_TAG) == null) {
                    getSupportFragmentManager().beginTransaction()
                            .replace(
                                    R.id.scope_disclosure_container,
                                    new ScopeDisclosureFragment(),
                                    DISCLOSURE_FRAGMENT_TAG)
                            .commitNow();
                }
            }
            renderReadiness();
        } finally {
            Trace.endSection();
        }
    }

    static boolean allowDebugScreenCapture(boolean debugBuild, boolean explicitlyRequested) {
        return debugBuild && explicitlyRequested;
    }

    private static void applySystemBarInsets(View view) {
        int left = view.getPaddingLeft();
        int top = view.getPaddingTop();
        int right = view.getPaddingRight();
        int bottom = view.getPaddingBottom();
        view.setOnApplyWindowInsetsListener((target, insets) -> {
            target.setPadding(
                    left + systemInsetLeft(insets),
                    top + systemInsetTop(insets),
                    right + systemInsetRight(insets),
                    bottom + systemInsetBottom(insets));
            return insets;
        });
        view.requestApplyInsets();
    }

    @SuppressWarnings("deprecation")
    private static int systemInsetLeft(WindowInsets insets) {
        return insets.getSystemWindowInsetLeft();
    }

    @SuppressWarnings("deprecation")
    private static int systemInsetTop(WindowInsets insets) {
        return insets.getSystemWindowInsetTop();
    }

    @SuppressWarnings("deprecation")
    private static int systemInsetRight(WindowInsets insets) {
        return insets.getSystemWindowInsetRight();
    }

    @SuppressWarnings("deprecation")
    private static int systemInsetBottom(WindowInsets insets) {
        return insets.getSystemWindowInsetBottom();
    }

    boolean isDebugScreenCaptureAllowed() {
        return allowDebugScreenCapture(
                BuildConfig.DEBUG,
                getIntent().getBooleanExtra(DEBUG_ALLOW_SCREEN_CAPTURE, false));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (scopeDisclosureAccepted) {
            refreshCameraPermission();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(DISCLOSURE_ACCEPTED_STATE, scopeDisclosureAccepted);
        outState.putBoolean(
                CAMERA_PERMISSION_REQUESTED_STATE, cameraPermissionRequestIssued);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onScopeDisclosureAccepted() {
        if (scopeDisclosureAccepted) {
            return;
        }
        scopeDisclosureAccepted = true;
        showSetupContent();
        requestCameraPermissionAutomatically();
    }

    @Override
    public void onStreamDestinationConfigured(StreamDestination destination) {
        StreamDestination configured = java.util.Objects.requireNonNull(
                destination, "destination");
        if (!scopeDisclosureAccepted) {
            configured.close();
            throw new IllegalStateException("Scope disclosure must be accepted before destination");
        }
        readiness = readiness.withDestinationConfigured(false);
        if (sessionCoordinator != null) {
            configurePublication(sessionCoordinator, configured);
        } else {
            if (streamDestination != null) {
                streamDestination.close();
            }
            streamDestination = configured;
        }
        renderReadiness();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            refreshCameraPermission();
        }
    }

    /** Returns the only UI container T038 may connect to a renderer-owned sanitized preview. */
    public FrameLayout sanitizedPreviewContainer() {
        return sanitizedPreviewContainer;
    }

    /** Installs event callbacks without granting this Activity camera or session ownership. */
    public void setSetupUiListener(SetupUiListener newListener) {
        listener = newListener == null ? SetupUiListener.NO_OP : newListener;
    }

    /** Connects the fail-private coordinator and starts its sanitized-only graph. */
    public void attachCoordinator(LiveSessionCoordinator coordinator) {
        if (!scopeDisclosureAccepted) {
            throw new IllegalStateException("Scope disclosure must be accepted before setup");
        }
        if (sessionCoordinator != null) {
            throw new IllegalStateException("A setup coordinator is already attached");
        }
        sessionCoordinator = java.util.Objects.requireNonNull(coordinator, "coordinator");
        if (streamDestination != null) {
            StreamDestination pendingDestination = streamDestination;
            streamDestination = null;
            configurePublication(coordinator, pendingDestination);
        }
        setSetupUiListener(coordinator);
        coordinator.begin();
    }

    /** Displays fresh session-local tracks. Stale tracks remain non-selectable and do not confer readiness. */
    public void showSelectableFaces(List<SelectableFace> faces, Long selectedTrack) {
        boolean selectedFresh = FreshHostSelection.isSelectedAndFresh(faces, selectedTrack);
        readiness = readiness.withFreshHostSelection(selectedFresh);
        faceOverlay.showFaces(faces, selectedFresh ? selectedTrack : null);
        faceSelectionStatus.setText(selectedFresh
                ? R.string.setup_face_selected : R.string.setup_face_waiting);
        faceSelectionIndicator.setBackgroundResource(selectedFresh
                ? R.drawable.ls_status_circle_selected : R.drawable.ls_status_circle);
        renderReadiness();
    }

    /** Applies fail-closed privacy readiness supplied by the later session coordinator. */
    public void showPrivacyReady(boolean ready) {
        readiness = readiness.withPrivacyReady(ready);
        renderReadiness();
    }

    /** Displays a payload-free continuity warning and keeps start disabled until a fresh tap. */
    @Override
    public void showHostReselectionRequired(boolean required) {
        hostReselectionRequired = required;
        if (required) {
            readiness = readiness
                    .withFreshHostSelection(false)
                    .withPrivacyReady(false);
            faceSelectionStatus.setText(R.string.setup_face_waiting);
            faceSelectionIndicator.setBackgroundResource(R.drawable.ls_status_circle);
        }
        renderReadiness();
    }

    @Override
    protected void onDestroy() {
        listener = SetupUiListener.NO_OP;
        privacyConfigurationListener = () -> { };
        if (pendingCreation != null) {
            pendingCreation.cancel();
            pendingCreation = null;
        }
        if (sessionCoordinator != null) {
            sessionCoordinator.close();
            sessionCoordinator = null;
        }
        if (streamDestination != null) {
            streamDestination.close();
            streamDestination = null;
        }
        indoorPrivacySetup.close();
        cameraPermissionPort = SYSTEM_CAMERA_PERMISSION;
        super.onDestroy();
    }

    /** Immutable session-only policy configuration used by the production analysis graph. */
    public SessionPrivacyConfigurationView sessionPrivacyConfiguration() {
        return indoorPrivacySetup.snapshot();
    }

    /** Signals configuration changes without exposing private words through the callback. */
    public void setPrivacyConfigurationListener(Runnable newListener) {
        privacyConfigurationListener = newListener == null ? () -> { } : newListener;
    }

    /** Suspends configured zones while the selected camera's output geometry is unresolved. */
    public void markPrivacyZoneTransformUnsafe() {
        indoorPrivacySetup.markZoneTransformUnsafe();
        renderZoneEditor();
    }

    /** Re-authorizes output-normalized zones after CameraX validates the rendered transform. */
    public void acceptVerifiedPrivacyZoneTransform(FrameTransform transform) {
        privacyZoneTransform = java.util.Objects.requireNonNull(transform, "transform");
        cameraTransformValidated = true;
        applyOutputZonesToSensor();
        renderZoneEditor();
    }

    IndoorPrivacySetupController privacySetupControllerForTest() {
        return indoorPrivacySetup;
    }

    private void requestCameraPermission() {
        if (!scopeDisclosureAccepted) {
            return;
        }
        if (cameraPermissionPort.isGranted(this)) {
            refreshCameraPermission();
            return;
        }
        cameraPermissionRequestIssued = true;
        cameraPermissionPort.request(this, CAMERA_PERMISSION_REQUEST);
    }

    private void requestCameraPermissionAutomatically() {
        boolean granted = refreshCameraPermission();
        if (!granted && !cameraPermissionRequestIssued) {
            requestCameraPermission();
        }
    }

    private boolean refreshCameraPermission() {
        if (!scopeDisclosureAccepted) {
            return false;
        }
        boolean granted = cameraPermissionPort.isGranted(this);
        AppDiagnostics.info(granted
                ? AppDiagnostics.Event.CAMERA_PERMISSION_GRANTED
                : AppDiagnostics.Event.CAMERA_PERMISSION_DENIED);
        readiness = readiness.withCameraPermission(granted);
        if (permissionStatus != null) {
            permissionStatus.setText(granted
                    ? R.string.camera_permission_granted
                    : R.string.camera_permission_denied);
            sanitizedPreviewContainer.setClickable(!granted);
            sanitizedPreviewContainer.setFocusable(!granted);
            faceOverlay.setEnabled(granted);
            renderReadiness();
            if (granted) {
                ensureSessionCoordinator();
            }
        }
        return granted;
    }

    private void ensureSessionCoordinator() {
        if (!scopeDisclosureAccepted
                || uiContractHarnessInstalledForTest
                || sessionCoordinator != null
                || pendingCreation != null
                || isFinishing()) {
            return;
        }
        pendingCreation = SetupSessionFactory.create(
                this, new SetupSessionFactory.CreationListener() {
                    @Override
                    public void onCreated(LiveSessionCoordinator coordinator) {
                        pendingCreation = null;
                        AppDiagnostics.info(AppDiagnostics.Event.SESSION_FACTORY_CREATED);
                        if (uiContractHarnessInstalledForTest || isFinishing() || isDestroyed()) {
                            coordinator.close();
                            return;
                        }
                        attachCoordinator(coordinator);
                    }

                    @Override
                    public void onFailure(Throwable failure) {
                        pendingCreation = null;
                        AppDiagnostics.failure(
                                AppDiagnostics.Event.SESSION_FACTORY_FAILED, failure);
                        if (uiContractHarnessInstalledForTest) {
                            return;
                        }
                        showPrivacyReady(false);
                        showSessionRetryAvailable();
                    }
                }, this::onSessionTerminated);
    }

    private void configurePublication(
            LiveSessionCoordinator coordinator,
            StreamDestination destination) {
        coordinator.configurePublication(destination, configured -> runOnUiThread(() -> {
            if (sessionCoordinator != coordinator || isFinishing() || isDestroyed()) {
                return;
            }
            readiness = readiness.withDestinationConfigured(configured);
            if (configured) {
                showDestinationConfigured();
            } else {
                showDestinationRequired();
            }
            renderReadiness();
        }));
    }

    private void onSessionTerminated(SessionState finalState) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || sessionCoordinator == null) {
                return;
            }
            sessionCoordinator = null;
            listener = SetupUiListener.NO_OP;
            readiness = SetupReadinessState.initial().withCameraPermission(
                    cameraPermissionPort.isGranted(this));
            hostReselectionRequired = false;
            faceOverlay.showFaces(List.of(), null);
            faceSelectionStatus.setText(R.string.setup_face_waiting);
            faceSelectionIndicator.setBackgroundResource(R.drawable.ls_status_circle);
            showDestinationRequired();
            showSessionRetryAvailable();
            renderReadiness();
        });
    }

    private void showSessionRetryAvailable() {
        if (!readiness.cameraPermissionGranted()) {
            return;
        }
        faceOverlay.setEnabled(false);
        sanitizedPreviewContainer.setClickable(true);
        sanitizedPreviewContainer.setFocusable(true);
        permissionStatus.setText(R.string.camera_session_retry);
    }

    private void showDestinationRequired() {
        androidx.fragment.app.Fragment fragment = getSupportFragmentManager()
                .findFragmentByTag("stream-destination");
        if (fragment instanceof StreamDestinationFragment destinationFragment) {
            destinationFragment.showDestinationRequired();
        }
    }

    private void showDestinationConfigured() {
        androidx.fragment.app.Fragment fragment = getSupportFragmentManager()
                .findFragmentByTag("stream-destination");
        if (fragment instanceof StreamDestinationFragment destinationFragment) {
            destinationFragment.showDestinationConfigured();
        }
    }

    private void showSetupContent() {
        scopeDisclosureContainer.setVisibility(View.GONE);
        setupContent.setVisibility(View.VISIBLE);
        setupContent.requestApplyInsets();
        setupContent.post(this::updatePreviewHeight);
        if (getSupportFragmentManager().findFragmentByTag("stream-destination") == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(
                            R.id.stream_destination_container,
                            new StreamDestinationFragment(),
                            "stream-destination")
                    .commitNow();
        }
    }

    private void updatePreviewHeight() {
        int availableHeight = setupContent.getHeight()
                - setupContent.getPaddingTop()
                - setupContent.getPaddingBottom();
        int previewHeight = SetupViewportLayout.previewHeight(availableHeight);
        if (previewHeight == 0) {
            return;
        }
        ViewGroup.LayoutParams parameters = sanitizedPreviewContainer.getLayoutParams();
        if (parameters.height != previewHeight) {
            parameters.height = previewHeight;
            sanitizedPreviewContainer.setLayoutParams(parameters);
        }
    }

    private void bindIndoorPrivacyControls() {
        watchlistInput = findViewById(R.id.watchlist_term_input);
        watchlistDetails = findViewById(R.id.watchlist_details);
        watchlistTermsContainer = findViewById(R.id.watchlist_terms_container);
        watchlistStatus = findViewById(R.id.watchlist_status);
        watchlistIndicator = findViewById(R.id.watchlist_indicator);
        findViewById(R.id.toggle_watchlist_details).setOnClickListener(ignored -> {
            boolean show = watchlistDetails.getVisibility() != View.VISIBLE;
            watchlistDetails.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) {
                watchlistInput.requestFocus();
            }
        });
        findViewById(R.id.add_watchlist_term).setOnClickListener(
                ignored -> addWatchlistTerm());
        zoneEditor = findViewById(R.id.privacy_zone_editor_overlay);
        privacyZoneIndicator = findViewById(R.id.privacy_zone_indicator);
        findViewById(R.id.toggle_zone_drawing).setOnClickListener(this::toggleZoneDrawing);
        zoneEditor.setZoneDrawListener(this::addDrawnZone);
        zoneEditor.setZoneRemoveListener(this::removeZone);
        CheckBox barcodeProtection = findViewById(R.id.cover_qr_codes_toggle);
        barcodeProtection.setChecked(indoorPrivacySetup.automaticBarcodeProtectionEnabled());
        barcodeProtection.setOnCheckedChangeListener((ignored, enabled) ->
                indoorPrivacySetup.setAutomaticBarcodeProtectionEnabled(enabled));
        renderWatchlist();
        renderZoneEditor();
    }

    private void addWatchlistTerm() {
        try {
            boolean added = indoorPrivacySetup.addWatchlistTerm(
                    watchlistInput.getText().toString());
            watchlistInput.getText().clear();
            watchlistStatus.setText(added
                    ? R.string.private_words_updated
                    : R.string.private_word_duplicate);
            privacyConfigurationListener.run();
            renderWatchlistRows();
            renderWatchlistIndicator();
        } catch (IllegalArgumentException exception) {
            watchlistStatus.setText(R.string.private_word_invalid);
        } catch (IllegalStateException exception) {
            watchlistStatus.setText(R.string.private_word_limit);
        }
    }

    private void renderWatchlist() {
        renderWatchlistRows();
        watchlistStatus.setText(indoorPrivacySetup.snapshot().normalizedWatchlistTerms().isEmpty()
                ? R.string.private_words_empty : R.string.private_words_updated);
        renderWatchlistIndicator();
    }

    private void renderWatchlistIndicator() {
        boolean configured = !indoorPrivacySetup.snapshot().normalizedWatchlistTerms().isEmpty();
        watchlistIndicator.setBackgroundResource(configured
                ? R.drawable.ls_status_circle_selected : R.drawable.ls_status_circle);
    }

    private void renderWatchlistRows() {
        watchlistTermsContainer.removeAllViews();
        int itemNumber = 0;
        for (String term : indoorPrivacySetup.snapshot().normalizedWatchlistTerms()) {
            int position = ++itemNumber;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView label = new TextView(this);
            label.setText(term);
            label.setTextColor(getColor(R.color.ls_ink));
            label.setTextSize(15.0F);
            row.addView(label, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0F));
            Button remove = new Button(this);
            remove.setText(R.string.remove_private_word);
            remove.setContentDescription(getString(R.string.remove_private_word_number, position));
            remove.setOnClickListener(ignored -> {
                indoorPrivacySetup.removeWatchlistTerm(term);
                privacyConfigurationListener.run();
                renderWatchlist();
            });
            row.addView(remove);
            watchlistTermsContainer.addView(row);
        }
    }

    private void toggleZoneDrawing(View buttonView) {
        boolean drawing = zoneEditor.getVisibility() != View.VISIBLE;
        zoneEditor.setVisibility(drawing ? View.VISIBLE : View.GONE);
        faceOverlay.setEnabled(!drawing && readiness.cameraPermissionGranted());
        ((Button) buttonView).setText(drawing
                ? R.string.finish_drawing_privacy_zone
                : R.string.draw_privacy_zone);
    }

    private void addDrawnZone(com.liveshield.privacy.model.NormalizedRect zone) {
        try {
            indoorPrivacySetup.addPrivacyZone(zone);
            markEditedZoneUnsafe();
            // The controller atomically stages output geometry as unsafe. A currently validated
            // transform can immediately replace it with safe sensor-space geometry; otherwise the
            // policy remains full-shielded until alignment is confirmed.
            if (cameraTransformValidated) {
                confirmZoneAlignment();
            }
        } catch (IllegalArgumentException | IllegalStateException invalidZone) {
            AppDiagnostics.failure(AppDiagnostics.Event.PRIVACY_ZONE_EDIT_REJECTED, invalidZone);
        }
    }

    private void removeZone(int index) {
        if (index < 0 || index >= indoorPrivacySetup.configuredPrivacyZones().size()) {
            return;
        }
        indoorPrivacySetup.removePrivacyZone(index);
        int count = indoorPrivacySetup.configuredPrivacyZones().size();
        if (count > 0) {
            markEditedZoneUnsafe();
            if (cameraTransformValidated) {
                confirmZoneAlignment();
            }
        } else {
            renderZoneEditor();
        }
    }

    private void markEditedZoneUnsafe() {
        indoorPrivacySetup.markZoneTransformUnsafe();
        readiness = readiness.withPrivacyReady(false);
        renderReadiness();
        renderZoneEditor();
    }

    private void confirmZoneAlignment() {
        if (!cameraTransformValidated || privacyZoneTransform == null) {
            return;
        }
        applyOutputZonesToSensor();
        renderZoneEditor();
    }

    /** The editor stores displayed-output coordinates; renderer decisions require sensor space. */
    private void applyOutputZonesToSensor() {
        IndoorPrivacySetupController.ZoneTransformSnapshot transformRequest =
                indoorPrivacySetup.zoneTransformSnapshot();
        List<com.liveshield.privacy.model.NormalizedRect> configured =
                transformRequest.configuredZones();
        ArrayList<com.liveshield.privacy.model.NormalizedRect> sensorZones =
                new ArrayList<>(configured.size());
        for (com.liveshield.privacy.model.NormalizedRect outputZone : configured) {
            sensorZones.add(privacyZoneTransform.mapOutputRectToSensor(outputZone));
        }
        indoorPrivacySetup.applySafelyTransformedZones(transformRequest, sensorZones);
    }

    private void renderZoneEditor() {
        if (zoneEditor == null) {
            return;
        }
        List<com.liveshield.privacy.model.NormalizedRect> zones =
                indoorPrivacySetup.configuredPrivacyZones();
        zoneEditor.showZones(zones);
        IndoorPrivacySetupController.Configuration snapshot = indoorPrivacySetup.snapshot();
        privacyZoneIndicator.setBackgroundResource(
                !zones.isEmpty() && snapshot.zonesSafelyTransformed()
                        ? R.drawable.ls_status_circle_selected
                        : R.drawable.ls_status_circle);
    }

    private void renderReadiness() {
        if (startButton == null) {
            return;
        }
        startButton.setEnabled(scopeDisclosureAccepted && readiness.canStart());
        if (!readiness.cameraPermissionGranted()) {
            privacyStatus.setText(R.string.privacy_status_permission_required);
        } else if (hostReselectionRequired) {
            privacyStatus.setText(R.string.privacy_status_reselect_host);
        } else if (!readiness.freshHostSelected()) {
            privacyStatus.setText(R.string.privacy_status_select_host);
        } else if (!readiness.privacyReady()) {
            privacyStatus.setText(R.string.privacy_status_checking);
        } else if (!readiness.destinationConfigured()) {
            privacyStatus.setText(R.string.privacy_status_choose_destination);
        } else {
            privacyStatus.setText(R.string.privacy_status_ready);
        }
    }

    boolean hasSessionCoordinatorForTest() {
        return sessionCoordinator != null;
    }

    CameraSessionGraph.ReadinessSnapshot readinessSnapshotForTest() {
        return sessionCoordinator == null ? null : sessionCoordinator.readinessSnapshotForTest();
    }

    boolean isScopeDisclosureAcceptedForTest() {
        return scopeDisclosureAccepted;
    }

    void installCameraPermissionPortForTest(CameraPermissionPort port) {
        if (scopeDisclosureAccepted || sessionCoordinator != null || pendingCreation != null) {
            throw new IllegalStateException(
                    "Test camera permission must be installed before setup starts");
        }
        cameraPermissionPort = java.util.Objects.requireNonNull(port, "port");
    }

    boolean usesSystemCameraPermissionPortForTest() {
        return cameraPermissionPort == SYSTEM_CAMERA_PERMISSION;
    }

    StreamDestination streamDestinationForTest() {
        return streamDestination;
    }

    /**
     * Detaches production asynchronous work before deterministic UI-contract instrumentation.
     * This package-private seam exposes no camera surface, frame, or production behavior toggle.
     */
    void installUiContractHarnessForTest(SetupUiListener harnessListener) {
        if (!scopeDisclosureAccepted) {
            throw new IllegalStateException(
                    "Scope disclosure must be accepted before UI-contract instrumentation");
        }
        uiContractHarnessInstalledForTest = true;
        listener = SetupUiListener.NO_OP;
        if (pendingCreation != null) {
            pendingCreation.cancel();
            pendingCreation = null;
        }
        if (sessionCoordinator != null) {
            sessionCoordinator.close();
            sessionCoordinator = null;
        }
        readiness = SetupReadinessState.initial().withCameraPermission(
                cameraPermissionPort.isGranted(this))
                .withDestinationConfigured(streamDestination != null);
        hostReselectionRequired = false;
        faceOverlay.showFaces(List.of(), null);
        setSetupUiListener(harnessListener);
        renderReadiness();
    }

    interface CameraPermissionPort {
        boolean isGranted(SetupActivity activity);

        void request(SetupActivity activity, int requestCode);
    }

}
