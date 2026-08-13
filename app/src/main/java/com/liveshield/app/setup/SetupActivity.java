package com.liveshield.app.setup;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Trace;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
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
import com.liveshield.transport.destination.StreamDestination;
import com.liveshield.video.geometry.FrameTransform;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Privacy-gated setup UI. Camera/render/session wiring is supplied later by T038/T042. */
public final class SetupActivity extends FragmentActivity
        implements SetupView,
        ScopeDisclosureFragment.Listener,
        StreamDestinationFragment.Listener {
    private static final int CAMERA_PERMISSION_REQUEST = 1001;
    private static final String SETUP_CREATE_TRACE = "LiveShieldSetupCreate";
    private static final String DISCLOSURE_ACCEPTED_STATE = "scopeDisclosureAccepted";
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
    private TextView permissionStatus;
    private TextView privacyStatus;
    private Button permissionButton;
    private Button startButton;
    private LiveSessionCoordinator sessionCoordinator;
    private SetupSessionFactory.PendingCreation pendingCreation;
    private boolean uiContractHarnessInstalledForTest;
    private boolean scopeDisclosureAccepted;
    private CameraPermissionPort cameraPermissionPort = SYSTEM_CAMERA_PERMISSION;
    private StreamDestination streamDestination;
    private EditText watchlistInput;
    private LinearLayout watchlistTermsContainer;
    private TextView watchlistStatus;
    private PrivacyZoneEditorView zoneEditor;
    private TextView zoneStatus;
    private Button zoneConfirmButton;
    private int selectedZoneIndex = -1;
    private boolean cameraTransformValidated;
    private FrameTransform privacyZoneTransform;
    private PrivacyConfigurationListener privacyConfigurationListener = ignored -> { };
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
            sanitizedPreviewContainer = findViewById(R.id.sanitized_preview_container);
            faceOverlay = findViewById(R.id.face_selection_overlay);
            permissionStatus = findViewById(R.id.camera_permission_status);
            privacyStatus = findViewById(R.id.privacy_readiness_status);
            permissionButton = findViewById(R.id.request_camera_permission);
            startButton = findViewById(R.id.start_protected_live);
            bindIndoorPrivacyControls();

            faceOverlay.setSelectionListener(trackId -> listener.onHostSelectionRequested(trackId));
            permissionButton.setOnClickListener(ignored -> requestCameraPermission());
            startButton.setOnClickListener(ignored -> {
                if (readiness.canStart()) {
                    listener.onStartRequested();
                }
            });
            scopeDisclosureAccepted = savedInstanceState != null
                    && savedInstanceState.getBoolean(DISCLOSURE_ACCEPTED_STATE, false);
            if (scopeDisclosureAccepted) {
                showSetupContent();
                refreshCameraPermission();
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
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onScopeDisclosureAccepted() {
        if (scopeDisclosureAccepted) {
            return;
        }
        scopeDisclosureAccepted = true;
        showSetupContent();
        refreshCameraPermission();
    }

    @Override
    public void onStreamDestinationConfigured(StreamDestination destination) {
        StreamDestination configured = java.util.Objects.requireNonNull(
                destination, "destination");
        if (!scopeDisclosureAccepted) {
            configured.close();
            throw new IllegalStateException("Scope disclosure must be accepted before destination");
        }
        readiness = readiness.withDestinationConfigured(true);
        if (sessionCoordinator != null) {
            sessionCoordinator.configurePublication(configured);
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
            coordinator.configurePublication(streamDestination);
            streamDestination = null;
        }
        setSetupUiListener(coordinator);
        coordinator.begin();
    }

    /** Displays fresh session-local tracks. Stale tracks remain non-selectable and do not confer readiness. */
    public void showSelectableFaces(List<SelectableFace> faces, Long selectedTrack) {
        boolean selectedFresh = FreshHostSelection.isSelectedAndFresh(faces, selectedTrack);
        readiness = readiness.withFreshHostSelection(selectedFresh);
        faceOverlay.showFaces(faces, selectedFresh ? selectedTrack : null);
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
        }
        renderReadiness();
    }

    @Override
    protected void onDestroy() {
        listener = SetupUiListener.NO_OP;
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
        privacyConfigurationListener = ignored -> { };
        cameraPermissionPort = SYSTEM_CAMERA_PERMISSION;
        super.onDestroy();
    }

    /** Immutable session-only policy configuration used by the production analysis graph. */
    public SessionPrivacyConfigurationView sessionPrivacyConfiguration() {
        return indoorPrivacySetup.snapshot();
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

    /** Installs a payload-contained observer for updating the in-memory OCR session configuration. */
    public void setPrivacyConfigurationListener(PrivacyConfigurationListener newListener) {
        privacyConfigurationListener = newListener == null ? ignored -> { } : newListener;
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
        cameraPermissionPort.request(this, CAMERA_PERMISSION_REQUEST);
    }

    private void refreshCameraPermission() {
        if (!scopeDisclosureAccepted) {
            return;
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
            permissionButton.setVisibility(granted ? View.GONE : View.VISIBLE);
            faceOverlay.setEnabled(granted);
            renderReadiness();
            if (granted) {
                ensureSessionCoordinator();
            }
        }
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
                    }
                });
    }

    private void showSetupContent() {
        scopeDisclosureContainer.setVisibility(View.GONE);
        setupContent.setVisibility(View.VISIBLE);
        if (getSupportFragmentManager().findFragmentByTag("stream-destination") == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(
                            R.id.stream_destination_container,
                            new StreamDestinationFragment(),
                            "stream-destination")
                    .commitNow();
        }
    }

    private void bindIndoorPrivacyControls() {
        watchlistInput = findViewById(R.id.watchlist_term_input);
        watchlistTermsContainer = findViewById(R.id.watchlist_terms_container);
        watchlistStatus = findViewById(R.id.watchlist_status);
        zoneEditor = findViewById(R.id.privacy_zone_editor_overlay);
        zoneStatus = findViewById(R.id.privacy_zone_status);
        zoneConfirmButton = findViewById(R.id.confirm_privacy_zones);
        findViewById(R.id.add_watchlist_term).setOnClickListener(ignored -> addWatchlistTerm());
        findViewById(R.id.toggle_zone_drawing).setOnClickListener(this::toggleZoneDrawing);
        zoneEditor.setZoneDrawListener(this::addDrawnZone);
        findViewById(R.id.previous_privacy_zone).setOnClickListener(
                ignored -> selectRelativeZone(-1));
        findViewById(R.id.next_privacy_zone).setOnClickListener(
                ignored -> selectRelativeZone(1));
        findViewById(R.id.remove_privacy_zone).setOnClickListener(
                ignored -> removeSelectedZone());
        zoneConfirmButton.setOnClickListener(ignored -> confirmZoneAlignment());
        renderWatchlist();
        renderZoneEditor();
    }

    private void addWatchlistTerm() {
        try {
            boolean added = indoorPrivacySetup.addWatchlistTerm(
                    watchlistInput.getText().toString());
            watchlistInput.getText().clear();
            watchlistStatus.setText(added
                    ? getResources().getQuantityString(R.plurals.watchlist_status_count,
                            indoorPrivacySetup.snapshot().normalizedWatchlistTerms().size(),
                            indoorPrivacySetup.snapshot().normalizedWatchlistTerms().size(),
                            IndoorPrivacySetupController.MAX_WATCHLIST_TERMS)
                    : getString(R.string.watchlist_status_duplicate));
            notifyWatchlistChanged();
            renderWatchlistRows();
        } catch (IllegalArgumentException exception) {
            watchlistStatus.setText(R.string.watchlist_status_invalid);
        } catch (IllegalStateException exception) {
            watchlistStatus.setText(R.string.watchlist_status_limit);
        }
    }

    private void renderWatchlist() {
        renderWatchlistRows();
        int count = indoorPrivacySetup.snapshot().normalizedWatchlistTerms().size();
        watchlistStatus.setText(count == 0
                ? getString(R.string.watchlist_status_empty)
                : getResources().getQuantityString(R.plurals.watchlist_status_count, count, count,
                        IndoorPrivacySetupController.MAX_WATCHLIST_TERMS));
    }

    private void renderWatchlistRows() {
        watchlistTermsContainer.removeAllViews();
        int position = 0;
        for (String term : indoorPrivacySetup.snapshot().normalizedWatchlistTerms()) {
            int itemNumber = ++position;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setSaveEnabled(false);
            TextView label = new TextView(this);
            label.setText(term);
            label.setTextColor(0xFFFFFFFF);
            label.setSaveEnabled(false);
            row.addView(label, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
            Button remove = new Button(this);
            remove.setText(R.string.remove_privacy_zone);
            remove.setContentDescription(getString(R.string.remove_watchlist_term, itemNumber));
            remove.setOnClickListener(ignored -> {
                indoorPrivacySetup.removeWatchlistTerm(term);
                notifyWatchlistChanged();
                renderWatchlist();
            });
            row.addView(remove);
            watchlistTermsContainer.addView(row);
        }
    }

    private void notifyWatchlistChanged() {
        Set<String> detached = Set.copyOf(
                indoorPrivacySetup.snapshot().normalizedWatchlistTerms());
        privacyConfigurationListener.onWatchlistChanged(detached);
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
            selectedZoneIndex = indoorPrivacySetup.configuredPrivacyZones().size() - 1;
            markEditedZoneUnsafe();
            // A touch-drawn rectangle is already defined directly in the currently validated
            // sanitized-output coordinate space. Commit it atomically so the fail-private policy
            // never blanks the very preview the creator needs to inspect. Numeric edits still
            // require the explicit confirmation control below.
            if (cameraTransformValidated) {
                confirmZoneAlignment();
            }
        } catch (IllegalArgumentException exception) {
            zoneStatus.setText(R.string.privacy_zone_status_invalid);
        } catch (IllegalStateException exception) {
            zoneStatus.setText(R.string.privacy_zone_status_limit);
        }
    }

    private void selectRelativeZone(int offset) {
        int count = indoorPrivacySetup.configuredPrivacyZones().size();
        if (count == 0) {
            selectedZoneIndex = -1;
        } else if (selectedZoneIndex < 0) {
            selectedZoneIndex = offset > 0 ? 0 : count - 1;
        } else {
            selectedZoneIndex = Math.floorMod(selectedZoneIndex + offset, count);
        }
        renderZoneEditor();
    }

    private void removeSelectedZone() {
        if (selectedZoneIndex < 0) {
            return;
        }
        indoorPrivacySetup.removePrivacyZone(selectedZoneIndex);
        int count = indoorPrivacySetup.configuredPrivacyZones().size();
        selectedZoneIndex = count == 0 ? -1 : Math.min(selectedZoneIndex, count - 1);
        if (count > 0) {
            markEditedZoneUnsafe();
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
        List<com.liveshield.privacy.model.NormalizedRect> configured =
                indoorPrivacySetup.configuredPrivacyZones();
        ArrayList<com.liveshield.privacy.model.NormalizedRect> sensorZones =
                new ArrayList<>(configured.size());
        for (com.liveshield.privacy.model.NormalizedRect outputZone : configured) {
            sensorZones.add(privacyZoneTransform.mapOutputRectToSensor(outputZone));
        }
        indoorPrivacySetup.applySafelyTransformedZones(sensorZones);
    }

    private void renderZoneEditor() {
        if (zoneEditor == null) {
            return;
        }
        List<com.liveshield.privacy.model.NormalizedRect> zones =
                indoorPrivacySetup.configuredPrivacyZones();
        if (selectedZoneIndex >= zones.size()) {
            selectedZoneIndex = zones.isEmpty() ? -1 : zones.size() - 1;
        }
        zoneEditor.showZones(zones, selectedZoneIndex);
        IndoorPrivacySetupController.Configuration snapshot = indoorPrivacySetup.snapshot();
        if (zones.isEmpty()) {
            zoneStatus.setText(R.string.privacy_zone_status_empty);
        } else if (snapshot.zonesSafelyTransformed()) {
            zoneStatus.setText(getResources().getQuantityString(
                    R.plurals.privacy_zone_status_safe, zones.size(), zones.size()));
        } else {
            zoneStatus.setText(getResources().getQuantityString(
                    R.plurals.privacy_zone_status_selected, zones.size(),
                    Math.max(1, selectedZoneIndex + 1), zones.size()));
        }
        zoneConfirmButton.setEnabled(
                !zones.isEmpty() && cameraTransformValidated
                        && !snapshot.zonesSafelyTransformed());
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

    @FunctionalInterface
    public interface PrivacyConfigurationListener {
        void onWatchlistChanged(Set<String> normalizedTerms);
    }

}
