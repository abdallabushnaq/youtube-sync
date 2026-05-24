/*
 * Copyright (C) 2025-2026 Abdalla Bushnaq
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.bushnaq.abdalla.youtube.ui;

import de.bushnaq.abdalla.youtube.dto.OldVersionStrategy;
import de.bushnaq.abdalla.youtube.dto.SyncAction;
import de.bushnaq.abdalla.youtube.service.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main Swing window for youtube-sync.
 *
 * <h2>Workflow</h2>
 * <ol>
 *   <li>Enter or browse to the video folder using the native {@link JFileChooser}.</li>
 *   <li>Click <strong>Load Plan</strong> to authenticate with YouTube (browser OAuth flow
 *       opens once) and build the sync plan.  The table shows the planned action for
 *       each video.</li>
 *   <li>Click <strong>Sync</strong> to execute all {@code UPLOAD} actions.  The progress bar
 *       shows overall completion; each table row updates as its upload finishes.</li>
 * </ol>
 *
 * <p>Long-running operations (plan and sync) run on virtual threads; UI updates are
 * dispatched back to the EDT via {@link SwingUtilities#invokeLater}.
 */
@Component
@Slf4j
public class MainFrame extends JFrame {

    // -------------------------------------------------------------------------
    // Table column indices
    // -------------------------------------------------------------------------
    private static final int COL_FILE    = 0;
    private static final int COL_ACTION  = 1;
    private static final int COL_LOCAL   = 2;
    private static final int COL_REMOTE  = 3;
    private static final int COL_NOTE    = 4;
    private static final int WINDOW_HEIGHT = 620;
    private static final int WINDOW_WIDTH  = 900;

    // -------------------------------------------------------------------------
    // Spring dependencies
    // -------------------------------------------------------------------------
    @Autowired
    private YouTubeClientFactory clientFactory;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------
    /**
     * The plan built by the last "Load Plan" invocation.
     */
    private List<SyncAction> currentPlan;
    /**
     * The {@link SyncService} instance tied to the current plan.
     */
    private SyncService currentService;
    /**
     * Per-file upload progress: {@code null} = not started; {@code 0–100} = percent;
     * {@code -1} = completed (DONE ✓).
     */
    private final ConcurrentHashMap<String, Integer> uploadProgressMap = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // UI fields
    // -------------------------------------------------------------------------
    /**
     * Text field showing the currently selected video folder path.
     */
    private final JTextField folderField;
    /**
     * Drop-down for old-version strategy.
     */
    private final JComboBox<OldVersionStrategy> strategyCombo;
    /**
     * Spinner for the daily API quota budget.
     */
    private final JSpinner quotaBudgetSpinner;
    /**
     * When checked the plan is shown but no uploads are performed.
     */
    private final JCheckBox dryRunCheckbox;
    /**
     * Builds the sync plan.
     */
    private final JButton loadPlanButton;
    /**
     * Executes the current plan.
     */
    private final JButton syncButton;
    /**
     * Displays one row per video file with its planned/actual action.
     */
    private final JTable syncTable;
    /**
     * Table model backing {@link #syncTable}.
     */
    private final SyncTableModel tableModel;
    /**
     * Overall progress bar — advances once per completed action.
     */
    private final JProgressBar overallProgressBar;
    /**
     * Label next to the progress bar showing "completed / total".
     */
    private final JLabel progressLabel;
    /**
     * Summary counts shown below the table.
     */
    private final JLabel summaryLabel;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Constructs the main window and builds the complete component hierarchy.
     */
    public MainFrame() {
        super("YouTube Sync");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
        setResizable(false);
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(new EmptyBorder(12, 12, 12, 12));
        setContentPane(root);
        installFolderDropHandler(root);

        // ---- Folder row ----
        folderField = new JTextField(40);
        folderField.setToolTipText("Absolute path to the folder containing video files and client_secret.json");
        JButton browseButton = new JButton("Browse…");
        browseButton.addActionListener(_ -> openFolderChooser());

        JPanel folderRow = new JPanel(new BorderLayout(6, 0));
        folderRow.add(new JLabel("Video folder:"), BorderLayout.WEST);
        folderRow.add(folderField, BorderLayout.CENTER);
        folderRow.add(browseButton, BorderLayout.EAST);

        // ---- Settings row ----
        strategyCombo = new JComboBox<>(OldVersionStrategy.values());
        strategyCombo.setSelectedItem(OldVersionStrategy.KEEP);
        strategyCombo.setToolTipText("What to do with the old video version after re-uploading");

        quotaBudgetSpinner = new JSpinner(new SpinnerNumberModel(10_000, 0, Integer.MAX_VALUE, 1_000));
        quotaBudgetSpinner.setToolTipText("Estimated daily YouTube Data API quota budget (free tier = 10 000)");
        ((JSpinner.DefaultEditor) quotaBudgetSpinner.getEditor()).getTextField().setColumns(7);

        dryRunCheckbox = new JCheckBox("Dry run");
        dryRunCheckbox.setToolTipText("Build the plan but skip all uploads");

        loadPlanButton = new JButton("Load Plan");
        loadPlanButton.addActionListener(_ -> loadPlan());

        JPanel settingsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        settingsRow.add(new JLabel("Old version:"));
        settingsRow.add(strategyCombo);
        settingsRow.add(new JLabel("Quota budget:"));
        settingsRow.add(quotaBudgetSpinner);
        settingsRow.add(dryRunCheckbox);
        settingsRow.add(loadPlanButton);

        // ---- Top panel ----
        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
        topPanel.add(folderRow);
        topPanel.add(Box.createVerticalStrut(8));
        topPanel.add(settingsRow);

        // ---- Table ----
        tableModel = new SyncTableModel();
        syncTable = new JTable(tableModel);
        syncTable.setFillsViewportHeight(true);
        syncTable.setRowHeight(22);
        syncTable.setAutoCreateRowSorter(true);
        syncTable.getColumnModel().getColumn(COL_FILE).setPreferredWidth(340);
        syncTable.getColumnModel().getColumn(COL_ACTION).setPreferredWidth(90);
        syncTable.getColumnModel().getColumn(COL_LOCAL).setPreferredWidth(60);
        syncTable.getColumnModel().getColumn(COL_REMOTE).setPreferredWidth(60);
        syncTable.getColumnModel().getColumn(COL_NOTE).setPreferredWidth(280);
        syncTable.getColumnModel().getColumn(COL_ACTION).setCellRenderer(new ActionCellRenderer());

        JScrollPane scrollPane = new JScrollPane(syncTable);

        // ---- Bottom panel ----
        syncButton = new JButton("Sync");
        syncButton.setEnabled(false);
        syncButton.addActionListener(_ -> runSync());

        overallProgressBar = new JProgressBar(0, 100);
        overallProgressBar.setStringPainted(true);
        overallProgressBar.setPreferredSize(new Dimension(300, 22));
        overallProgressBar.setVisible(false);

        progressLabel = new JLabel();
        progressLabel.setVisible(false);

        summaryLabel = new JLabel(" ");

        JPanel syncRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        syncRow.add(syncButton);
        syncRow.add(overallProgressBar);
        syncRow.add(progressLabel);

        JPanel bottomPanel = new JPanel();
        bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.Y_AXIS));
        bottomPanel.add(syncRow);
        bottomPanel.add(summaryLabel);

        // ---- Assemble ----
        root.add(topPanel, BorderLayout.NORTH);
        root.add(scrollPane, BorderLayout.CENTER);
        root.add(bottomPanel, BorderLayout.SOUTH);
    }

    // -------------------------------------------------------------------------
    // Drag-and-drop
    // -------------------------------------------------------------------------

    /**
     * Installs a {@link TransferHandler} on {@code target} that accepts folder drops from
     * the OS file manager (e.g. Windows Explorer).
     *
     * <p>The OS delivers dragged filesystem objects via {@link DataFlavor#javaFileListFlavor}.
     * If the first dropped item is a directory its absolute path is written into
     * {@link #folderField}; if it is a file, its parent directory is used instead.
     *
     * @param target the component on which drops should be accepted (typically the root panel)
     */
    private void installFolderDropHandler(JComponent target) {
        target.setTransferHandler(new TransferHandler() {

            /** {@inheritDoc} */
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            /** {@inheritDoc} */
            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) {
                    return false;
                }
                try {
                    List<java.io.File> files = (List<java.io.File>)
                            support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (files == null || files.isEmpty()) {
                        return false;
                    }
                    java.io.File dropped = files.getFirst();
                    // Accept both a dragged folder and a file inside a folder.
                    java.io.File folder = dropped.isDirectory() ? dropped : dropped.getParentFile();
                    if (folder != null) {
                        SwingUtilities.invokeLater(() -> folderField.setText(folder.getAbsolutePath()));
                        log.debug("Folder set via drag-and-drop: {}", folder.getAbsolutePath());
                        return true;
                    }
                } catch (Exception ex) {
                    log.warn("Drag-and-drop import failed: {}", ex.getMessage());
                }
                return false;
            }
        });
    }

    // -------------------------------------------------------------------------
    // Folder selection
    // -------------------------------------------------------------------------

    /**
     * Opens a native {@link JFileChooser} in directory-selection mode and writes
     * the chosen path into {@link #folderField}.
     */
    private void openFolderChooser() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select video folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);

        String current = folderField.getText().trim();
        if (!current.isBlank()) {
            Path hint = Path.of(current);
            if (Files.isDirectory(hint)) {
                chooser.setCurrentDirectory(hint.toFile());
            }
        }

        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            folderField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    // -------------------------------------------------------------------------
    // Plan phase
    // -------------------------------------------------------------------------

    /**
     * Triggered by "Load Plan".  Validates the folder, authenticates with YouTube (opening a
     * browser OAuth tab on first run), and calls {@link SyncService#planSync} on a virtual
     * thread.  The resulting plan is displayed in the table.
     */
    private void loadPlan() {
        String folderPath = folderField.getText().trim();
        if (folderPath.isBlank()) {
            showError("Please specify a video folder.");
            return;
        }
        Path folder = Path.of(folderPath);
        if (!Files.isDirectory(folder)) {
            showError("Not a valid directory: " + folderPath);
            return;
        }

        setButtonsEnabled(false, false);
        loadPlanButton.setText("Loading…");
        overallProgressBar.setIndeterminate(true);
        overallProgressBar.setVisible(true);
        progressLabel.setText("Building plan…");
        progressLabel.setVisible(true);
        summaryLabel.setText(" ");
        uploadProgressMap.clear();
        tableModel.setActions(List.of());

        Thread.ofVirtual().start(() -> {
            try {
                com.google.api.services.youtube.YouTube youtube = clientFactory.build(folder);
                QuotaTracker tracker = new QuotaTracker((Integer) quotaBudgetSpinner.getValue());
                SyncService service = new SyncService(
                        new YouTubeGatewayImpl(youtube),
                        (OldVersionStrategy) strategyCombo.getSelectedItem(),
                        tracker,
                        dryRunCheckbox.isSelected(),
                        buildProgressListener());

                List<SyncAction> plan = service.planSync(folder);

                SwingUtilities.invokeLater(() -> {
                    currentPlan    = plan;
                    currentService = service;
                    uploadProgressMap.clear();
                    tableModel.setActions(plan);
                    updateSummary(plan);
                    loadPlanButton.setText("Load Plan");
                    overallProgressBar.setIndeterminate(false);
                    overallProgressBar.setVisible(false);
                    progressLabel.setVisible(false);
                    boolean hasUploads = plan.stream().anyMatch(a -> a.kind() == SyncAction.Kind.UPLOAD);
                    setButtonsEnabled(true, hasUploads && !dryRunCheckbox.isSelected());
                });
            } catch (GeneralSecurityException ex) {
                log.error("Security error building YouTube client: {}", ex.getMessage(), ex);
                SwingUtilities.invokeLater(() -> finishLoadWithError("Security error: " + ex.getMessage()));
            } catch (Exception ex) {
                log.error("Failed to load plan: {}", ex.getMessage(), ex);
                SwingUtilities.invokeLater(() -> finishLoadWithError("Could not build plan: " + ex.getMessage()));
            }
        });
    }

    /**
     * Resets the UI after a failed "Load Plan" attempt and shows an error dialog.
     *
     * @param message the error detail to display
     */
    private void finishLoadWithError(String message) {
        loadPlanButton.setText("Load Plan");
        overallProgressBar.setIndeterminate(false);
        overallProgressBar.setVisible(false);
        progressLabel.setVisible(false);
        setButtonsEnabled(true, false);
        summaryLabel.setText("Plan failed.");
        showError(message);
    }

    // -------------------------------------------------------------------------
    // Progress listener
    // -------------------------------------------------------------------------

    /**
     * Builds a {@link SyncProgressListener} that updates the table and progress bar via
     * {@link SwingUtilities#invokeLater} for each upload-progress tick and each completed action.
     *
     * @return a live listener bound to this view's state
     */
    private SyncProgressListener buildProgressListener() {
        return new SyncProgressListener() {
            @Override
            public void onActionCompleted(int completed, int total, SyncAction action) {
                if (action.kind() == SyncAction.Kind.UPLOAD) {
                    uploadProgressMap.put(action.filename(), -1); // -1 = "DONE ✓"
                } else {
                    uploadProgressMap.remove(action.filename());
                }
                SwingUtilities.invokeLater(() -> {
                    int pct = (int) Math.round((double) completed / total * 100);
                    overallProgressBar.setValue(pct);
                    overallProgressBar.setString(completed + " / " + total);
                    progressLabel.setText(completed + " / " + total);
                    tableModel.fireTableDataChanged();
                    if (currentPlan != null) {
                        updateSummary(currentPlan);
                    }
                });
            }

            @Override
            public void onUploadProgress(SyncAction action, int percent) {
                uploadProgressMap.put(action.filename(), percent);
                SwingUtilities.invokeLater(tableModel::fireTableDataChanged);
            }
        };
    }

    // -------------------------------------------------------------------------
    // Execute phase
    // -------------------------------------------------------------------------

    /**
     * Triggered by "Sync".  Executes the current plan on a virtual thread, updating the table
     * and progress bar via {@link SyncProgressListener} events.
     */
    private void runSync() {
        if (currentPlan == null || currentService == null) {
            showError("Load a plan first.");
            return;
        }

        setButtonsEnabled(false, false);
        overallProgressBar.setValue(0);
        overallProgressBar.setString("0 / " + currentPlan.size());
        overallProgressBar.setVisible(true);
        progressLabel.setText("0 / " + currentPlan.size());
        progressLabel.setVisible(true);

        List<SyncAction> plan    = currentPlan;
        SyncService      service = currentService;

        Thread.ofVirtual().start(() -> {
            try {
                service.executeSync(plan);
                SwingUtilities.invokeLater(() -> {
                    setButtonsEnabled(true, false);  // plan consumed — disable Sync
                    progressLabel.setText("Done ✓");
                    JOptionPane.showMessageDialog(MainFrame.this,
                            "Sync complete!", "YouTube Sync", JOptionPane.INFORMATION_MESSAGE);
                });
            } catch (Exception ex) {
                log.error("Sync failed: {}", ex.getMessage(), ex);
                SwingUtilities.invokeLater(() -> {
                    setButtonsEnabled(true, false);
                    showError("Sync error: " + ex.getMessage());
                });
            }
        });
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Enables or disables the Load Plan and Sync buttons atomically.
     *
     * @param loadEnabled {@code true} to enable the Load Plan button
     * @param syncEnabled {@code true} to enable the Sync button
     */
    private void setButtonsEnabled(boolean loadEnabled, boolean syncEnabled) {
        loadPlanButton.setEnabled(loadEnabled);
        syncButton.setEnabled(syncEnabled);
    }

    /**
     * Shows a modal error dialog.
     *
     * @param message the error text to display
     */
    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "YouTube Sync — Error", JOptionPane.ERROR_MESSAGE);
    }

    /**
     * Updates {@link #summaryLabel} with up-to-date counts from the plan.
     *
     * @param plan the current list of planned actions
     */
    private void updateSummary(List<SyncAction> plan) {
        long uploads = plan.stream().filter(a -> a.kind() == SyncAction.Kind.UPLOAD).count();
        long skips   = plan.stream().filter(a -> a.kind() == SyncAction.Kind.SKIP).count();
        long quotas  = plan.stream().filter(a -> a.kind() == SyncAction.Kind.QUOTA).count();
        long errors  = plan.stream().filter(a -> a.kind() == SyncAction.Kind.ERROR).count();
        summaryLabel.setText(String.format(
                "Upload: %d   Skip: %d   Quota-deferred: %d   Error: %d",
                uploads, skips, quotas, errors));
    }

    // =========================================================================
    // Inner classes
    // =========================================================================

    /**
     * {@link AbstractTableModel} backed by a list of {@link SyncAction} records.
     *
     * <p>The Action column shows live upload-progress values while a sync is running,
     * by consulting {@link MainFrame#uploadProgressMap}.
     */
    private class SyncTableModel extends AbstractTableModel {

        /** Column headers. */
        private static final String[] COLUMNS = {"File", "Action", "Local", "Remote", "Note"};

        /** The current plan rows; never {@code null}. */
        private List<SyncAction> actions = new ArrayList<>();

        /**
         * Replaces the current row data and notifies the table.
         *
         * @param actions the new list of planned actions
         */
        public void setActions(List<SyncAction> actions) {
            this.actions = new ArrayList<>(actions);
            fireTableDataChanged();
        }

        /** {@inheritDoc} */
        @Override
        public int getRowCount() {
            return actions.size();
        }

        /** {@inheritDoc} */
        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        /** {@inheritDoc} */
        @Override
        public String getColumnName(int col) {
            return COLUMNS[col];
        }

        /** {@inheritDoc} */
        @Override
        public Object getValueAt(int row, int col) {
            SyncAction a = actions.get(row);
            return switch (col) {
                case COL_FILE   -> a.filename();
                case COL_ACTION -> actionLabel(a);
                case COL_LOCAL  -> a.localVersion() >= 0 ? String.valueOf(a.localVersion()) : "—";
                case COL_REMOTE -> a.remoteVersion() >= 0 ? String.valueOf(a.remoteVersion()) : "—";
                case COL_NOTE   -> a.errorMessage() != null ? a.errorMessage() : "";
                default         -> "";
            };
        }

        /**
         * Returns the display label for the Action column, reflecting live upload progress.
         *
         * @param action the sync action
         * @return a short label string
         */
        private String actionLabel(SyncAction action) {
            Integer progress = uploadProgressMap.get(action.filename());
            if (progress != null) {
                return progress < 0 ? "DONE ✓" : "↑ " + progress + "%";
            }
            return switch (action.kind()) {
                case UPLOAD -> "UPLOAD";
                case SKIP   -> "SKIP";
                case ERROR  -> "ERROR";
                case QUOTA  -> "QUOTA";
            };
        }
    }

    /**
     * Cell renderer that colours the Action column text to match the action kind.
     */
    private static class ActionCellRenderer extends DefaultTableCellRenderer {

        /** {@inheritDoc} */
        @Override
        public java.awt.Component getTableCellRendererComponent(
                JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int column) {

            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setHorizontalAlignment(SwingConstants.CENTER);
            setFont(getFont().deriveFont(Font.BOLD));

            if (!isSelected) {
                String text = value != null ? value.toString() : "";
                if (text.startsWith("UPLOAD") || text.startsWith("DONE") || text.startsWith("↑")) {
                    setForeground(new Color(0x4ade80)); // green
                } else if (text.equals("SKIP")) {
                    setForeground(new Color(0x60a5fa)); // blue
                } else if (text.equals("ERROR")) {
                    setForeground(new Color(0xf87171)); // red
                } else if (text.equals("QUOTA")) {
                    setForeground(new Color(0xfbbf24)); // amber
                } else {
                    setForeground(table.getForeground());
                }
            }
            return this;
        }
    }
}



