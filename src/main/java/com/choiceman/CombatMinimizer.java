package com.choiceman;

import com.choiceman.ui.ChoiceManOverlay;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Auto-minimizes the Choice Man overlay while "in combat".
 * When the local player either deals or receives a hitsplat, remember the current tick
 * and keep the overlay minimized for a 7-tick grace period.
 */
@Singleton
public final class CombatMinimizer {
    private static final int GRACE_TICKS = 5;
    private final Client client;
    private final ChoiceManOverlay overlay;
    private int lastCombatTick = Integer.MIN_VALUE;

    @Inject
    public CombatMinimizer(Client client, ChoiceManOverlay overlay) {
        this.client = client;
        this.overlay = overlay;
    }

    @Subscribe
    public void onHitsplatApplied(HitsplatApplied e) {
        final Player me = client.getLocalPlayer();
        if (me == null) return;

        final boolean iWasHit = (e.getActor() == me);
        final boolean iDealtIt = e.getHitsplat() != null && e.getHitsplat().isMine();
        if (iWasHit || iDealtIt) {
            lastCombatTick = client.getTickCount();
        }
    }

    @Subscribe
    public void onChoicesPresentedEvent(ChoiceManOverlay.ChoicesPresentedEvent ev) {
        if (!overlay.isActive()) return;
        final int ticksSince = client.getTickCount() - lastCombatTick;
        if (ticksSince <= GRACE_TICKS) {
            overlay.setMinimized(true);
        }
    }
}
