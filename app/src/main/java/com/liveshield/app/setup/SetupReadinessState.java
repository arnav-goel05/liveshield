package com.liveshield.app.setup;

/** Immutable fail-closed readiness inputs for the setup screen. */
public record SetupReadinessState(
        boolean cameraPermissionGranted,
        boolean freshHostSelected,
        boolean privacyReady,
        boolean destinationConfigured) {
    public static SetupReadinessState initial() {
        return new SetupReadinessState(false, false, false, false);
    }

    public SetupReadinessState withCameraPermission(boolean granted) {
        return new SetupReadinessState(
                granted, freshHostSelected, privacyReady, destinationConfigured);
    }

    public SetupReadinessState withFreshHostSelection(boolean selected) {
        return new SetupReadinessState(
                cameraPermissionGranted, selected, privacyReady, destinationConfigured);
    }

    public SetupReadinessState withPrivacyReady(boolean ready) {
        return new SetupReadinessState(
                cameraPermissionGranted, freshHostSelected, ready, destinationConfigured);
    }

    public SetupReadinessState withDestinationConfigured(boolean configured) {
        return new SetupReadinessState(
                cameraPermissionGranted, freshHostSelected, privacyReady, configured);
    }

    public boolean canStart() {
        return cameraPermissionGranted
                && freshHostSelected
                && privacyReady
                && destinationConfigured;
    }
}
