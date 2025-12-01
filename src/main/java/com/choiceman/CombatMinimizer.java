package com.choiceman;

import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.client.eventbus.Subscribe;

import lombok.RequiredArgsConstructor;
import javax.inject.Singleton;

/**
 * Tracks recent combat based on hitsplats.
 * Other classes can query {@link #isInCombatNow()} to gate behavior.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @javax.inject.Inject)
public final class CombatMinimizer {
    private static final int GRACE_TICKS = 7;

    private final Client client;
    private volatile int lastCombatTick = Integer.MIN_VALUE;

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

    /**
     * True if seen a hitsplat in the last GRACE_TICKS.
     */
    public boolean isInCombatNow() {
        return client.getTickCount() - lastCombatTick <= GRACE_TICKS;
    }
}
