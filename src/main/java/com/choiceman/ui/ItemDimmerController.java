package com.choiceman.ui;

import com.choiceman.data.ChoiceManUnlocks;
import com.choiceman.data.ItemsRepository;
import com.choiceman.filters.EnsouledHeadMapping;
import com.choiceman.menus.EnabledUI;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.Map;

/**
 * Dims tracked item icon widgets whose Choice Man base is not usable.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class ItemDimmerController
{
    private final Client client;
    private final ChoiceManUnlocks unlocks;
    private final ItemsRepository itemsRepo;
    private final ItemManager itemManager;

    private final Map<Integer, Boolean> dimDecisionCache = new HashMap<>(256);

    private volatile int dimOpacity = 150;

    @Setter
    private volatile boolean enabled = true;

    public void setDimOpacity(int opacity)
    {
        this.dimOpacity = Math.max(0, Math.min(255, opacity));
    }

    private boolean isCollectionLogWidget(Widget widget)
    {
        return widget != null && (widget.getId() >>> 16) == 621;
    }

    @Subscribe
    public void onBeforeRender(BeforeRender event)
    {
        if (!enabled || client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        dimDecisionCache.clear();

        Widget[] roots = client.getWidgetRoots();
        if (roots == null)
        {
            return;
        }

        for (Widget root : roots)
        {
            if (root != null)
            {
                walkAndDim(root);
            }
        }
    }

    private void walkAndDim(Widget widget)
    {
        if (widget == null || widget.isHidden())
        {
            return;
        }

        int groupId = widget.getId() >>> 16;
        EnabledUI ui = EnabledUI.fromGroupId(groupId);
        if (ui != null && !ui.isGreyLockedItems())
        {
            walkChildren(widget);
            return;
        }

        if (isCollectionLogWidget(widget))
        {
            return;
        }

        int itemId = widget.getItemId();
        if (itemId > 0 && !isBankPlaceholderWidget(widget))
        {
            int targetOpacity = shouldDimMemoized(itemId) ? dimOpacity : 0;
            if (widget.getOpacity() != targetOpacity)
            {
                widget.setOpacity(targetOpacity);
            }
        }

        walkChildren(widget);
    }

    private void walkChildren(Widget widget)
    {
        Widget[] dynamicChildren = widget.getDynamicChildren();
        if (dynamicChildren != null)
        {
            for (Widget child : dynamicChildren)
            {
                walkAndDim(child);
            }
        }

        Widget[] staticChildren = widget.getStaticChildren();
        if (staticChildren != null)
        {
            for (Widget child : staticChildren)
            {
                walkAndDim(child);
            }
        }

        Widget[] nestedChildren = widget.getNestedChildren();
        if (nestedChildren != null)
        {
            for (Widget child : nestedChildren)
            {
                walkAndDim(child);
            }
        }
    }

    private boolean shouldDimMemoized(int rawItemId)
    {
        int canonical;
        try
        {
            int mapped = EnsouledHeadMapping.toTradeableId(rawItemId);
            canonical = itemManager.canonicalize(mapped);
        }
        catch (Exception ignored)
        {
            return false;
        }

        if (canonical <= 0)
        {
            return false;
        }

        Boolean cached = dimDecisionCache.get(canonical);
        if (cached != null)
        {
            return cached;
        }

        boolean result = shouldDimCanonical(canonical);
        dimDecisionCache.put(canonical, result);
        return result;
    }

    private boolean shouldDimCanonical(int canonicalItemId)
    {
        try
        {
            String base = itemsRepo.getBaseForId(canonicalItemId);
            if (base == null)
            {
                return false;
            }

            return !unlocks.isBaseUsable(base);
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    private boolean isBankPlaceholderWidget(Widget widget)
    {
        return widget != null && widget.getItemId() > 0 && widget.getItemQuantity() == 0;
    }
}
