/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2017 hsz Jakub Chrzanowski <jakub@hsz.mobi>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package mobi.hsz.idea.gitignore;

import com.intellij.dvcs.repo.Repository;
import com.intellij.dvcs.repo.VcsRepositoryManager;
import com.intellij.ide.projectView.ProjectView;
import com.intellij.ide.projectView.impl.AbstractProjectViewPane;
import com.intellij.openapi.components.AbstractProjectComponent;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.FileStatusManager;
import com.intellij.openapi.vfs.*;
import com.intellij.util.containers.HashMap;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.messages.Topic;
import git4idea.repo.GitRepository;
import mobi.hsz.idea.gitignore.file.type.IgnoreFileType;
import mobi.hsz.idea.gitignore.file.type.kind.GitExcludeFileType;
import mobi.hsz.idea.gitignore.indexing.ExternalIndexableSetContributor;
import mobi.hsz.idea.gitignore.indexing.IgnoreEntryOccurrence;
import mobi.hsz.idea.gitignore.indexing.IgnoreFilesIndex;
import mobi.hsz.idea.gitignore.settings.IgnoreSettings;
import mobi.hsz.idea.gitignore.util.Debounced;
import mobi.hsz.idea.gitignore.util.InterruptibleScheduledFuture;
import mobi.hsz.idea.gitignore.util.Utils;
import mobi.hsz.idea.gitignore.util.exec.ExternalExec;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.regex.Pattern;

import static mobi.hsz.idea.gitignore.IgnoreManager.RefreshTrackedIgnoredListener.TRACKED_IGNORED_REFRESH;
import static mobi.hsz.idea.gitignore.IgnoreManager.TrackedIgnoredListener.TRACKED_IGNORED;
import static mobi.hsz.idea.gitignore.settings.IgnoreSettings.KEY;

/**
 * {@link IgnoreManager} handles ignore files indexing and status caching.
 *
 * @author Jakub Chrzanowski <jakub@hsz.mobi>
 * @since 1.0
 */
public class IgnoreManager extends AbstractProjectComponent implements DumbAware {
    /** {@link VirtualFileManager} instance. */
    @NotNull
    private final VirtualFileManager virtualFileManager;

    /** {@link IgnoreSettings} instance. */
    @NotNull
    private final IgnoreSettings settings;

    /** {@link FileStatusManager} instance. */
    @NotNull
    private final FileStatusManager statusManager;

    /** {@link RefreshTrackedIgnoredRunnable} instance. */
    @NotNull
    private final RefreshTrackedIgnoredRunnable refreshTrackedIgnoredRunnable;

    /** {@link MessageBusConnection} instance. */
    @Nullable
    private MessageBusConnection messageBus;

    /** List of the files that are ignored and also tracked by Git. */
    @NotNull
    private final HashMap<VirtualFile, Repository> confirmedIgnoredFiles = new HashMap<VirtualFile, Repository>();

    /** {@link FileStatusManager#fileStatusesChanged()} method wrapped with {@link Debounced}. */
    private final Debounced debouncedStatusesChanged = new Debounced(1000) {
        @Override
        protected void task(@Nullable Object argument) {
            statusManager.fileStatusesChanged();
        }
    };

    /** {@link FileStatusManager#fileStatusesChanged()} method wrapped with {@link Debounced}. */
    private final Debounced<Boolean> debouncedRefreshTrackedIgnores = new Debounced<Boolean>(5000) {
        @Override
        protected void task(@Nullable Boolean refresh) {
            if (Boolean.TRUE.equals(refresh)) {
                refreshTrackedIgnoredRunnable.refresh();
            } else {
                refreshTrackedIgnoredRunnable.run();
            }
        }
    };

    /** Scheduled feature connected with {@link #debouncedStatusesChanged}. */
    @NotNull
    private final InterruptibleScheduledFuture statusesChangedScheduledFeature;

    /** Scheduled feature connected with {@link #debouncedRefreshTrackedIgnores}. */
    @NotNull
    private final InterruptibleScheduledFuture refreshTrackedIgnoredFeature;

    /** {@link IgnoreManager} working flag. */
    private boolean working;

    /** {@link VirtualFileListener} instance to check if file's content was changed. */
    @NotNull
    private final VirtualFileListener virtualFileListener = new VirtualFileAdapter() {
        @Override
        public void contentsChanged(@NotNull VirtualFileEvent event) {
            if (event.getFile().getFileType() instanceof IgnoreFileType) {
                debouncedStatusesChanged.run();
                debouncedRefreshTrackedIgnores.run();
            }
        }
    };

    /** {@link IgnoreSettings} listener to watch changes in the plugin's settings. */
    @NotNull
    private final IgnoreSettings.Listener settingsListener = new IgnoreSettings.Listener() {
        @Override
        public void onChange(@NotNull KEY key, Object value) {
            switch (key) {

                case IGNORED_FILE_STATUS:
                    toggle((Boolean) value);
                    break;

                case OUTER_IGNORE_RULES:
                case LANGUAGES:
                    if (isEnabled()) {
                        if (working) {
                            debouncedStatusesChanged.run();
                            debouncedRefreshTrackedIgnores.run();
                        } else {
                            enable();
                        }
                    }
                    break;

                case HIDE_IGNORED_FILES:
                    ProjectView.getInstance(myProject).refresh();
                    break;

            }
        }
    };

    /**
     * Returns {@link IgnoreManager} service instance.
     *
     * @param project current project
     * @return {@link IgnoreManager instance}
     */
    @NotNull
    public static IgnoreManager getInstance(@NotNull final Project project) {
        return project.getComponent(IgnoreManager.class);
    }

    /**
     * Constructor builds {@link IgnoreManager} instance.
     *
     * @param project current project
     */
    public IgnoreManager(@NotNull final Project project) {
        super(project);
        this.virtualFileManager = VirtualFileManager.getInstance();
        this.settings = IgnoreSettings.getInstance();
        this.statusManager = FileStatusManager.getInstance(project);
        this.refreshTrackedIgnoredRunnable = new RefreshTrackedIgnoredRunnable();
        this.statusesChangedScheduledFeature =
                new InterruptibleScheduledFuture(debouncedStatusesChanged, 5000, 15);
        this.refreshTrackedIgnoredFeature =
                new InterruptibleScheduledFuture(debouncedRefreshTrackedIgnores, 10000, 5);
    }

    /**
     * Checks if file is ignored.
     *
     * @param file current file
     * @return file is ignored
     */
    public boolean isFileIgnored(@NotNull final VirtualFile file) {
        if (DumbService.isDumb(myProject) || !isEnabled() || !Utils.isUnder(file, myProject.getBaseDir())) {
            return false;
        }

        boolean ignored = false;
        boolean matched = false;
        int valuesCount = 0;
        for (IgnoreFileType fileType : IgnoreFilesIndex.getKeys(myProject)) {
            if (!fileType.getIgnoreLanguage().isEnabled()) {
                continue;
            }

            Collection<IgnoreEntryOccurrence> values = IgnoreFilesIndex.getEntries(myProject, fileType);
            valuesCount += values.size();
            for (IgnoreEntryOccurrence value : values) {
                String relativePath;
                if (fileType instanceof GitExcludeFileType) {
                    VirtualFile workingDirectory = GitExcludeFileType.getWorkingDirectory(myProject, value.getFile());
                    if (workingDirectory == null || !Utils.isUnder(file, workingDirectory)) {
                        continue;
                    }
                    relativePath = StringUtil.trimStart(file.getPath(), workingDirectory.getPath());
                } else {
                    String parentPath = value.getFile().getParent().getPath();
                    if (!StringUtil.startsWith(file.getPath(), parentPath)) {
                        if (!ExternalIndexableSetContributor.getAdditionalFiles(myProject).contains(value.getFile())) {
                            continue;
                        }
                    }
                    relativePath = StringUtil.trimStart(file.getPath(), parentPath);
                }

                relativePath = StringUtil.trimEnd(StringUtil.trimStart(relativePath, "/"), "/");
                if (StringUtil.isEmpty(relativePath)) {
                    continue;
                }

                if (file.isDirectory()) {
                    relativePath += "/";
                }

                for (Pair<Pattern, Boolean> item : value.getItems()) {
                    if (item.first.matcher(relativePath).matches()) {
                        ignored = !item.second;
                        matched = true;
                    }
                }
            }
        }

        if (valuesCount > 0 && !ignored && !matched) {
            VirtualFile directory = file.getParent();
            if (directory != null && !directory.equals(myProject.getBaseDir())) {
                return isFileIgnored(directory);
            }
        }

        if (ignored) {
            statusesChangedScheduledFeature.cancel();
            refreshTrackedIgnoredFeature.cancel();
        }

        return ignored;
    }

    /**
     * Checks if file is ignored and tracked.
     *
     * @param file current file
     * @return file is ignored and tracked
     */
    public boolean isFileIgnoredAndTracked(@NotNull final VirtualFile file) {
        return !confirmedIgnoredFiles.containsKey(file) && isFileIgnored(file);
    }

    /**
     * Invoked when the project corresponding to this component instance is opened.<p>
     * Note that components may be created for even unopened projects and this method can be never
     * invoked for a particular component instance (for example for default project).
     */
    @Override
    public void projectOpened() {
        if (isEnabled() && !working) {
            enable();
        }
    }

    /**
     * Invoked when the project corresponding to this component instance is closed.<p>
     * Note that components may be created for even unopened projects and this method can be never
     * invoked for a particular component instance (for example for default project).
     */
    @Override
    public void projectClosed() {
        disable();
    }

    /**
     * Checks if ignored files watching is enabled.
     *
     * @return enabled
     */
    private boolean isEnabled() {
        return settings.isIgnoredFileStatus();
    }

    /** Enable manager. */
    private void enable() {
        if (working) {
            return;
        }

        statusesChangedScheduledFeature.run();
        refreshTrackedIgnoredFeature.run();
        virtualFileManager.addVirtualFileListener(virtualFileListener);
        settings.addListener(settingsListener);
        messageBus = myProject.getMessageBus().connect();
        messageBus.subscribe(RefreshStatusesListener.REFRESH_STATUSES, new RefreshStatusesListener() {
            @Override
            public void refresh() {
                statusesChangedScheduledFeature.run();
            }
        });
        messageBus.subscribe(TRACKED_IGNORED_REFRESH, new RefreshTrackedIgnoredListener() {
            @Override
            public void refresh() {
                debouncedRefreshTrackedIgnores.run(false);
            }
        });

        working = true;
    }

    /** Disable manager. */
    private void disable() {
        statusesChangedScheduledFeature.cancel();
        virtualFileManager.removeVirtualFileListener(virtualFileListener);
        settings.removeListener(settingsListener);

        if (messageBus != null) {
            messageBus.disconnect();
            messageBus = null;
        }

        working = false;
    }

    /** Dispose and disable component. */
    @Override
    public void disposeComponent() {
        super.disposeComponent();
        disable();
    }

    /**
     * Runs {@link #enable()} or {@link #disable()} depending on the passed value.
     *
     * @param enable or disable
     */
    private void toggle(@NotNull Boolean enable) {
        if (enable) {
            enable();
        } else {
            disable();
        }
    }

    /**
     * Returns tracked and ignored files stored in {@link #confirmedIgnoredFiles}.
     *
     * @return tracked and ignored files map
     */
    @NotNull
    public HashMap<VirtualFile, Repository> getConfirmedIgnoredFiles() {
        return confirmedIgnoredFiles;
    }

    /** {@link Runnable} implementation to rebuild {@link #confirmedIgnoredFiles}. */
    class RefreshTrackedIgnoredRunnable implements Runnable, IgnoreManager.RefreshTrackedIgnoredListener {
        /** Default {@link Runnable} run method that invokes rebuilding with bus event propagating. */
        @Override
        public void run() {
            run(false);
        }

        /** Rebuilds {@link #confirmedIgnoredFiles} map in silent mode. */
        @Override
        public void refresh() {
            this.run(true);
        }

        /**
         * Rebuilds {@link #confirmedIgnoredFiles} map.
         *
         * @param silent propagate {@link IgnoreManager.TrackedIgnoredListener#TRACKED_IGNORED} event
         */
        public void run(boolean silent) {
            if (!settings.isInformTrackedIgnored()) {
                return;
            }

            final Collection<Repository> repositories = VcsRepositoryManager.getInstance(myProject).getRepositories();
            final HashMap<VirtualFile, Repository> result = new HashMap<VirtualFile, Repository>();
            for (Repository repository : repositories) {
                if (!(repository instanceof GitRepository)) {
                    continue;
                }
                final VirtualFile root = repository.getRoot();
                for (String path : ExternalExec.getIgnoredFiles(repository)) {
                    final VirtualFile file = root.findFileByRelativePath(path);
                    if (file != null) {
                        result.put(file, repository);
                    }
                }
            }

            if (!silent && !result.isEmpty()) {
                myProject.getMessageBus().syncPublisher(TRACKED_IGNORED).handleFiles(result);
            }
            confirmedIgnoredFiles.clear();
            confirmedIgnoredFiles.putAll(result);
            statusManager.fileStatusesChanged();

            for (AbstractProjectViewPane pane : Extensions.getExtensions(AbstractProjectViewPane.EP_NAME, myProject)) {
                if (pane.getTreeBuilder() != null) {
                    pane.getTreeBuilder().queueUpdate();
                }
            }
        }
    }

    /** Listener bounded with {@link TrackedIgnoredListener#TRACKED_IGNORED} topic to inform about new entries. */
    public interface TrackedIgnoredListener {
        /** Topic for detected tracked and indexed files. */
        Topic<TrackedIgnoredListener> TRACKED_IGNORED =
                Topic.create("New tracked and indexed files detected", TrackedIgnoredListener.class);

        void handleFiles(@NotNull HashMap<VirtualFile, Repository> files);
    }

    /**
     * Listener bounded with {@link RefreshTrackedIgnoredListener#TRACKED_IGNORED_REFRESH} topic to
     * trigger tracked and ignored files list.
     */
    public interface RefreshTrackedIgnoredListener {
        /** Topic for refresh tracked and indexed files. */
        Topic<RefreshTrackedIgnoredListener> TRACKED_IGNORED_REFRESH =
                Topic.create("New tracked and indexed files detected", RefreshTrackedIgnoredListener.class);

        void refresh();
    }

    public interface RefreshStatusesListener {
        /** Topic to refresh files statuses using {@link MessageBusConnection}. */
        Topic<RefreshStatusesListener> REFRESH_STATUSES =
                new Topic<RefreshStatusesListener>("Refresh files statuses", RefreshStatusesListener.class);

        void refresh();
    }

    /**
     * Unique name of this component. If there is another component with the same name or
     * name is null internal assertion will occur.
     *
     * @return the name of this component
     */
    @NonNls
    @NotNull
    @Override
    public String getComponentName() {
        return "IgnoreManager";
    }
}
