package com.choiceman.ui;

import com.choiceman.data.ChoiceManUnlocks;
import com.choiceman.data.ItemsRepository;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.PluginPanel;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

/**
 * Choice Man side panel UI that mirrors Chance Man’s look-and-feel.
 * Shows two list views of base names (unlocked and obtained) with a search box and filters.
 * Rows render a representative item icon and the base name only.
 */
public class ChoiceManPanel extends PluginPanel {
    /**
     * Caches a representative icon per base. Use a special sentinel to remember missing icons too.
     */
    private static final ImageIcon NO_ICON = new ImageIcon(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB));
    private static final Color PANEL_BG = new Color(37, 37, 37);
    private static final Color ROW_BG = new Color(60, 63, 65);
    private static final Color TEXT_DEFAULT = new Color(220, 220, 220);
    private final ItemsRepository repo;
    private final ChoiceManUnlocks unlocks;
    private final ItemManager itemManager;
    private final JPanel centerCardPanel = new JPanel(new CardLayout());
    private final DefaultListModel<String> unlockedModel = new DefaultListModel<>();
    private final JList<String> unlockedList = new JList<>(unlockedModel);
    private final DefaultListModel<String> obtainedModel = new DefaultListModel<>();
    private final JList<String> obtainedList = new JList<>(obtainedModel);
    private final JButton swapViewButton = new JButton("🔄");
    private final JToggleButton filterUnlockedNotObtButton = new JToggleButton("🔓");
    private final JToggleButton filterUnlockedAndObtButton = new JToggleButton("🔀");
    private final JLabel countLabel = new JLabel("");
    private final Map<String, ImageIcon> iconCache = new HashMap<>();
    private final Map<String, Integer> repIdCache = new HashMap<>();
    private String searchText = "";
    private boolean showingUnlocked = true;
    private Filter activeFilter = Filter.NONE;
    public ChoiceManPanel(ItemsRepository repo, ChoiceManUnlocks unlocks, ItemManager itemManager) {
        this.repo = repo;
        this.unlocks = unlocks;
        this.itemManager = itemManager;
        init();
    }

    private static void styleButton(JButton b) {
        b.setFocusPainted(false);
        b.setBackground(new Color(60, 63, 65));
        b.setForeground(Color.WHITE);
        b.setFont(new Font("SansSerif", Font.BOLD, 12));
        b.setPreferredSize(new Dimension(50, 30));
    }

    private static void styleToggle(JToggleButton b) {
        b.setFocusPainted(false);
        b.setBackground(new Color(60, 63, 65));
        b.setForeground(Color.WHITE);
        b.setFont(new Font("SansSerif", Font.BOLD, 12));
        b.setPreferredSize(new Dimension(50, 30));
    }

    private static void styleList(JList<String> list) {
        list.setVisibleRowCount(10);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    }

    private static JPanel titled(String title, Component content) {
        JPanel container = new JPanel(new BorderLayout());
        container.setOpaque(false);
        Border line = new LineBorder(new Color(80, 80, 80));
        Border empty = new EmptyBorder(5, 5, 5, 5);
        TitledBorder titled = BorderFactory.createTitledBorder(line, title);
        titled.setTitleColor(new Color(200, 200, 200));
        container.setBorder(new CompoundBorder(titled, empty));
        container.add(content, BorderLayout.CENTER);
        return container;
    }

    private void init() {
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(15, 15, 15, 15));
        setBackground(PANEL_BG);

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setOpaque(false);

        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        header.setOpaque(false);
        JLabel title = new JLabel("Choice Man");
        title.setFont(new Font("SansSerif", Font.BOLD, 18));
        title.setForeground(TEXT_DEFAULT);
        header.add(title);
        top.add(header);
        top.add(Box.createVerticalStrut(10));

        top.add(buildSearchBar());
        top.add(Box.createVerticalStrut(10));

        JPanel row = new JPanel(new GridLayout(1, 3, 10, 0));
        row.setOpaque(false);
        styleButton(swapViewButton);
        styleToggle(filterUnlockedNotObtButton);
        styleToggle(filterUnlockedAndObtButton);

        swapViewButton.setToolTipText("Swap between Unlocked and Obtained views");
        swapViewButton.addActionListener(e -> {
            showingUnlocked = !showingUnlocked;
            CardLayout cl = (CardLayout) centerCardPanel.getLayout();
            cl.show(centerCardPanel, showingUnlocked ? "UNLOCKED" : "OBTAINED");
            updatePanel();
        });

        filterUnlockedNotObtButton.setToolTipText("Filter: Unlocked ∧ Not Obtained");
        filterUnlockedNotObtButton.addActionListener(e -> {
            if (filterUnlockedNotObtButton.isSelected()) {
                activeFilter = Filter.UNLOCKED_NOT_OBTAINED;
                filterUnlockedAndObtButton.setSelected(false);
            } else activeFilter = Filter.NONE;
            updatePanel();
        });

        filterUnlockedAndObtButton.setToolTipText("Filter: Unlocked ∧ Obtained");
        filterUnlockedAndObtButton.addActionListener(e -> {
            if (filterUnlockedAndObtButton.isSelected()) {
                activeFilter = Filter.UNLOCKED_AND_OBTAINED;
                filterUnlockedNotObtButton.setSelected(false);
            } else activeFilter = Filter.NONE;
            updatePanel();
        });

        row.add(swapViewButton);
        row.add(filterUnlockedNotObtButton);
        row.add(filterUnlockedAndObtButton);
        top.add(row);
        top.add(Box.createVerticalStrut(10));
        add(top, BorderLayout.NORTH);

        styleList(unlockedList);
        styleList(obtainedList);

        JScrollPane unlockedScroll = new JScrollPane(unlockedList,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        unlockedScroll.setPreferredSize(new Dimension(250, 300));
        JPanel unlockedContainer = titled("Unlocked", unlockedScroll);

        JScrollPane obtainedScroll = new JScrollPane(obtainedList,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        obtainedScroll.setPreferredSize(new Dimension(250, 300));
        JPanel obtainedContainer = titled("Obtained", obtainedScroll);

        centerCardPanel.add(unlockedContainer, "UNLOCKED");
        centerCardPanel.add(obtainedContainer, "OBTAINED");
        ((CardLayout) centerCardPanel.getLayout()).show(centerCardPanel, "UNLOCKED");
        add(centerCardPanel, BorderLayout.CENTER);

        JPanel bottom = new JPanel();
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        bottom.setOpaque(false);

        JPanel countRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        countRow.setOpaque(false);
        countLabel.setFont(new Font("Arial", Font.BOLD, 11));
        countLabel.setForeground(TEXT_DEFAULT);
        countRow.add(countLabel);
        bottom.add(countRow);

        add(bottom, BorderLayout.SOUTH);

        unlockedList.setCellRenderer(new BaseCellRenderer());
        obtainedList.setCellRenderer(new BaseCellRenderer());

        updatePanel();
    }

    private JPanel buildSearchBar() {
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.setBorder(new EmptyBorder(5, 5, 5, 5));

        JPanel box = new JPanel(new BorderLayout());
        box.setBackground(new Color(30, 30, 30));
        box.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));

        JLabel icon = new JLabel("\uD83D\uDD0D");
        icon.setForeground(new Color(200, 200, 200));
        box.add(icon, BorderLayout.WEST);

        JTextField field = new JTextField();
        field.setBackground(new Color(45, 45, 45));
        field.setForeground(Color.WHITE);
        field.setBorder(null);
        field.setCaretColor(Color.WHITE);
        field.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyReleased(java.awt.event.KeyEvent e) {
                SwingUtilities.invokeLater(() -> {
                    searchText = field.getText().toLowerCase(Locale.ROOT);
                    updatePanel();
                });
            }
        });
        box.add(field, BorderLayout.CENTER);

        JLabel clear = new JLabel("❌");
        clear.setFont(new Font("SansSerif", Font.PLAIN, 9));
        clear.setForeground(Color.RED);
        clear.setBorder(new EmptyBorder(0, 6, 0, 6));
        clear.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        clear.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                field.setText("");
                searchText = "";
                updatePanel();
            }
        });
        box.add(clear, BorderLayout.EAST);

        wrap.add(box, BorderLayout.CENTER);
        return wrap;
    }

    /**
     * External hook from the plugin to refresh the lists after state changes.
     * Safe to call from any thread, updates are marshaled to the EDT. :contentReference[oaicite:1]{index=1}
     */
    public void refresh(ChoiceManUnlocks u) {
        updatePanel();
    }

    /**
     * Builds filtered snapshots off-EDT, then applies model updates on the EDT.
     */
    public void updatePanel() {
        List<String> unlocked = new ArrayList<>(unlocks.unlockedList());
        List<String> obtained = new ArrayList<>(unlocks.obtainedList());
        Set<String> unlockedSet = new HashSet<>(unlocked);
        Set<String> obtainedSet = new HashSet<>(obtained);

        if (!searchText.isEmpty()) {
            final String q = searchText;
            unlocked.removeIf(s -> !s.toLowerCase(Locale.ROOT).contains(q));
            obtained.removeIf(s -> !s.toLowerCase(Locale.ROOT).contains(q));
        }

        if (activeFilter == Filter.UNLOCKED_NOT_OBTAINED) {
            unlocked.removeIf(obtainedSet::contains);
            obtained.clear();
        } else if (activeFilter == Filter.UNLOCKED_AND_OBTAINED) {
            unlocked.removeIf(s -> !obtainedSet.contains(s));
            obtained.removeIf(s -> !unlockedSet.contains(s));
        }

        unlocked.sort(String::compareToIgnoreCase);
        obtained.sort(String::compareToIgnoreCase);

        SwingUtilities.invokeLater(() -> {
            unlockedModel.clear();
            obtainedModel.clear();
            for (String s : unlocked) unlockedModel.addElement(s);
            for (String s : obtained) obtainedModel.addElement(s);

            int totalBases = repo.getAllBases().size();
            countLabel.setText(showingUnlocked
                    ? "Unlocked: " + unlockedModel.size() + "/" + totalBases
                    : "Obtained: " + obtainedModel.size() + "/" + totalBases);

            CardLayout cl = (CardLayout) centerCardPanel.getLayout();
            cl.show(centerCardPanel, showingUnlocked ? "UNLOCKED" : "OBTAINED");
        });
    }

    /**
     * Returns a cached representative icon for a base using the smallest item id.
     * Caches misses with a sentinel to avoid repeated lookups.
     */
    private ImageIcon getBaseIcon(String base) {
        ImageIcon cached = iconCache.get(base);
        if (cached != null) return cached == NO_ICON ? null : cached;

        Set<Integer> ids = repo.getIdsForBase(base);
        if (ids == null || ids.isEmpty()) {
            iconCache.put(base, NO_ICON);
            return null;
        }

        int rep = repIdCache.computeIfAbsent(base, b -> ids.stream().min(Integer::compareTo).orElse(0));
        if (rep <= 0) {
            iconCache.put(base, NO_ICON);
            return null;
        }

        BufferedImage img = itemManager.getImage(rep, 1, false);
        if (img == null) {
            iconCache.put(base, NO_ICON);
            return null;
        }

        ImageIcon icon = new ImageIcon(img);
        iconCache.put(base, icon);
        return icon;
    }

    private enum Filter {
        NONE,
        UNLOCKED_NOT_OBTAINED,
        UNLOCKED_AND_OBTAINED
    }

    private class BaseCellRenderer extends JPanel implements ListCellRenderer<String> {
        private final JLabel iconLabel = new JLabel();
        private final JLabel nameLabel = new JLabel();

        BaseCellRenderer() {
            setLayout(new BorderLayout(6, 0));
            setOpaque(true);
            add(iconLabel, BorderLayout.WEST);
            add(nameLabel, BorderLayout.CENTER);
            nameLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        }

        @Override
        public Component getListCellRendererComponent(
                JList<? extends String> list,
                String base,
                int index,
                boolean isSelected,
                boolean cellHasFocus) {
            iconLabel.setIcon(getBaseIcon(base));
            nameLabel.setText(base);
            nameLabel.setToolTipText(base);
            iconLabel.setToolTipText(base);
            setToolTipText(base);

            if (isSelected) {
                setBackground(list.getSelectionBackground());
                setForeground(list.getSelectionForeground());
                nameLabel.setForeground(list.getSelectionForeground());
            } else {
                setBackground(ROW_BG);
                setForeground(TEXT_DEFAULT);
                nameLabel.setForeground(TEXT_DEFAULT);
            }
            return this;
        }
    }
}
