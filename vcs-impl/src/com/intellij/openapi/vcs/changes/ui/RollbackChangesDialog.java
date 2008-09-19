package com.intellij.openapi.vcs.changes.ui;

import com.intellij.history.LocalHistory;
import com.intellij.history.LocalHistoryAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vcs.*;
import com.intellij.openapi.vcs.changes.*;
import com.intellij.openapi.vcs.rollback.RollbackEnvironment;
import com.intellij.openapi.vfs.newvfs.RefreshQueue;
import com.intellij.openapi.vfs.newvfs.RefreshSession;
import com.intellij.util.ui.UIUtil;
import gnu.trove.THashSet;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * @author max
 */
public class RollbackChangesDialog extends DialogWrapper {
  private Project myProject;
  private final boolean myRefreshSynchronously;
  private MultipleChangeListBrowser myBrowser;
  @Nullable private JCheckBox myDeleteLocallyAddedFiles;

  public static void rollbackChanges(final Project project, final Collection<Change> changes) {
    rollbackChanges(project, changes, false);
  }

  public static void rollbackChanges(final Project project, final Collection<Change> changes, boolean refreshSynchronously) {
    final ChangeListManager manager = ChangeListManager.getInstance(project);

    if (changes.isEmpty()) {
      Messages.showWarningDialog(project, VcsBundle.message("commit.dialog.no.changes.detected.text"),
                                 VcsBundle.message("commit.dialog.no.changes.detected.title"));
      return;
    }

    ArrayList<Change> validChanges = new ArrayList<Change>();
    Set<LocalChangeList> lists = new THashSet<LocalChangeList>();
    for (Change change : changes) {
      final LocalChangeList list = manager.getChangeList(change);
      if (list != null) {
        lists.add(list);
        validChanges.add(change);
      }
    }

    rollback(project, new ArrayList<LocalChangeList>(lists), validChanges, refreshSynchronously);
  }

  public static void rollback(final Project project,
                              final List<LocalChangeList> changeLists,
                              final List<Change> changes,
                              final boolean refreshSynchronously) {
    new RollbackChangesDialog(project, changeLists, changes, refreshSynchronously).show();
  }

  public RollbackChangesDialog(final Project project,
                               List<LocalChangeList> changeLists,
                               final List<Change> changes,
                               final boolean refreshSynchronously) {
    super(project, true);

    myProject = project;
    myRefreshSynchronously = refreshSynchronously;
    myBrowser = new MultipleChangeListBrowser(project, changeLists, changes, null, true, true);
    myBrowser.setToggleActionTitle("Include in rollback");

    setOKButtonText(VcsBundle.message("changes.action.rollback.text"));
    setTitle(VcsBundle.message("changes.action.rollback.title"));

    Set<AbstractVcs> affectedVcs = new HashSet<AbstractVcs>();
    for (Change c : changes) {
      final AbstractVcs vcs = ChangesUtil.getVcsForChange(c, project);
      if (vcs != null) {
        // vcs may be null if we have turned off VCS integration and are in process of refreshing
        affectedVcs.add(vcs);
      }
    }
    if (affectedVcs.size() == 1) {
      AbstractVcs vcs = (AbstractVcs)affectedVcs.toArray()[0];
      final RollbackEnvironment rollbackEnvironment = vcs.getRollbackEnvironment();
      if (rollbackEnvironment != null) {
        final String rollbackOperationName = rollbackEnvironment.getRollbackOperationName().replace(Character.toString(UIUtil.MNEMONIC), "");
        setTitle(VcsBundle.message("changes.action.rollback.custom.title", rollbackOperationName).replace("_", ""));
        setOKButtonText(rollbackOperationName);
      }
    }

    for (Change c : changes) {
      if (c.getType() == Change.Type.NEW) {
        myDeleteLocallyAddedFiles = new JCheckBox(VcsBundle.message("changes.checkbox.delete.locally.added.files"));
        break;
      }
    }

    init();
  }

  @Override
  protected void dispose() {
    super.dispose();
    myBrowser.dispose();
  }

  @Override
  protected void doOKAction() {
    super.doOKAction();
    doRollback(myProject, myBrowser.getCurrentIncludedChanges(),
               myDeleteLocallyAddedFiles != null && myDeleteLocallyAddedFiles.isSelected(), myRefreshSynchronously);
  }

  @Nullable
  protected JComponent createCenterPanel() {
    if (myDeleteLocallyAddedFiles != null) {
      JPanel panel = new JPanel(new BorderLayout());
      panel.add(myBrowser, BorderLayout.CENTER);
      panel.add(myDeleteLocallyAddedFiles, BorderLayout.SOUTH);
      return panel;
    }
    return myBrowser;
  }

  public static void doRollback(final Project project,
                                final Collection<Change> changes,
                                final boolean deleteLocallyAddedFiles,
                                final boolean refreshSynchronously) {
    final List<VcsException> vcsExceptions = new ArrayList<VcsException>();
    final List<FilePath> pathsToRefresh = new ArrayList<FilePath>();

    final ChangeListManager changeListManager = ChangeListManagerImpl.getInstance(project);
    final Runnable notifier = changeListManager.prepareForChangeDeletion(changes);
    final Runnable afterRefresh = new Runnable() {
      public void run() {
        changeListManager.invokeAfterUpdate(notifier, false, true, "Refresh change lists after update");
      }
    };

    Runnable rollbackAction = new Runnable() {
      public void run() {
        ChangesUtil.processChangesByVcs(project, changes, new ChangesUtil.PerVcsProcessor<Change>() {
          public void process(AbstractVcs vcs, List<Change> changes) {
            final RollbackEnvironment environment = vcs.getRollbackEnvironment();
            if (environment != null) {
              pathsToRefresh.addAll(ChangesUtil.getPaths(changes));

              final List<VcsException> exceptions = environment.rollbackChanges(changes);
              if (exceptions.size() > 0) {
                vcsExceptions.addAll(exceptions);
              }
              else if (deleteLocallyAddedFiles) {
                for (Change c : changes) {
                  if (c.getType() == Change.Type.NEW) {
                    ContentRevision rev = c.getAfterRevision();
                    assert rev != null;
                    FileUtil.delete(rev.getFile().getIOFile());
                  }
                }
              }
            }
          }
        });

        if (!refreshSynchronously) {
          doRefresh(project, pathsToRefresh, true, afterRefresh);
        }

        AbstractVcsHelper.getInstanceChecked(project).showErrors(vcsExceptions, VcsBundle.message("changes.action.rollback.text"));
      }
    };

    if (ApplicationManager.getApplication().isDispatchThread()) {
      ProgressManager.getInstance()
        .runProcessWithProgressSynchronously(rollbackAction, VcsBundle.message("changes.action.rollback.text"), true, project);
    }
    else {
      rollbackAction.run();
    }
    if (refreshSynchronously) {
      ApplicationManager.getApplication().runWriteAction(new Runnable() {
        public void run() {
          doRefresh(project, pathsToRefresh, false, afterRefresh);
        }
      });
    }
  }

  private static void doRefresh(final Project project, final List<FilePath> pathsToRefresh, final boolean asynchronous,
                                final Runnable runAfter) {
    final LocalHistoryAction action = LocalHistory.startAction(project, VcsBundle.message("changes.action.rollback.text"));
    RefreshSession session = RefreshQueue.getInstance().createSession(asynchronous, true, new Runnable() {
      public void run() {
        action.finish();
        if (!project.isDisposed()) {
          for (FilePath path : pathsToRefresh) {
            if (path.isDirectory()) {
              VcsDirtyScopeManager.getInstance(project).dirDirtyRecursively(path);
            }
            else {
              VcsDirtyScopeManager.getInstance(project).fileDirty(path);
            }
          }
          runAfter.run();
        }
      }
    });
    for(FilePath path: pathsToRefresh) {
      session.addFile(ChangesUtil.findValidParent(path));
    }
    session.launch();
  }

  public JComponent getPreferredFocusedComponent() {
    return myBrowser.getPrefferedFocusComponent();
  }

  protected String getDimensionServiceKey() {
    return "RollbackChangesDialog";
  }
}
