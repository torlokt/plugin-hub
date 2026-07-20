package com.tyrsguard;

import net.runelite.client.ui.PluginPanel;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.ImageUtil;

@Slf4j
public class TyrsGuardPanel extends PluginPanel
{
    private final TyrsGuardConfig config;
    private final TyrsGuardPlugin plugin;

    // ── XP / Rank ─────────────────────────────────────────────────────────────
    private JLabel rankIconLabel;
    private JLabel xpLabel;
    private JLabel rankLabel;
    private JLabel prestigeLabel;
    private JLabel multiplierLabel;
    private JProgressBar rankProgressBar;
    private JLabel progressLabel;
    private JButton refreshXpButton;

    // ── Submission form ───────────────────────────────────────────────────────
    private JComboBox<String> topCategoryDropdown;
    private JComboBox<String> subCategoryDropdown;
    private JLabel subCategoryLabel;
    private JTextField donationGpField;
    private JLabel donationLabel;
    private JTextField raidPartnerField;
    private JLabel raidPartnerLabel;
    private JCheckBox staffPresentCheckbox;
    private JTextField staffNameField;
    private JLabel staffNameLabel;
    private JTextArea detailsArea;

    // ── Screenshot ────────────────────────────────────────────────────────────
    private BufferedImage capturedScreenshot;
    private JLabel screenshotPreviewLabel;
    private JButton screenshotButton;
    private JButton submitButton;
    private JLabel statusLabel;

    // ── Clan Coffer & Armory ──────────────────────────────────────────────────
    private JLabel cofferGpLabel;
    private JLabel cofferBondsLabel;
    private JComboBox<String> armoryCategoryDropdown;
    private JPanel  armoryItemsPanel;
    private JLabel  armoryStatusLabel;
    private JButton clanHallRefreshButton;
    private final java.util.Map<String, java.util.List<String>> armoryData = new java.util.HashMap<>();

    private static final String[] ARMORY_CATEGORIES = {
        "Select a category...",
        "Armor Sets",
        "Weapons",
        "Clue Items"
    };

    // ── Theme ─────────────────────────────────────────────────────────────────
    private static final int PAD = 8;
    private static final Color BG_DARK  = new Color(30, 30, 30);
    private static final Color BG_PANEL = new Color(42, 42, 42);
    private static final Color BG_INPUT = new Color(55, 55, 55);
    private static final Color COL_GOLD = new Color(200, 160, 80);
    private static final Color COL_TEXT = new Color(220, 220, 220);
    private static final Color COL_DIM  = new Color(140, 140, 140);
    private static final Color COL_LINK = new Color(100, 160, 255);

    private static final float FONT_HEADER = 16f;
    private static final float FONT_BODY   = 15f;
    private static final float FONT_SMALL  = 14f;

    // ── Submission categories ─────────────────────────────────────────────────
    private static final String[] TOP_CATEGORIES = {
        "Select a category...",
        "Clan Contributions",
        "Personal Progression"
    };

    private static final String[] CLAN_CONTRIBUTIONS = {
        "Select a type...",
        "Events Participation",
        "Events Win",
        "Donation",
        "Hosting a Mass",
        "Hosting an Event",
        "Teaching a Raid",
        "Mass Participation",
        "Hosting a Raid",
        "Raid Participation",
        "Learner Raid Participation",
        "Leagues Participation",
        "Deadman Participation"
    };

    // Auto-award categories removed (bot handles these automatically)
    private static final String[] PERSONAL_PROGRESSION = {
        "Select a type...",
        "Fire Cape",
        "Infernal Cape",
        "Quest Cape",
        "Diary Cape",
        "Music Cape",
        "Max Cape"
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Constructor
    // ─────────────────────────────────────────────────────────────────────────

    public TyrsGuardPanel(TyrsGuardConfig config, TyrsGuardPlugin plugin)
    {
        this.config = config;
        this.plugin = plugin;
        setLayout(new BorderLayout());
        setBackground(BG_DARK);

        JPanel wrapper = new JPanel(new GridBagLayout())
        {
            @Override public Dimension getPreferredSize()
            {
                Dimension d = super.getPreferredSize();
                d.width = Math.max(d.width, PluginPanel.PANEL_WIDTH);
                return d;
            }
        };
        wrapper.setBackground(BG_DARK);
        wrapper.setBorder(new EmptyBorder(PAD, PAD, PAD, PAD));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0; gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0; gbc.insets = new Insets(0, 0, 6, 0);

        wrapper.add(section("Tyrs Guard Clan",    headerPanel()),      gbc);
        wrapper.add(section("Your XP & Rank",     xpPanel()),          gbc);
        wrapper.add(section("Clan Coffer",         cofferPanel()),      gbc);
        wrapper.add(section("Clan Armory",         armoryPanel()),      gbc);
        wrapper.add(section("Submission Details",  formPanel()),        gbc);
        wrapper.add(section("Screenshot",          shotPanel()),        gbc);
        wrapper.add(submitPanel(),                                       gbc);

        gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH;
        wrapper.add(Box.createVerticalGlue(), gbc);

        JScrollPane scroll = new JScrollPane(wrapper,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(BG_DARK);
        add(scroll, BorderLayout.CENTER);

        // Initial load of coffer + armory data
        refreshClanHall();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Section card
    // ─────────────────────────────────────────────────────────────────────────

    private JPanel section(String title, JPanel inner)
    {
        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(BG_PANEL);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(60, 60, 60)),
            new EmptyBorder(6, PAD, PAD, PAD)));

        JLabel hdr = new JLabel(title);
        hdr.setForeground(COL_GOLD);
        hdr.setFont(hdr.getFont().deriveFont(Font.BOLD, FONT_HEADER));
        hdr.setBorder(new EmptyBorder(0, 0, 6, 0));
        card.add(hdr, BorderLayout.NORTH);
        card.add(inner, BorderLayout.CENTER);
        return card;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inner panels
    // ─────────────────────────────────────────────────────────────────────────

    private JPanel headerPanel()
    {
        JPanel p = inner();
        GridBagConstraints g = fillGbc();

        JLabel sub = lbl("Submit proof & earn clan XP", COL_TEXT, FONT_BODY);
        p.add(sub, g);

        g.insets = new Insets(8, 0, 0, 0);
        JPanel buttonRow = new JPanel(new GridLayout(1, 2, 6, 0));
        buttonRow.setBackground(BG_PANEL);

        JButton discordBtn = linkBtn("Discord", new Color(88, 101, 242));
        discordBtn.addActionListener(e -> openUrl("https://discord.gg/tyrsguard"));

        JButton websiteBtn = linkBtn("Website", new Color(180, 80, 0));
        websiteBtn.addActionListener(e -> openUrl("https://tyrsguard.com"));

        buttonRow.add(discordBtn);
        buttonRow.add(websiteBtn);
        p.add(buttonRow, g);

        g.insets = new Insets(6, 0, 0, 0);
        JButton supportBtn = linkBtn("Support The Clan", new Color(180, 140, 30));
        supportBtn.addActionListener(e -> openUrl("https://tyrsguard.com/support"));
        p.add(supportBtn, g);

        return p;
    }

    private JPanel xpPanel()
    {
        JPanel p = inner();
        GridBagConstraints g = fillGbc();

        // ── Rank icon ──
        rankIconLabel = new JLabel();
        rankIconLabel.setHorizontalAlignment(SwingConstants.CENTER);

        // ── Rank + XP labels ──
        rankLabel       = lbl("Rank: —",        COL_TEXT, Font.BOLD, FONT_HEADER);
        xpLabel         = lbl("XP: —",          COL_DIM,  FONT_BODY);
        multiplierLabel = lbl("Multiplier: —",  COL_TEXT, FONT_SMALL);
        prestigeLabel   = lbl("",               new Color(255, 215, 0), Font.BOLD, FONT_SMALL);
        prestigeLabel.setVisible(false);

        rankProgressBar = new JProgressBar(0, 100);
        rankProgressBar.setValue(0);
        rankProgressBar.setForeground(new Color(180, 0, 0));
        rankProgressBar.setBackground(new Color(55, 55, 55));
        rankProgressBar.setPreferredSize(new Dimension(0, 8));

        progressLabel = lbl("Set Discord ID in config", COL_TEXT, FONT_SMALL);

        refreshXpButton = btn("Refresh XP", new Color(60, 60, 60));
        refreshXpButton.addActionListener(e -> refreshXp());

        p.add(rankIconLabel,     g);
        g.insets = new Insets(4, 0, 0, 0);
        p.add(rankLabel,         g);
        g.insets = new Insets(2, 0, 0, 0);
        p.add(xpLabel,           g);
        p.add(multiplierLabel,   g);
        p.add(prestigeLabel,     g);
        g.insets = new Insets(6, 0, 0, 0);
        p.add(rankProgressBar,   g);
        g.insets = new Insets(2, 0, 0, 0);
        p.add(progressLabel,     g);
        g.insets = new Insets(8, 0, 0, 0);
        p.add(refreshXpButton,   g);

        return p;
    }

    private JPanel cofferPanel()
    {
        JPanel p = inner();
        GridBagConstraints g = fillGbc();

        p.add(lbl("The clan's shared wealth, synced with the website.", COL_DIM, FONT_SMALL), g);

        g.insets = new Insets(6, 0, 0, 0);
        cofferGpLabel = lbl("\uD83D\uDCB0 GP: \u2014", COL_GOLD, FONT_HEADER);
        cofferGpLabel.setFont(cofferGpLabel.getFont().deriveFont(Font.BOLD, FONT_HEADER));
        p.add(cofferGpLabel, g);

        g.insets = new Insets(4, 0, 0, 0);
        cofferBondsLabel = lbl("\uD83D\uDD17 Bonds: \u2014", COL_TEXT, FONT_BODY);
        p.add(cofferBondsLabel, g);

        return p;
    }

    private JPanel armoryPanel()
    {
        JPanel p = inner();
        GridBagConstraints g = fillGbc();

        p.add(lbl("High-value items the clan loans to members (Sapphire+).", COL_DIM, FONT_SMALL), g);

        g.insets = new Insets(6, 0, 0, 0);
        armoryCategoryDropdown = new JComboBox<>(ARMORY_CATEGORIES);
        styleCombo(armoryCategoryDropdown);
        armoryCategoryDropdown.addActionListener(e -> onArmoryCategoryChanged());
        p.add(armoryCategoryDropdown, g);

        g.insets = new Insets(6, 0, 0, 0);
        armoryItemsPanel = new JPanel(new GridBagLayout());
        armoryItemsPanel.setBackground(BG_PANEL);
        p.add(armoryItemsPanel, g);

        g.insets = new Insets(4, 0, 0, 0);
        armoryStatusLabel = lbl("", COL_DIM, FONT_SMALL);
        p.add(armoryStatusLabel, g);

        g.insets = new Insets(8, 0, 0, 0);
        clanHallRefreshButton = linkBtn("\u21BB Refresh Coffer & Armory", new Color(70, 70, 90));
        clanHallRefreshButton.addActionListener(e -> refreshClanHall());
        p.add(clanHallRefreshButton, g);

        return p;
    }

    private void onArmoryCategoryChanged()
    {
        String cat = (String) armoryCategoryDropdown.getSelectedItem();
        armoryItemsPanel.removeAll();
        GridBagConstraints g = fillGbc();

        if (cat != null && !cat.equals("Select a category..."))
        {
            java.util.List<String> items = armoryData.get(cat);
            if (items == null || items.isEmpty())
            {
                armoryItemsPanel.add(lbl("No items in this category.", COL_DIM, FONT_SMALL), g);
            }
            else
            {
                for (String item : items)
                {
                    g.insets = new Insets(2, 4, 2, 0);
                    armoryItemsPanel.add(lbl("\u2694 " + item, COL_TEXT, FONT_BODY), g);
                }
            }
        }
        armoryItemsPanel.revalidate();
        armoryItemsPanel.repaint();
        revalidate();
        repaint();
    }

    /**
     * Fetches coffer balance + categorized armory items from the bot.
     * Called on panel construction, on every login, and via the refresh button.
     */
    public void refreshClanHall()
    {
        String apiUrl = config.botApiUrl().trim();
        if (apiUrl.isEmpty() || config.pluginApiSecret().isEmpty())
        {
            SwingUtilities.invokeLater(() ->
                armoryStatusLabel.setText("Set Bot API URL + secret in config"));
            return;
        }
        if (clanHallRefreshButton != null) clanHallRefreshButton.setEnabled(false);

        new Thread(() -> {
            try
            {
                HttpURLConnection conn = (HttpURLConnection)
                    new URL(apiUrl + "/api/plugin/clanhall").openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("X-Plugin-Secret", config.pluginApiSecret());

                int code = conn.getResponseCode();
                if (code == 200)
                {
                    String body = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    long gp    = jsonLong(body, "coffer_gp");
                    long bonds = jsonLong(body, "coffer_bonds");

                    java.util.Map<String, java.util.List<String>> fresh = new java.util.HashMap<>();
                    fresh.put("Armor Sets", jsonStrArray(body, "Armor Sets"));
                    fresh.put("Weapons",    jsonStrArray(body, "Weapons"));
                    fresh.put("Clue Items", jsonStrArray(body, "Clue Items"));

                    SwingUtilities.invokeLater(() -> {
                        cofferGpLabel.setText("\uD83D\uDCB0 GP: " + String.format("%,d", gp));
                        cofferBondsLabel.setText("\uD83D\uDD17 Bonds: " + String.format("%,d", bonds));
                        armoryData.clear();
                        armoryData.putAll(fresh);
                        int total = fresh.values().stream().mapToInt(java.util.List::size).sum();
                        armoryStatusLabel.setText(total + " item" + (total == 1 ? "" : "s") + " available for loan");
                        onArmoryCategoryChanged(); // re-render current category with fresh data
                        if (clanHallRefreshButton != null) clanHallRefreshButton.setEnabled(true);
                    });
                }
                else
                {
                    SwingUtilities.invokeLater(() -> {
                        armoryStatusLabel.setText("Could not load (HTTP " + code + ")");
                        if (clanHallRefreshButton != null) clanHallRefreshButton.setEnabled(true);
                    });
                }
            }
            catch (Exception ex)
            {
                SwingUtilities.invokeLater(() -> {
                    armoryStatusLabel.setText("Could not reach the bot");
                    if (clanHallRefreshButton != null) clanHallRefreshButton.setEnabled(true);
                });
            }
        }, "TyrsGuard-ClanHall").start();
    }

    // ── Submission form ───────────────────────────────────────────────────────

    private JPanel formPanel()
    {
        JPanel p = inner();
        GridBagConstraints g = fillGbc();

        topCategoryDropdown = new JComboBox<>(TOP_CATEGORIES);
        styleCombo(topCategoryDropdown);
        topCategoryDropdown.addActionListener(e -> onTopCategoryChanged());

        subCategoryLabel = lbl("Submission Type:", COL_TEXT, FONT_BODY);
        subCategoryDropdown = new JComboBox<>(new String[]{ "Select a type..." });
        styleCombo(subCategoryDropdown);
        subCategoryDropdown.addActionListener(e -> onSubCategoryChanged());
        subCategoryLabel.setVisible(false);
        subCategoryDropdown.setVisible(false);

        donationLabel   = lbl("Donation Amount (GP):", COL_TEXT, FONT_BODY);
        donationGpField = input("e.g. 5000000");
        donationLabel.setVisible(false);
        donationGpField.setVisible(false);

        raidPartnerLabel = lbl("Username of member you taught/who taught you:", COL_TEXT, FONT_BODY);
        raidPartnerField = input("Enter their in-game / Discord username");
        raidPartnerLabel.setVisible(false);
        raidPartnerField.setVisible(false);

        staffPresentCheckbox = new JCheckBox("Staff was present");
        staffPresentCheckbox.setBackground(BG_PANEL);
        staffPresentCheckbox.setForeground(COL_TEXT);
        staffPresentCheckbox.setFont(staffPresentCheckbox.getFont().deriveFont(FONT_BODY));
        staffPresentCheckbox.setFocusPainted(false);
        staffPresentCheckbox.addActionListener(e -> onStaffChanged());
        staffPresentCheckbox.setVisible(false);

        staffNameLabel = lbl("Staff Member Name:", COL_TEXT, FONT_BODY);
        staffNameField = input("Enter staff name");
        staffNameLabel.setVisible(false);
        staffNameField.setVisible(false);

        detailsArea = new JTextArea(4, 0);
        detailsArea.setBackground(BG_INPUT);
        detailsArea.setForeground(COL_TEXT);
        detailsArea.setCaretColor(COL_TEXT);
        detailsArea.setFont(detailsArea.getFont().deriveFont(FONT_BODY));
        detailsArea.setLineWrap(true);
        detailsArea.setWrapStyleWord(true);
        detailsArea.setBorder(new EmptyBorder(4, 4, 4, 4));
        JScrollPane ds = new JScrollPane(detailsArea);
        ds.setBorder(BorderFactory.createLineBorder(new Color(70, 70, 70)));

        p.add(lbl("Category:", COL_TEXT, FONT_BODY), g);
        g.insets = new Insets(3, 0, 0, 0);
        p.add(topCategoryDropdown,   g);
        g.insets = new Insets(6, 0, 0, 0);
        p.add(subCategoryLabel,      g);
        g.insets = new Insets(3, 0, 0, 0);
        p.add(subCategoryDropdown,   g);
        g.insets = new Insets(6, 0, 0, 0);
        p.add(donationLabel,         g);
        g.insets = new Insets(3, 0, 0, 0);
        p.add(donationGpField,       g);
        g.insets = new Insets(6, 0, 0, 0);
        p.add(raidPartnerLabel,      g);
        g.insets = new Insets(3, 0, 0, 0);
        p.add(raidPartnerField,      g);
        g.insets = new Insets(6, 0, 0, 0);
        p.add(staffPresentCheckbox,  g);
        g.insets = new Insets(4, 0, 0, 0);
        p.add(staffNameLabel,        g);
        g.insets = new Insets(3, 0, 0, 0);
        p.add(staffNameField,        g);
        g.insets = new Insets(6, 0, 0, 0);
        p.add(lbl("Additional Details (optional):", COL_TEXT, FONT_BODY), g);
        g.insets = new Insets(3, 0, 0, 0);
        p.add(ds, g);
        return p;
    }

    private JPanel shotPanel()
    {
        JPanel p = inner();
        GridBagConstraints g = fillGbc();

        screenshotPreviewLabel = new JLabel("No screenshot captured");
        screenshotPreviewLabel.setForeground(COL_DIM);
        screenshotPreviewLabel.setFont(screenshotPreviewLabel.getFont().deriveFont(FONT_SMALL));
        screenshotPreviewLabel.setHorizontalAlignment(SwingConstants.CENTER);
        // Fixed height so screenshot preview never pushes submit button off screen
        screenshotPreviewLabel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - PAD * 4, 80));

        screenshotButton = btn("Capture Game Window", new Color(0, 110, 0));
        screenshotButton.addActionListener(e -> captureScreenshot());

        p.add(screenshotPreviewLabel, g);
        g.insets = new Insets(6, 0, 0, 0);
        p.add(screenshotButton, g);
        return p;
    }

    private JPanel submitPanel()
    {
        JPanel p = inner();
        p.setBackground(BG_DARK);
        GridBagConstraints g = fillGbc();

        statusLabel  = lbl(" ", COL_DIM, FONT_SMALL);
        submitButton = btn("Submit to Tyrs Guard Clan", new Color(160, 0, 0));
        submitButton.setFont(submitButton.getFont().deriveFont(Font.BOLD, FONT_HEADER));
        submitButton.addActionListener(e -> submit());

        p.add(statusLabel,  g);
        g.insets = new Insets(4, 0, 0, 0);
        p.add(submitButton, g);
        return p;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private JPanel inner()
    {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBackground(BG_PANEL);
        return p;
    }

    private GridBagConstraints fillGbc()
    {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0; g.gridy = GridBagConstraints.RELATIVE;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.weightx = 1.0;
        g.insets = new Insets(0, 0, 0, 0);
        return g;
    }

    private JLabel lbl(String text, Color color, float size)
    {
        JLabel l = new JLabel(text);
        l.setForeground(color);
        l.setFont(l.getFont().deriveFont(size));
        return l;
    }

    private JLabel lbl(String text, Color color, int style, float size)
    {
        JLabel l = new JLabel(text);
        l.setForeground(color);
        l.setFont(l.getFont().deriveFont(style, size));
        return l;
    }

    private JTextField input(String tooltip)
    {
        JTextField f = new JTextField();
        f.setBackground(BG_INPUT);
        f.setForeground(COL_TEXT);
        f.setCaretColor(COL_TEXT);
        f.setFont(f.getFont().deriveFont(FONT_BODY));
        f.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(70, 70, 70)),
            new EmptyBorder(4, 6, 4, 6)));
        f.setToolTipText(tooltip);
        return f;
    }

    private void styleCombo(JComboBox<?> c)
    {
        c.setBackground(BG_INPUT);
        c.setForeground(COL_TEXT);
        c.setFont(c.getFont().deriveFont(FONT_BODY));
    }

    private JButton btn(String text, Color bg)
    {
        JButton b = new JButton(text);
        b.setBackground(bg);
        b.setForeground(Color.WHITE);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setFont(b.getFont().deriveFont(FONT_BODY));
        return b;
    }

    private JButton linkBtn(String text, Color bg)
    {
        JButton b = new JButton(text);
        b.setBackground(bg);
        b.setForeground(Color.WHITE);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setFont(b.getFont().deriveFont(Font.BOLD, FONT_BODY));
        return b;
    }

    private void openUrl(String url)
    {
        try { Desktop.getDesktop().browse(new URI(url)); }
        catch (Exception e) { log.warn("Could not open URL: {}", url); }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Events
    // ─────────────────────────────────────────────────────────────────────────

    private void onTopCategoryChanged()
    {
        String top     = (String) topCategoryDropdown.getSelectedItem();
        boolean isClan     = "Clan Contributions".equals(top);
        boolean isPersonal = "Personal Progression".equals(top);
        boolean hasTop     = isClan || isPersonal;

        subCategoryDropdown.removeAllItems();
        if (isClan)     for (String s : CLAN_CONTRIBUTIONS)   subCategoryDropdown.addItem(s);
        else if (isPersonal) for (String s : PERSONAL_PROGRESSION) subCategoryDropdown.addItem(s);

        subCategoryLabel.setVisible(hasTop);
        subCategoryDropdown.setVisible(hasTop);

        staffPresentCheckbox.setVisible(isClan);
        if (!isClan) { staffPresentCheckbox.setSelected(false); staffNameLabel.setVisible(false); staffNameField.setVisible(false); }

        donationLabel.setVisible(false); donationGpField.setVisible(false); donationGpField.setText("");
        raidPartnerLabel.setVisible(false); raidPartnerField.setVisible(false); raidPartnerField.setText("");

        revalidate(); repaint();
    }

    private void onSubCategoryChanged()
    {
        String sub        = (String) subCategoryDropdown.getSelectedItem();
        boolean isDonation   = "Donation".equals(sub);
        boolean isTeaching   = "Teaching a Raid".equals(sub);
        boolean isLearner    = "Learner Raid Participation".equals(sub);
        boolean needsPartner = isTeaching || isLearner;

        donationLabel.setVisible(isDonation);
        donationGpField.setVisible(isDonation);
        raidPartnerLabel.setVisible(needsPartner);
        raidPartnerField.setVisible(needsPartner);
        if (needsPartner)
            raidPartnerLabel.setText(isTeaching ? "Username of member you taught:" : "Username of member who taught you:");

        revalidate(); repaint();
    }

    private void onStaffChanged()
    {
        boolean checked = staffPresentCheckbox.isSelected();
        staffNameLabel.setVisible(checked);
        staffNameField.setVisible(checked);
        revalidate(); repaint();
    }

    private void captureScreenshot()
    {
        screenshotButton.setEnabled(false);
        status("Capturing...", Color.YELLOW);
        plugin.requestScreenshot(img -> SwingUtilities.invokeLater(() -> {
            capturedScreenshot = img;
            if (img != null)
            {
                // Scale to fixed 80px height to avoid pushing submit button off screen
                int previewWidth  = PluginPanel.PANEL_WIDTH - PAD * 4;
                int previewHeight = 80;
                Image thumb = img.getScaledInstance(previewWidth, previewHeight, Image.SCALE_SMOOTH);
                screenshotPreviewLabel.setIcon(new ImageIcon(thumb));
                screenshotPreviewLabel.setText(null);
                status("Screenshot captured!", new Color(0, 200, 0));
            }
            else { status("Log in to the game first.", Color.RED); }
            screenshotButton.setEnabled(true);
        }));
    }

    private void submit()
    {
        String top = (String) topCategoryDropdown.getSelectedItem();
        if (top == null || top.equals("Select a category...")) { status("Please select a category.", Color.RED); return; }

        String category = (String) subCategoryDropdown.getSelectedItem();
        if (category == null || category.equals("Select a type...")) { status("Please select a submission type.", Color.RED); return; }

        if (capturedScreenshot == null) { status("Please capture a screenshot first.", Color.RED); return; }

        String gpStr = null;
        if ("Donation".equals(category))
        {
            String raw = donationGpField.getText().trim().replaceAll("[^0-9]", "");
            if (raw.isEmpty()) { status("Enter donation amount in GP.", Color.RED); return; }
            if (Long.parseLong(raw) < 100_000) { status("Minimum donation is 100,000 GP.", Color.RED); return; }
            gpStr = raw;
        }

        String discordId = config.discordId().trim();
        if (discordId.isEmpty()) { status("Set your Discord ID in config.", Color.RED); return; }
        String apiUrl = config.botApiUrl().trim();
        if (apiUrl.isEmpty()) { status("Set Bot API URL in config.", Color.RED); return; }

        String rsn       = plugin.getLocalPlayerName();
        String staffName = staffPresentCheckbox.isVisible() && staffPresentCheckbox.isSelected()
            ? staffNameField.getText().trim() : null;
        String details   = detailsArea.getText().trim();

        if (raidPartnerField.isVisible() && !raidPartnerField.getText().trim().isEmpty())
        {
            String partnerLabel = "Teaching a Raid".equals(category) ? "Taught member" : "Taught by";
            String partnerLine  = partnerLabel + ": " + raidPartnerField.getText().trim();
            details = details.isEmpty() ? partnerLine : details + "\n" + partnerLine;
        }

        submitButton.setEnabled(false);
        status("Submitting...", Color.YELLOW);

        final String fCat   = category;
        final String fGp    = gpStr;
        final String fStaff = (staffName != null && !staffName.isEmpty()) ? staffName : null;
        final String fDets  = details.isEmpty() ? "Submitted via RuneLite plugin" : details;
        final BufferedImage fShot = capturedScreenshot;

        new Thread(() -> {
            try
            {
                String boundary = UUID.randomUUID().toString().replace("-", "");
                HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl + "/submit").openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                conn.setRequestProperty("X-Plugin-Secret", config.pluginApiSecret());

                try (OutputStream out = conn.getOutputStream())
                {
                    field(out, boundary, "discordId",    discordId);
                    field(out, boundary, "rsn",          rsn != null ? rsn : "");
                    field(out, boundary, "category",     fCat);
                    field(out, boundary, "details",      fDets);
                    field(out, boundary, "staffPresent", fStaff != null ? "true" : "false");
                    if (fStaff != null) field(out, boundary, "staffName",  fStaff);
                    if (fGp    != null) field(out, boundary, "donationGp", fGp);
                    ByteArrayOutputStream imgBytes = new ByteArrayOutputStream();
                    ImageIO.write(fShot, "png", imgBytes);
                    filePart(out, boundary, "screenshot", "screenshot.png", "image/png", imgBytes.toByteArray());
                    out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                }

                int code = conn.getResponseCode();
                SwingUtilities.invokeLater(() -> {
                    if (code == 200)
                    {
                        status("Submitted! Staff will review soon.", new Color(0, 200, 0));
                        capturedScreenshot = null;
                        screenshotPreviewLabel.setIcon(null);
                        screenshotPreviewLabel.setText("No screenshot captured");
                        detailsArea.setText("");
                        donationGpField.setText("");
                        staffPresentCheckbox.setSelected(false);
                        staffNameLabel.setVisible(false);
                        staffNameField.setVisible(false);
                        topCategoryDropdown.setSelectedIndex(0);
                        subCategoryDropdown.removeAllItems();
                        subCategoryDropdown.addItem("Select a type...");
                        subCategoryLabel.setVisible(false);
                        subCategoryDropdown.setVisible(false);
                        staffPresentCheckbox.setVisible(false);
                        donationLabel.setVisible(false);
                        donationGpField.setVisible(false);
                        raidPartnerLabel.setVisible(false);
                        raidPartnerField.setVisible(false);
                        raidPartnerField.setText("");
                    }
                    else { status("Failed (HTTP " + code + "). Check config.", Color.RED); }
                    submitButton.setEnabled(true);
                });
            }
            catch (Exception e)
            {
                log.error("Submit error", e);
                SwingUtilities.invokeLater(() -> { status("Error: " + e.getMessage(), Color.RED); submitButton.setEnabled(true); });
            }
        }, "TyrsGuardClan-Submit").start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // XP refresh
    // ─────────────────────────────────────────────────────────────────────────

    public void refreshXp()
    {
        String discordId = config.discordId().trim();
        if (discordId.isEmpty()) { rankLabel.setText("Rank: —"); progressLabel.setText("Set Discord ID in config"); return; }
        refreshXpButton.setEnabled(false);
        new Thread(() -> {
            try
            {
                HttpURLConnection conn = (HttpURLConnection) new URL(config.botApiUrl().trim() + "/xp/" + discordId).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("X-Plugin-Secret", config.pluginApiSecret());
                int code = conn.getResponseCode();
                String body = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (code == 200)
                {
                    long   pts      = jsonLong(body, "points");
                    String rank     = jsonStr(body,  "rank");
                    String nextRank = jsonStr(body,  "nextRank");
                    long   toNext   = jsonLong(body, "pointsToNext");
                    long   nextReq  = jsonLong(body, "nextPointsReq");
                    long   curReq   = jsonLong(body, "currentPointsReq");
                    long   prestige = jsonLong(body, "prestigePoints");
                    String mult     = jsonStr(body,  "multiplier");

                    // Load rank icon
                    ImageIcon rankIcon = loadRankIcon(rank);

                    SwingUtilities.invokeLater(() -> {
                        // Update icon
                        if (rankIcon != null)
                        {
                            rankIconLabel.setIcon(rankIcon);
                            rankIconLabel.setText(null);
                        }
                        else
                        {
                            rankIconLabel.setIcon(null);
                            rankIconLabel.setText("[ " + (rank != null ? rank : "—") + " ]");
                            rankIconLabel.setForeground(COL_GOLD);
                        }

                        rankLabel.setText("Rank: " + (rank != null ? rank : "—"));
                        xpLabel.setText("XP: " + String.format("%,d", pts));

                        // Multiplier
                        multiplierLabel.setText("Multiplier: " + (mult != null && !mult.equals("null") ? mult + "x" : "1x"));
                        multiplierLabel.setVisible(true);

                        // Prestige
                        if (prestige > 0)
                        {
                            prestigeLabel.setText("⭐ Prestige Points: " + prestige);
                            prestigeLabel.setVisible(true);
                        }
                        else
                        {
                            prestigeLabel.setVisible(false);
                        }

                        if (nextRank != null && !nextRank.equals("null") && nextReq > 0)
                        {
                            long range = nextReq - curReq;
                            int  pct   = range > 0 ? (int)((pts - curReq) * 100 / range) : 100;
                            rankProgressBar.setValue(pct);
                            progressLabel.setText(String.format("%,d XP to %s", toNext, nextRank));
                        }
                        else { rankProgressBar.setValue(100); progressLabel.setText("Maximum rank reached!"); }

                        refreshXpButton.setEnabled(true);
                    });
                }
                else { SwingUtilities.invokeLater(() -> { progressLabel.setText("Check Discord ID & API URL"); refreshXpButton.setEnabled(true); }); }
            }
            catch (Exception e)
            {
                log.warn("XP error", e);
                SwingUtilities.invokeLater(() -> { progressLabel.setText("Bot not reachable"); refreshXpButton.setEnabled(true); });
            }
        }, "TyrsGuardClan-XP").start();
    }

    /**
     * Maps the bot's rank name to the correct PNG filename in /com/tyrsguard/ranks/
     */
    private String rankToFilename(String rank)
    {
        if (rank == null) return null;
        switch (rank)
        {
            case "Bronze":      return "bronze";
            case "Iron":        return "iron";
            case "Steel":       return "steel";
            case "Mithril":     return "mithril";
            case "Adamant":     return "adamant";
            case "Rune":        return "rune";
            case "Dragon":      return "dragon";
            case "Sapphire":    return "sapphire";
            case "Emerald":     return "emerald";
            case "Ruby":        return "ruby";
            case "Diamond":     return "diamond";
            case "Dragonstone": return "dragonstone";
            case "Onyx":        return "onyx";
            case "Legacy":      return "legacy";
            case "Zenyte":      return "zenyte";
            case "Staff":          return "staff";
            case "Leader":         return "leaderalt";
            case "Deputy Owner":   return "deputyowner";
            case "Owner":          return "owner";
            default: return rank.toLowerCase().replace(" ", "_");
        }
    }

    /**
     * Loads the rank icon PNG from /com/tyrsguard/ranks/ and scales to 32x32.
     */
    private ImageIcon loadRankIcon(String rank)
    {
        if (rank == null || rank.isEmpty()) return null;
        try
        {
            String filename = rankToFilename(rank);
            if (filename == null) return null;
            String path = "/com/tyrsguard/ranks/" + filename + ".png";
            BufferedImage img = ImageUtil.loadImageResource(getClass(), path);
            if (img == null) return null;
            Image scaled = img.getScaledInstance(32, 32, Image.SCALE_SMOOTH);
            return new ImageIcon(scaled);
        }
        catch (Exception e)
        {
            log.debug("No rank icon for: {}", rank);
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Multipart helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void field(OutputStream out, String b, String name, String val) throws IOException
    {
        out.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(val.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private void filePart(OutputStream out, String b, String field, String fname, String ct, byte[] data) throws IOException
    {
        out.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"" + field + "\"; filename=\"" + fname + "\"\r\nContent-Type: " + ct + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(data);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JSON helpers
    // ─────────────────────────────────────────────────────────────────────────

    private long jsonLong(String json, String key)
    {
        try { String p = "\"" + key + "\":"; int i = json.indexOf(p); if (i < 0) return 0; int s = i + p.length(); while (s < json.length() && json.charAt(s)==' ') s++; StringBuilder sb = new StringBuilder(); for (int j=s;j<json.length();j++){char c=json.charAt(j);if(Character.isDigit(c)||c=='-')sb.append(c);else break;} return sb.length()>0?Long.parseLong(sb.toString()):0; } catch(Exception e){return 0;}
    }

    private String jsonStr(String json, String key)
    {
        try { String p = "\"" + key + "\":\""; int i = json.indexOf(p); if (i < 0) return null; int s = i+p.length(); int e = json.indexOf("\"",s); return e>s?json.substring(s,e):null; } catch(Exception e){return null;}
    }


    /**
     * Extracts a JSON string array by key, e.g. "Weapons":["a","b"] -> [a, b].
     * Tolerates escaped quotes inside item names.
     */
    private java.util.List<String> jsonStrArray(String json, String key)
    {
        java.util.List<String> out = new java.util.ArrayList<>();
        try
        {
            String p = "\"" + key + "\":[";
            int i = json.indexOf(p);
            if (i < 0) return out;
            int s = i + p.length();
            int e = json.indexOf("]", s);
            if (e < 0) return out;
            String inner = json.substring(s, e).trim();
            if (inner.isEmpty()) return out;
            for (String part : inner.split("\",\""))
            {
                String item = part.replaceAll("^\"|\"$", "").replace("\\\"", "\"").trim();
                if (!item.isEmpty()) out.add(item);
            }
        }
        catch (Exception ignored) { }
        return out;
    }

    private void status(String msg, Color c) { statusLabel.setText(msg); statusLabel.setForeground(c); }
}
