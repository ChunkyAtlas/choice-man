package com.choiceman.ui;

import com.choiceman.data.ChoiceManUnlocks;
import com.choiceman.data.ItemsRepository;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.PluginPanel;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Choice Man side panel UI.
 * Shows a searchable list of item bases from unlocked/obtained state.
 */
public class ChoiceManPanel extends PluginPanel {
    private static final ImageIcon NO_ICON = new ImageIcon(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB));

    private static final Color PANEL_BG = new Color(37, 37, 37);
    private static final Color ROW_BG = new Color(60, 63, 65);
    private static final Color ROW_SELECTED_BG = new Color(75, 78, 80);
    private static final Color TEXT_DEFAULT = new Color(220, 220, 220);
    private static final Color BORDER_COLOR = new Color(80, 80, 80);
    private static final Color SEARCH_BG = new Color(30, 30, 30);
    private static final Color SEARCH_FIELD_BG = new Color(45, 45, 45);
    private static final Map<ListMode, String> MODE_TOOLTIPS = Map.of(
            ListMode.UNLOCKED, "Item bases you have unlocked.",
            ListMode.OBTAINED, "Item bases you have obtained.",
            ListMode.UNLOCKED_NOT_OBTAINED, "Item bases you have unlocked, but have not obtained yet.",
            ListMode.UNLOCKED_AND_OBTAINED, "Item bases that are both unlocked and obtained."
    );
    private final ItemsRepository repo;
    private final ChoiceManUnlocks unlocks;
    private final ItemManager itemManager;
    private final Map<String, ImageIcon> iconCache = new ConcurrentHashMap<>();
    private final Map<String, Integer> repIdCache = new ConcurrentHashMap<>();
    private final JLabel modeLabel = new JLabel("Unlocked Items");
    private final JTextField searchField = new JTextField();
    private final DefaultListModel<String> listModel = new DefaultListModel<>();
    private final JList<String> baseList = new JList<>(listModel);
    private final JLabel countLabel = new JLabel("0/0");
    private final JComboBox<ListMode> modeDropdown = new JComboBox<>(ListMode.values());
    private volatile ListMode listMode = ListMode.UNLOCKED;
    private volatile String searchText = "";

    public ChoiceManPanel(ItemsRepository repo, ChoiceManUnlocks unlocks, ItemManager itemManager) {
        this.repo = repo;
        this.unlocks = unlocks;
        this.itemManager = itemManager;
        init();
    }

    private void init() {
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(12, 12, 12, 12));
        setBackground(PANEL_BG);

        add(buildTop(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildBottom(), BorderLayout.SOUTH);

        baseList.setFixedCellHeight(36);
        baseList.setVisibleRowCount(-1);
        baseList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        baseList.setCellRenderer(new BaseCellRenderer());

        setMode(ListMode.UNLOCKED);
        updatePanel();
    }

    private JPanel buildTop() {
        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

        top.add(buildHeaderRow());
        top.add(Box.createVerticalStrut(10));
        top.add(buildDropdownRow());
        top.add(Box.createVerticalStrut(8));
        top.add(buildModeLabelRow());
        top.add(Box.createVerticalStrut(10));
        top.add(buildSearchBar());

        return top;
    }

    private JPanel buildHeaderRow() {
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setOpaque(false);

        JLabel iconLabel = new JLabel();
        try {
            java.net.URL iconUrl = getClass().getResource("/net/runelite/client/plugins/choiceman/icon.png");
            if (iconUrl != null) {
                iconLabel.setIcon(new ImageIcon(iconUrl));
            }
        } catch (Exception ignored) {
        }

        JLabel titleLabel = new JLabel("Choice Man", SwingConstants.CENTER);
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 18));
        titleLabel.setForeground(TEXT_DEFAULT);

        headerPanel.add(iconLabel, BorderLayout.WEST);
        headerPanel.add(titleLabel, BorderLayout.CENTER);

        return headerPanel;
    }

    private JPanel buildDropdownRow() {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);

        modeDropdown.setFocusable(false);
        modeDropdown.setBackground(ROW_BG);
        modeDropdown.setForeground(Color.WHITE);
        modeDropdown.setFont(new Font("SansSerif", Font.BOLD, 12));
        modeDropdown.setBorder(new CompoundBorder(
                new LineBorder(BORDER_COLOR),
                new EmptyBorder(2, 6, 2, 6))
        );

        modeDropdown.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list,
                    Object value,
                    int index,
                    boolean isSelected,
                    boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);

                label.setBorder(new EmptyBorder(4, 6, 4, 6));

                if (value instanceof ListMode) {
                    ListMode mode = (ListMode) value;
                    label.setText(index == -1 ? "Filter: " + mode.label() : mode.label());

                    String tooltip = MODE_TOOLTIPS.get(mode);
                    label.setToolTipText(tooltip);
                    modeDropdown.setToolTipText(tooltip);
                } else {
                    label.setToolTipText(null);
                }

                label.setBackground(isSelected ? ROW_SELECTED_BG : ROW_BG);
                label.setForeground(Color.WHITE);

                return label;
            }
        });

        modeDropdown.addActionListener(e ->
        {
            Object selected = modeDropdown.getSelectedItem();
            if (selected instanceof ListMode) {
                setMode((ListMode) selected);
                updatePanel();
            }
        });

        row.add(modeDropdown, BorderLayout.CENTER);
        return row;
    }

    private JPanel buildModeLabelRow() {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);

        modeLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        modeLabel.setForeground(new Color(210, 210, 210));

        row.add(modeLabel, BorderLayout.WEST);
        return row;
    }

    private JPanel buildSearchBar() {
        JPanel container = new JPanel(new BorderLayout());
        container.setOpaque(false);

        JPanel searchBox = new JPanel(new BorderLayout());
        searchBox.setBackground(SEARCH_BG);
        searchBox.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        JLabel icon = new JLabel("\uD83D\uDD0D");
        icon.setForeground(new Color(200, 200, 200));
        icon.setBorder(new EmptyBorder(0, 0, 0, 6));
        searchBox.add(icon, BorderLayout.WEST);

        searchField.setBackground(SEARCH_FIELD_BG);
        searchField.setForeground(Color.WHITE);
        searchField.setBorder(null);
        searchField.setCaretColor(Color.WHITE);

        searchField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                searchText = searchField.getText().toLowerCase(Locale.ROOT);
                updatePanel();
            }
        });

        searchBox.add(searchField, BorderLayout.CENTER);

        JButton clearButton = new JButton("x");
        clearButton.setFocusable(false);
        clearButton.setBorder(null);
        clearButton.setOpaque(false);
        clearButton.setContentAreaFilled(false);
        clearButton.setForeground(new Color(220, 80, 80));
        clearButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        clearButton.setToolTipText("Clear search");
        clearButton.addActionListener(e ->
        {
            searchField.setText("");
            searchText = "";
            updatePanel();
        });

        searchBox.add(clearButton, BorderLayout.EAST);

        container.add(searchBox, BorderLayout.CENTER);
        return container;
    }

    private JPanel buildCenter() {
        JScrollPane scroll = new JScrollPane(
                baseList,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        );
        scroll.setBorder(null);

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.setBorder(new CompoundBorder(
                new LineBorder(BORDER_COLOR),
                new EmptyBorder(6, 6, 6, 6))
        );

        wrap.add(scroll, BorderLayout.CENTER);
        return wrap;
    }

    private JPanel buildBottom() {
        JPanel bottom = new JPanel();
        bottom.setOpaque(false);
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));

        bottom.add(Box.createVerticalStrut(10));

        JPanel countPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        countPanel.setOpaque(false);

        countLabel.setFont(new Font("Arial", Font.BOLD, 11));
        countLabel.setForeground(TEXT_DEFAULT);

        countPanel.add(countLabel);
        bottom.add(countPanel);
        bottom.add(Box.createVerticalStrut(4));

        return bottom;
    }

    private void setMode(ListMode mode) {
        listMode = mode;

        String tooltip = MODE_TOOLTIPS.get(mode);
        modeDropdown.setToolTipText(tooltip);

        switch (mode) {
            case UNLOCKED:
                modeLabel.setText("Unlocked Items");
                break;

            case OBTAINED:
                modeLabel.setText("Obtained Items");
                break;

            case UNLOCKED_NOT_OBTAINED:
                modeLabel.setText("Unlocked, not Obtained");
                break;

            case UNLOCKED_AND_OBTAINED:
                modeLabel.setText("Unlocked and Obtained");
                break;
        }
    }

    /**
     * External hook from the plugin to refresh the list after state changes.
     */
    public void refresh(ChoiceManUnlocks u) {
        updatePanel();
    }

    /**
     * Builds filtered snapshots, then applies model updates on the EDT.
     */
    public void updatePanel() {
        final ListMode modeSnapshot = listMode;
        final String searchSnapshot = searchText;

        List<String> unlocked = new ArrayList<>(unlocks.unlockedList());
        List<String> obtained = new ArrayList<>(unlocks.obtainedList());

        Set<String> unlockedSet = new HashSet<>(unlocked);
        Set<String> obtainedSet = new HashSet<>(obtained);

        List<String> base = new ArrayList<>();

        switch (modeSnapshot) {
            case UNLOCKED:
                base.addAll(unlocked);
                break;

            case OBTAINED:
                base.addAll(obtained);
                break;

            case UNLOCKED_NOT_OBTAINED:
                base.addAll(unlocked);
                base.removeIf(obtainedSet::contains);
                break;

            case UNLOCKED_AND_OBTAINED:
                base.addAll(unlocked);
                base.removeIf(s -> !obtainedSet.contains(s));
                break;
        }

        if (searchSnapshot != null && !searchSnapshot.isEmpty()) {
            base.removeIf(s -> !s.toLowerCase(Locale.ROOT).contains(searchSnapshot));
        }

        base.sort(String::compareToIgnoreCase);

        int totalBases = repo.getAllBases().size();

        SwingUtilities.invokeLater(() ->
        {
            listModel.clear();

            for (String s : base) {
                listModel.addElement(s);
            }

            countLabel.setText(base.size() + "/" + totalBases);

            baseList.revalidate();
            baseList.repaint();
        });
    }

    /**
     * Returns a cached representative icon for a base using the smallest item id.
     * Caches misses with a sentinel to avoid repeated lookups.
     */
    private ImageIcon getBaseIcon(String base) {
        ImageIcon cached = iconCache.get(base);
        if (cached != null) {
            return cached == NO_ICON ? null : cached;
        }

        Set<Integer> ids = repo.getIdsForBase(base);
        if (ids == null || ids.isEmpty()) {
            iconCache.put(base, NO_ICON);
            return null;
        }

        int representativeId = repIdCache.computeIfAbsent(base, b -> ids.stream().min(Integer::compareTo).orElse(0));
        if (representativeId <= 0) {
            iconCache.put(base, NO_ICON);
            return null;
        }

        BufferedImage img = itemManager.getImage(representativeId, 1, false);
        if (img == null) {
            iconCache.put(base, NO_ICON);
            return null;
        }

        ImageIcon icon = new ImageIcon(img);
        iconCache.put(base, icon);
        return icon;
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        int viewportHeight = getViewportHeight();

        if (viewportHeight > 0 && d.height < viewportHeight) {
            return new Dimension(d.width, viewportHeight);
        }

        return d;
    }

    private int getViewportHeight() {
        Container parent = getParent();

        while (parent != null && !(parent instanceof JViewport)) {
            parent = parent.getParent();
        }

        if (parent != null) {
            return Math.max(((JViewport) parent).getHeight(), 0);
        }

        return 0;
    }

    private enum ListMode {
        UNLOCKED("Unlocked"),
        OBTAINED("Obtained"),
        UNLOCKED_NOT_OBTAINED("Unlocked, not Obtained"),
        UNLOCKED_AND_OBTAINED("Unlocked and Obtained");

        private final String label;

        ListMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private class BaseCellRenderer extends JPanel implements ListCellRenderer<String> {
        private final JLabel iconLabel = new JLabel();
        private final JLabel nameLabel = new JLabel();

        BaseCellRenderer() {
            setLayout(new BorderLayout(8, 0));
            setOpaque(true);

            iconLabel.setPreferredSize(new Dimension(32, 32));

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
            iconLabel.setToolTipText(base);

            nameLabel.setText(base);
            nameLabel.setToolTipText(base);

            setToolTipText(base);
            setBackground(isSelected ? ROW_SELECTED_BG : ROW_BG);
            nameLabel.setForeground(TEXT_DEFAULT);

            return this;
        }
    }
}