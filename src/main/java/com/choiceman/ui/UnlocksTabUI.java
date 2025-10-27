package com.choiceman.ui;

import net.runelite.client.eventbus.EventBus;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wires up the small Music-tab entry point:
 * registers the button and the tab listener with the client event bus,
 * and ensures the button’s widget is placed/removed at the right times.
 * <p>
 * Idempotent lifecycle: repeated startUp/shutDown calls are safe.
 */
@Singleton
public final class UnlocksTabUI {
    private final EventBus eventBus;
    private final MusicOpenButton button;
    private final TabListener tabListener;

    // Guard against double-register or double-unregister in edge cases
    private final AtomicBoolean registered = new AtomicBoolean(false);

    @Inject
    public UnlocksTabUI(EventBus eventBus, MusicOpenButton button, TabListener tabListener) {
        this.eventBus = eventBus;
        this.button = button;
        this.tabListener = tabListener;
    }

    /**
     * Registers listeners and shows the Music-tab icon.
     * Safe to call multiple times; no duplicate registrations will occur.
     */
    public void startUp() {
        if (!registered.compareAndSet(false, true)) {
            return; // already started
        }

        eventBus.register(button);
        eventBus.register(tabListener);

        // Schedule placing the icon; the button already defers to the client thread.
        button.onStart();
    }

    /**
     * Unregisters listeners and hides the Music-tab icon.
     * Safe to call multiple times.
     */
    public void shutDown() {
        if (!registered.compareAndSet(true, false)) {
            return; // not started
        }

        // Ensure the button hides itself before we drop listeners
        button.onStop();

        eventBus.unregister(tabListener);
        eventBus.unregister(button);
    }
}
