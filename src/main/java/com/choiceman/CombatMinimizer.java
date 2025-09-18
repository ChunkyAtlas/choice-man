package com.choiceman;

import com.choiceman.ui.ChoiceManOverlay;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Auto-minimizes the Choice Man overlay only at presentation time if "in combat".
 * You can always manually minimize/restore afterward.
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
        if (isInCombat()) {
            overlay.setMinimized(true);
        }
    }

    private boolean isInCombat() {
        final int ticksSince = client.getTickCount() - lastCombatTick;
        return ticksSince <= GRACE_TICKS;
    }
}
