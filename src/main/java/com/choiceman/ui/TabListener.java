package com.choiceman.ui;

import net.runelite.api.Client;
import net.runelite.api.events.VarClientIntChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Restores or preserves the Music tab’s UI state when the inventory tab selection changes.
 * When leaving the Music tab, it restores the override if active and shows top-row controls;
 * when entering the Music tab without the override, ensures top-row controls are visible.
 * <p>
 * All UI-mutating operations are posted to the client thread.
 */
@Singleton
public class TabListener {
    private static final int MUSIC_TAB = 13; // VarClientInt value when the Music tab is selected
    private static final int VARC_INV_TAB = 171; // Index of the VarClientInt controlling the inventory tab selector

    private final Client client;
    private final ClientThread clientThread;
    private final UnlocksWidgetController widgetController;

    @Inject
    public TabListener(Client client, ClientThread clientThread, UnlocksWidgetController widgetController) {
        this.client = client;
        this.clientThread = clientThread;
        this.widgetController = widgetController;
    }

    @Subscribe
    public void onVarClientIntChanged(VarClientIntChanged ev) {
        if (ev.getIndex() != VARC_INV_TAB) {
            return;
        }

        int newTab = client.getVarcIntValue(VARC_INV_TAB);

        if (newTab != MUSIC_TAB) {
            clientThread.invokeLater(() -> {
                if (widgetController.isOverrideActive()) {
                    widgetController.restore();
                }
                widgetController.restoreTopRowControls();
            });
        } else {
            // Entering Music tab
            if (!widgetController.isOverrideActive()) {
                clientThread.invokeLater(widgetController::restoreTopRowControls);
            }
        }
    }
}
