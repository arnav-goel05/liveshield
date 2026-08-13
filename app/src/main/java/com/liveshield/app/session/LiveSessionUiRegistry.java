package com.liveshield.app.session;

import com.liveshield.privacy.session.SessionState;
import java.util.Objects;

/**
 * Process-local, payload-free bridge between the session owner and creator-private status UI.
 *
 * <p>It owns no Context, destination, camera, surface, frame, encoder, or secret. Process death
 * therefore drops the control callback together with every media resource.</p>
 */
final class LiveSessionUiRegistry {
    private static Observer observer;
    private static Runnable stopAction;
    private static SessionState state;
    private static PublisherObserver publisherObserver;
    private static LiveSessionCoordinator.PublisherHealth publisherHealth =
            LiveSessionCoordinator.PublisherHealth.unconfigured();

    private LiveSessionUiRegistry() {
    }

    static synchronized void activate(SessionState initialState, Runnable requestedStop) {
        state = Objects.requireNonNull(initialState, "initialState");
        stopAction = Objects.requireNonNull(requestedStop, "requestedStop");
        notifyObserver();
    }

    static synchronized void update(SessionState updatedState) {
        state = Objects.requireNonNull(updatedState, "updatedState");
        if (updatedState == SessionState.ENDED || updatedState == SessionState.FAILED) {
            stopAction = null;
        }
        notifyObserver();
    }

    static synchronized boolean bind(Observer updatedObserver) {
        observer = Objects.requireNonNull(updatedObserver, "observer");
        if (state != null) {
            observer.onStateChanged(state);
            return true;
        }
        return false;
    }

    static synchronized void unbind(Observer existingObserver) {
        if (observer == existingObserver) {
            observer = null;
        }
    }

    static synchronized void updatePublisherHealth(
            LiveSessionCoordinator.PublisherHealth updatedHealth) {
        publisherHealth = Objects.requireNonNull(updatedHealth, "updatedHealth");
        if (publisherObserver != null) {
            publisherObserver.onPublisherHealthChanged(publisherHealth);
        }
    }

    static synchronized void bindPublisher(PublisherObserver updatedObserver) {
        publisherObserver = Objects.requireNonNull(updatedObserver, "updatedObserver");
        publisherObserver.onPublisherHealthChanged(publisherHealth);
    }

    static synchronized void unbindPublisher(PublisherObserver existingObserver) {
        if (publisherObserver == existingObserver) {
            publisherObserver = null;
        }
    }

    static synchronized void requestStop() {
        Runnable action = stopAction;
        if (action != null) {
            stopAction = null;
            action.run();
        }
    }

    static synchronized void resetForTest() {
        observer = null;
        stopAction = null;
        state = null;
        publisherObserver = null;
        publisherHealth = LiveSessionCoordinator.PublisherHealth.unconfigured();
    }

    private static void notifyObserver() {
        if (observer != null) {
            observer.onStateChanged(state);
        }
    }

    @FunctionalInterface
    interface Observer {
        void onStateChanged(SessionState state);
    }

    @FunctionalInterface
    interface PublisherObserver {
        void onPublisherHealthChanged(LiveSessionCoordinator.PublisherHealth health);
    }
}
