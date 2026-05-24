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

import com.google.api.services.youtube.YouTube;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import de.bushnaq.abdalla.youtube.dto.OldVersionStrategy;
import de.bushnaq.abdalla.youtube.dto.SyncAction;
import de.bushnaq.abdalla.youtube.service.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import com.vaadin.flow.component.dialog.Dialog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main Vaadin view for youtube-sync.
 *
 * <h2>Workflow</h2>
 * <ol>
 *   <li>Enter or browse to the video folder (drag a folder from Explorer onto the page,
 *       or use the native "Browse…" dialog).</li>
 *   <li>Click <strong>Load Plan</strong> to authenticate with YouTube (browser OAuth flow
 *       opens once) and build the sync plan.  The file grid shows the planned action for
 *       each video.</li>
 *   <li>Click <strong>Sync</strong> to execute all {@code UPLOAD} actions.  A progress bar
 *       shows overall completion; each grid row updates as its upload finishes.</li>
 * </ol>
 *
 * <p>{@code @Push} is required so that background-thread progress updates are pushed to
 * the browser in real time without polling.
 */
@Route("")
@PageTitle("YouTube Sync")
@Slf4j
public class MainView extends VerticalLayout {

    // -------------------------------------------------------------------------
    // UI fields
    // -------------------------------------------------------------------------

    /**
     * Opens a native OS folder-picker dialog.
     */
    // browseButton is constructed inline — no field needed
    @Autowired
    private       YouTubeClientFactory       clientFactory;
    /**
     * The plan built by the last "Load Plan" invocation.
     */
    private       List<SyncAction>           currentPlan;
    /**
     * The {@link SyncService} instance tied to the current plan (carries the quota tracker).
     */
    private       SyncService                currentService;
    /**
     * When checked, the plan is shown but no uploads are performed.
     */
    private final Checkbox                   dryRunCheckbox;
    /**
     * Text field displaying the currently selected video folder path.
     */
    private final TextField                  folderField;
    /**
     * Builds the sync plan by querying YouTube.
     */
    private final Button                     loadPlanButton;
    /**
     * Overall progress bar — advances once per completed action.
     */
    private final ProgressBar                overallProgressBar;
    /**
     * Label next to the progress bar showing "completed / total".
     */
    private final Span                       progressLabel;
    /**
     * Estimated daily API quota budget (units).
     */
    private final IntegerField               quotaBudgetField;
    /**
     * Selects what to do with the old video version after re-uploading.
     */
    private final Select<OldVersionStrategy> strategySelect;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------
    /**
     * Summary counts shown below the grid.
     */
    private final Span             summarySpan;
    /**
     * Executes the current plan.
     */
    private final Button           syncButton;
    /**
     * Displays one row per video file with its planned/actual action.
     */
    private final Grid<SyncAction> syncGrid;

    // -------------------------------------------------------------------------
    // Spring dependencies
    // -------------------------------------------------------------------------
    /**
     * Per-file upload state: {@code null} = not started/done; {@code 0–100} = percent; {@code -1} = completed.
     */
    private final ConcurrentHashMap<String, Integer> uploadProgressMap = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Constructs the main view and builds the complete component hierarchy.
     */
    public MainView() {
        log.info("hello");
        setPadding(true);
        setSpacing(true);
        setWidthFull();

        // --- Heading ---
        add(new H2("YouTube Sync"));

        // --- Folder row ---
        folderField = new TextField("Video folder");
        folderField.setPlaceholder("C:\\Users\\you\\Videos  (or drag a folder here)");
        folderField.setWidth("520px");

        Button browseButton = new Button("Browse…", _ -> openFolderChooser());

        HorizontalLayout folderRow = new HorizontalLayout(folderField, browseButton);
        folderRow.setAlignItems(Alignment.BASELINE);
        add(folderRow);

        // --- Settings row ---
        strategySelect = new Select<>();
        strategySelect.setLabel("Old version");
        strategySelect.setItems(OldVersionStrategy.values());
        strategySelect.setItemLabelGenerator(s -> s.name().charAt(0) + s.name().substring(1).toLowerCase());
        strategySelect.setValue(OldVersionStrategy.KEEP);
        strategySelect.setWidth("140px");

        quotaBudgetField = new IntegerField("Quota budget");
        quotaBudgetField.setValue(10_000);
        quotaBudgetField.setMin(0);
        quotaBudgetField.setWidth("130px");

        dryRunCheckbox = new Checkbox("Dry run");

        loadPlanButton = new Button("Load Plan", _ -> loadPlan());
        loadPlanButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        HorizontalLayout settingsRow = new HorizontalLayout(strategySelect, quotaBudgetField,
                dryRunCheckbox, loadPlanButton);
        settingsRow.setAlignItems(Alignment.BASELINE);
        add(settingsRow);

        // --- Grid ---
        syncGrid = new Grid<>(SyncAction.class, false);
        syncGrid.addColumn(SyncAction::filename)
                .setHeader("File")
                .setFlexGrow(3)
                .setSortable(true);
        syncGrid.addComponentColumn(this::buildActionBadge)
                .setHeader("Action")
                .setWidth("130px")
                .setFlexGrow(0);
        syncGrid.addColumn(a -> a.localVersion() >= 0 ? String.valueOf(a.localVersion()) : "—")
                .setHeader("Local")
                .setWidth("80px")
                .setFlexGrow(0);
        syncGrid.addColumn(a -> a.remoteVersion() >= 0 ? String.valueOf(a.remoteVersion()) : "—")
                .setHeader("Remote")
                .setWidth("80px")
                .setFlexGrow(0);
        syncGrid.addColumn(a -> a.errorMessage() != null ? a.errorMessage() : "")
                .setHeader("Note")
                .setFlexGrow(2);
        syncGrid.setHeight("420px");
        syncGrid.setWidthFull();
        add(syncGrid);

        // --- Sync row ---
        syncButton = new Button("Sync", _ -> runSync());
        syncButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SUCCESS);
        syncButton.setEnabled(false);

        overallProgressBar = new ProgressBar(0.0, 1.0);
        overallProgressBar.setWidth("360px");
        overallProgressBar.setVisible(false);

        progressLabel = new Span();
        progressLabel.setVisible(false);

        summarySpan = new Span();

        HorizontalLayout syncRow = new HorizontalLayout(syncButton, overallProgressBar, progressLabel);
        syncRow.setAlignItems(Alignment.BASELINE);
        syncRow.setSpacing(true);
        add(syncRow);
        add(summarySpan);

        // --- Drag-and-drop: try to intercept OS folder path from drag events ---
        installDragDropHandler();

        // --- Shut down the JVM when the browser tab is closed ---
        installUnloadHandler();
    }

    // -------------------------------------------------------------------------
    // Folder selection
    // -------------------------------------------------------------------------

    /**
     * Builds the coloured action badge component for a grid row.
     *
     * <p>While an upload is in progress the badge shows the current percentage.
     * Once the upload completes (signalled by {@code -1} in {@link #uploadProgressMap})
     * it shows "DONE ✓".  Otherwise the planned {@link SyncAction.Kind} is shown.
     *
     * @param action the action for this grid row
     * @return a {@link Span} styled as a status badge
     */
    private Span buildActionBadge(SyncAction action) {
        Integer progress = uploadProgressMap.get(action.filename());

        String label;
        String color;

        if (progress != null) {
            if (progress < 0) {
                label = "DONE ✓";
                color = "#16a34a"; // green-700
            } else {
                label = "↑ " + progress + "%";
                color = "#22c55e"; // green-500
            }
        } else {
            label = switch (action.kind()) {
                case UPLOAD -> "UPLOAD";
                case SKIP -> "SKIP";
                case ERROR -> "ERROR";
                case QUOTA -> "QUOTA";
            };
            color = switch (action.kind()) {
                case UPLOAD -> "#22c55e"; // green
                case SKIP -> "#60a5fa"; // blue
                case ERROR -> "#f87171"; // red
                case QUOTA -> "#fbbf24"; // amber
            };
        }

        Span span = new Span(label);
        span.getStyle()
                .set("color", color)
                .set("font-weight", "bold")
                .set("font-family", "monospace")
                .set("font-size", "0.85em");
        return span;
    }

    /**
     * Builds a {@link SyncProgressListener} that updates the grid and progress bar via
     * {@code UI.access()} for each upload-progress tick and each completed action.
     *
     * @param ui the Vaadin {@link UI} instance to dispatch updates to
     * @return a live listener bound to this view's state
     */
    private SyncProgressListener buildProgressListener(UI ui) {
        return new SyncProgressListener() {
            @Override
            public void onActionCompleted(int completed, int total, SyncAction action) {
                // Mark upload rows as done; remove in-progress marker for non-upload kinds.
                if (action.kind() == SyncAction.Kind.UPLOAD) {
                    uploadProgressMap.put(action.filename(), -1); // -1 = "DONE ✓"
                } else {
                    uploadProgressMap.remove(action.filename());
                }
                ui.access(() -> {
                    overallProgressBar.setValue((double) completed / total);
                    progressLabel.setText(completed + " / " + total);
                    syncGrid.getDataProvider().refreshAll();
                    if (currentPlan != null) {
                        updateSummary(currentPlan);
                    }
                });
            }

            @Override
            public void onUploadProgress(SyncAction action, int percent) {
                uploadProgressMap.put(action.filename(), percent);
                ui.access(() -> syncGrid.getDataProvider().refreshAll());
            }
        };
    }

    // -------------------------------------------------------------------------
    // Plan phase
    // -------------------------------------------------------------------------

    /**
     * Installs a page-level JavaScript drag-and-drop handler that attempts to extract a folder
     * path from OS drag events and writes it into {@link #folderField}.
     *
     * <p>Modern browsers block access to absolute file paths for security reasons, but on some
     * OS/browser combinations the {@code text/plain} or {@code text/uri-list} transfer data
     * contains the path when a folder is dragged from the system file manager.  This handler
     * tries both formats and falls back gracefully when neither yields a usable path.
     *
     * <p>The handler is attached to the {@code document} (not just the text field) so that the
     * user can drop the folder anywhere on the page.
     */
    private void installDragDropHandler() {
        getElement().executeJs("""
                document.addEventListener('dragover', e => {
                    e.preventDefault();
                    e.dataTransfer.dropEffect = 'copy';
                });
                document.addEventListener('drop', e => {
                    e.preventDefault();
                    // Try text/uri-list first (file:///C:/path format — works on some Linux desktops).
                    const uriList = e.dataTransfer.getData('text/uri-list');
                    if (uriList) {
                        const lines = uriList.split('\\n')
                            .map(l => l.trim())
                            .filter(l => l && !l.startsWith('#'));
                        if (lines.length > 0) {
                            let path = lines[0];
                            if (path.startsWith('file:///')) {
                                // Strip "file:///" and decode, convert forward-slashes to backslashes on Windows.
                                path = decodeURIComponent(path.substring(8)).replace(/\\//g, '\\\\');
                            } else if (path.startsWith('file://')) {
                                path = decodeURIComponent(path.substring(7));
                            }
                            $0.value = path;
                            $0.dispatchEvent(new Event('input', {bubbles: true}));
                            $0.dispatchEvent(new Event('change', {bubbles: true}));
                            return;
                        }
                    }
                    // Fallback: text/plain (may contain path on some Windows + browser combos).
                    const text = e.dataTransfer.getData('text/plain');
                    if (text && text.trim().length > 0) {
                        $0.value = text.trim();
                        $0.dispatchEvent(new Event('input', {bubbles: true}));
                        $0.dispatchEvent(new Event('change', {bubbles: true}));
                    }
                });
                """, folderField.getElement());
    }

    /**
     * Installs a page-level {@code beforeunload} JavaScript handler that calls
     * {@code GET /api/shutdown} when the browser tab is closed (or navigated away from).
     *
     * <p>{@code fetch} with {@code keepalive: true} is used so the browser completes the
     * HTTP request even while the page is being torn down.
     *
     * <p><strong>Note:</strong> a plain page refresh also fires {@code beforeunload}, so
     * refreshing the tab will exit the application — this is intentional for a desktop-mode
     * tool where the user is not expected to refresh.
     */
    private void installUnloadHandler() {
        getElement().executeJs("""
                window.addEventListener('beforeunload', () => {
                    fetch('/api/shutdown', { keepalive: true });
                });
                """);
    }

    // -------------------------------------------------------------------------
    // Execute phase
    // -------------------------------------------------------------------------

    /**
     * Triggered by "Load Plan".  Validates the folder, authenticates with YouTube (opening a
     * browser OAuth tab on first run), and calls {@link SyncService#planSync} on a virtual
     * thread.  The resulting plan is displayed in the grid.
     */
    private void loadPlan() {
        String folderPath = folderField.getValue().trim();
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
        summarySpan.setText("Loading plan…");
        uploadProgressMap.clear();

        UI ui = UI.getCurrent();
        Thread.ofVirtual().start(() -> {
            try {
                YouTube      youtube = clientFactory.build(folder);
                QuotaTracker tracker = new QuotaTracker(quotaBudgetField.getValue());
                SyncService service = new SyncService(
                        new YouTubeGatewayImpl(youtube),
                        strategySelect.getValue(),
                        tracker,
                        dryRunCheckbox.getValue(),
                        buildProgressListener(ui));

                List<SyncAction> plan = service.planSync(folder);

                ui.access(() -> {
                    currentPlan    = plan;
                    currentService = service;
                    uploadProgressMap.clear();
                    syncGrid.setItems(plan);
                    updateSummary(plan);
                    boolean hasUploads = plan.stream().anyMatch(a -> a.kind() == SyncAction.Kind.UPLOAD);
                    setButtonsEnabled(true, hasUploads && !dryRunCheckbox.getValue());
                });
            } catch (Exception ex) {
                log.error("Failed to load plan: {}", ex.getMessage(), ex);
                ui.access(() -> {
                    setButtonsEnabled(true, false);
                    summarySpan.setText("Plan failed.");
                    showError("Could not build plan: " + ex.getMessage());
                });
            }
        });
    }

    // -------------------------------------------------------------------------
    // Grid helpers
    // -------------------------------------------------------------------------

    /**
     * Opens a small Vaadin {@link Dialog} that lets the user type or paste a folder path.
     * This replaces the old {@code JFileChooser} approach, which fails in a headless
     * Spring Boot server environment ({@code java.awt.headless=true}).
     *
     * <p>The dialog is pre-filled with whatever is already in {@link #folderField}.
     * Pressing <strong>OK</strong> or hitting Enter validates that the path is a directory
     * and writes it back to {@link #folderField}.  Invalid paths show an inline error
     * message inside the dialog.
     */
    private void openFolderChooser() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Select video folder");
        dialog.setWidth("480px");

        TextField pathField = new TextField("Folder path");
        pathField.setWidthFull();
        pathField.setPlaceholder("C:\\Users\\you\\Videos");
        String current = folderField.getValue().trim();
        if (!current.isBlank()) {
            pathField.setValue(current);
        }

        Span errorSpan = new Span();
        errorSpan.getStyle().set("color", "var(--lumo-error-color)").set("font-size", "0.875em");

        VerticalLayout content = new VerticalLayout(pathField, errorSpan);
        content.setPadding(false);
        content.setSpacing(false);
        dialog.add(content);

        Button okButton = new Button("OK", _ -> {
            String entered = pathField.getValue().trim();
            if (entered.isBlank()) {
                errorSpan.setText("Path must not be empty.");
                return;
            }
            Path p = Path.of(entered);
            if (!Files.isDirectory(p)) {
                errorSpan.setText("Not a valid directory: " + entered);
                return;
            }
            folderField.setValue(entered);
            dialog.close();
        });
        okButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Button cancelButton = new Button("Cancel", _ -> dialog.close());

        dialog.getFooter().add(cancelButton, okButton);

        // Allow confirming with Enter key inside the text field.
        pathField.addKeyPressListener(com.vaadin.flow.component.Key.ENTER, _ -> okButton.click());

        dialog.open();
    }

    // -------------------------------------------------------------------------
    // Progress listener
    // -------------------------------------------------------------------------

    /**
     * Triggered by "Sync".  Executes the current plan on a virtual thread, updating the grid
     * and progress bar via {@link SyncProgressListener} events pushed to the browser.
     */
    private void runSync() {
        if (currentPlan == null || currentService == null) {
            showError("Load a plan first.");
            return;
        }

        setButtonsEnabled(false, false);
        overallProgressBar.setValue(0.0);
        overallProgressBar.setVisible(true);
        progressLabel.setText("0 / " + currentPlan.size());
        progressLabel.setVisible(true);

        List<SyncAction> plan    = currentPlan;
        SyncService      service = currentService;
        UI               ui      = UI.getCurrent();

        Thread.ofVirtual().start(() -> {
            try {
                service.executeSync(plan);
                ui.access(() -> {
                    setButtonsEnabled(true, false);   // plan consumed — disable Sync
                    progressLabel.setText("Done ✓");
                    Notification notification = Notification.show("Sync complete!", 3000,
                            Notification.Position.BOTTOM_CENTER);
                    notification.addThemeVariants(NotificationVariant.LUMO_SUCCESS);
                });
            } catch (Exception ex) {
                log.error("Sync failed: {}", ex.getMessage(), ex);
                ui.access(() -> {
                    setButtonsEnabled(true, false);
                    showError("Sync error: " + ex.getMessage());
                });
            }
        });
    }

    // -------------------------------------------------------------------------
    // Miscellaneous helpers
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
     * Shows a transient error notification at the top of the page.
     *
     * @param message the error text to display
     */
    private void showError(String message) {
        Notification n = Notification.show(message, 6000, Notification.Position.TOP_CENTER);
        n.addThemeVariants(NotificationVariant.LUMO_ERROR);
    }

    /**
     * Updates the summary {@link Span} with up-to-date counts from the plan.
     *
     * @param plan the current list of planned actions
     */
    private void updateSummary(List<SyncAction> plan) {
        long uploads = plan.stream().filter(a -> a.kind() == SyncAction.Kind.UPLOAD).count();
        long skips   = plan.stream().filter(a -> a.kind() == SyncAction.Kind.SKIP).count();
        long quotas  = plan.stream().filter(a -> a.kind() == SyncAction.Kind.QUOTA).count();
        long errors  = plan.stream().filter(a -> a.kind() == SyncAction.Kind.ERROR).count();
        summarySpan.setText(String.format(
                "Upload: %d   Skip: %d   Quota-deferred: %d   Error: %d",
                uploads, skips, quotas, errors));
    }
}

