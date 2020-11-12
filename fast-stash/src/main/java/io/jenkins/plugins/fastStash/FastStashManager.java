package io.jenkins.plugins.fastStash;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Launcher.LocalLauncher;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.DirScanner;
import hudson.util.io.ArchiverFactory;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import jenkins.model.ArtifactManager;
import jenkins.model.Jenkins;
import jenkins.util.BuildListenerAdapter;
import jenkins.util.VirtualFile;
import org.anarres.lzo.*;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.jenkinsci.plugins.workflow.flow.*;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

public class FastStashManager {

    @SuppressFBWarnings(value="RV_RETURN_VALUE_IGNORED_BAD_PRACTICE", justification="fine if mkdirs returns false")
    public static void stash(@Nonnull Run<?,?> build, @Nonnull String name, @Nonnull FilePath workspace, @Nonnull Launcher launcher, @Nonnull EnvVars env, @Nonnull TaskListener listener,
                             @CheckForNull String includes, @CheckForNull String excludes, boolean useDefaultExcludes, boolean allowEmpty) throws IOException, InterruptedException {
        Jenkins.checkGoodName(name);
        StashAwareArtifactManager saam = stashAwareArtifactManager(build);
        if (saam != null) {
            saam.stash(name, workspace, launcher, env, listener, includes, excludes, useDefaultExcludes, allowEmpty);
            return;
        }
        File storage = storage(build, name);
        storage.getParentFile().mkdirs();
        if (storage.isFile()) {
            listener.getLogger().println("Warning: overwriting stash ‘" + name + "’");
        }
        try (OutputStream os = new FileOutputStream(storage)) {
            LzoAlgorithm alg = LzoAlgorithm.LZO1X;
            LzoCompressor compressor = LzoLibrary.getInstance().newCompressor(alg, null);
            LzoOutputStream stream = new LzoOutputStream(os, compressor, 256);
            stream.write(256);

            int count = workspace.archive(ArchiverFactory.ZIP, stream, new DirScanner.Glob(Util.fixEmpty(includes) == null ? "**" : includes, excludes, useDefaultExcludes));
            if (count == 0 && !allowEmpty) {
                throw new AbortException("No files included in stash ‘" + name + "’");
            }
            listener.getLogger().println("Stashed " + count + " file(s)");
        }
    }

    public static void unstash(@Nonnull Run<?,?> build, @Nonnull String name, @Nonnull FilePath workspace, @Nonnull Launcher launcher, @Nonnull EnvVars env, @Nonnull TaskListener listener) throws IOException, InterruptedException {
        Jenkins.checkGoodName(name);
        StashAwareArtifactManager saam = stashAwareArtifactManager(build);
        if (saam != null) {
            saam.unstash(name, workspace, launcher, env, listener);
            return;
        }
        File storage = storage(build, name);
        if (!storage.isFile()) {
            throw new AbortException("No such saved stash ‘" + name + "’");
        }

        InputStream in = new FileInputStream(storage);
        LzoAlgorithm alg = LzoAlgorithm.LZO1X;
        LzoDecompressor decompressor = LzoLibrary.getInstance().newDecompressor(alg, null);
        LzoInputStream stream = new LzoInputStream(in, decompressor);
        File file = new File(String.valueOf(workspace )+ name);
        OutputStream outputStream = new FileOutputStream(file);
        stream.close();

        new FilePath(file).untar(workspace, FilePath.TarCompression.NONE);
    }

    /**
     * Delete any and all stashes in a build.
     * @param build a build possibly passed to {@link #stash} in the past
     * @param listener a way to report progress or problems
     * @see StashAwareArtifactManager#clearAllStashes
     */
    public static void clearAll(@Nonnull Run<?,?> build, @Nonnull TaskListener listener) throws IOException, InterruptedException {
        StashAwareArtifactManager saam = stashAwareArtifactManager(build);
        if (saam != null) {
            saam.clearAllStashes(listener);
            return;
        }
        Util.deleteRecursive(storage(build));
    }


    /**
     * Delete any and all stashes in a build unless told otherwise.
     * {@link StashBehavior#shouldClearAll} may cancel this.
     * @param build a build possibly passed to {@link #stash} in the past
     * @see #clearAll(Run, TaskListener)
     */
    public static void maybeClearAll(@Nonnull Run<?,?> build, @Nonnull TaskListener listener) throws IOException, InterruptedException {
        for (StashBehavior behavior : ExtensionList.lookup(StashBehavior.class)) {
            if (!behavior.shouldClearAll(build)) {
                return;
            }
        }
        clearAll(build, listener);
    }

    /**
     * @deprecated without replacement; only used from {@link CopyStashesAndArtifacts} anyway
     */
    @Deprecated
    public static void copyAll(@Nonnull Run<?,?> from, @Nonnull Run<?,?> to) throws IOException {
        File fromStorage = storage(from);
        if (!fromStorage.isDirectory()) {
            return;
        }
        FileUtils.copyDirectory(fromStorage, storage(to));
    }

    private static @Nonnull File storage(@Nonnull Run<?,?> build) throws IOException {
        assert stashAwareArtifactManager(build) == null;
        return new File(build.getRootDir(), "stashes");
    }

    private static @Nonnull File storage(@Nonnull Run<?,?> build, @Nonnull String name) throws IOException {
        File dir = storage(build);
        File f = new File(dir, name + SUFFIX);
        if (!f.getParentFile().equals(dir)) {
            throw new IllegalArgumentException();
        }
        return f;
    }

    private static final String SUFFIX = ".tar.gz";

    private FastStashManager() {}

    /**
     * Extension point for customizing behavior of stashes from other plugins.
     */
    public static abstract class StashBehavior implements ExtensionPoint {

        /**
         * Allows the normal clearing behavior to be suppressed.
         * @param build a build which has finished
         * @return true (the default) to go ahead and call {@link #clearAll}, false to stop
         */
        public boolean shouldClearAll(@Nonnull Run<?,?> build) {
            return true;
        }

    }

    /**
     * Mixin interface for an {@link ArtifactManager} which supports specialized stash behavior as well.
     *
     * <p> When implementing off-Jenkins artifact storage, you should NOT extend this directly but instead use the
     * {@code JCloudsArtifactManager} in the plugin currently named {@code artifact-manager-s3}.
     *
     * This is dangerous to directly extend if using remote storage unless you write a very robust handling of network failures including at least a base timeout and retries.
     * The {@code JCloudsArtifactManager} implementation supports extensibility to various cloud providers and custom stores via the {@code BlobStoreProvider} ExtensionPoint.
     * It handles all aspects of making cloud artifact storage work smoothly in Jenkins
     * including the {@link VirtualFile} implementation, robust network error handling, overall configuration UI, and more.
     * <strong>Implement this interface directly at your own risk.</strong>
     * @see <a href="https://github.com/jenkinsci/jep/blob/master/jep/202/README.adoc">JEP-202</a>
     */
    public interface StashAwareArtifactManager /* extends ArtifactManager */ {

        /** @see StashManager#stash(Run, String, FilePath, Launcher, EnvVars, TaskListener, String, String, boolean, boolean) */
        void stash(@Nonnull String name, @Nonnull FilePath workspace, @Nonnull Launcher launcher, @Nonnull EnvVars env, @Nonnull TaskListener listener, @CheckForNull String includes, @CheckForNull String excludes, boolean useDefaultExcludes, boolean allowEmpty) throws IOException, InterruptedException;

        /** @see StashManager#unstash(Run, String, FilePath, Launcher, EnvVars, TaskListener) */
        void unstash(@Nonnull String name, @Nonnull FilePath workspace, @Nonnull Launcher launcher, @Nonnull EnvVars env, @Nonnull TaskListener listener) throws IOException, InterruptedException;

        /** @see StashManager#clearAll(Run, TaskListener) */
        void clearAllStashes(@Nonnull TaskListener listener) throws IOException, InterruptedException;

        /**
         * Copy all stashes and artifacts from one build to another.
         * The {@link ArtifactManager} configuration will be as of the origin build.
         * If the implementation cannot handle {@code to} for whatever reason, it may throw {@link AbortException}.
         * @see CopyStashesAndArtifacts
         */
        void copyAllArtifactsAndStashes(@Nonnull Run<?,?> to, @Nonnull TaskListener listener) throws IOException, InterruptedException;

    }

    private static @CheckForNull StashAwareArtifactManager stashAwareArtifactManager(@Nonnull Run<?, ?> build) throws IOException {
        ArtifactManager am = build.pickArtifactManager();
        return am instanceof StashAwareArtifactManager ? (StashAwareArtifactManager) am : null;
    }

    @Restricted(NoExternalUse.class)
    @Extension public static class CopyStashesAndArtifacts extends FlowCopier.ByRun {

        @Override public void copy(Run<?,?> original, Run<?,?> copy, TaskListener listener) throws IOException, InterruptedException {
            StashAwareArtifactManager saam = stashAwareArtifactManager(original);
            if (saam != null) {
                saam.copyAllArtifactsAndStashes(copy, listener);
                return;
            }
            VirtualFile srcroot = original.getArtifactManager().root();
            FilePath dstDir = createTmpDir();
            try {
                Map<String,String> files = new HashMap<>();
                for (String path : srcroot.list("**/*")) {
                    files.put(path, path);
                    InputStream in = srcroot.child(path).open();
                    try {
                        dstDir.child(path).copyFrom(in);
                    } finally {
                        IOUtils.closeQuietly(in);
                    }
                }
                if (!files.isEmpty()) {
                    listener.getLogger().println("Copying " + files.size() + " artifact(s) from " + original.getDisplayName());
                    copy.getArtifactManager().archive(dstDir, new LocalLauncher(listener), new BuildListenerAdapter(listener), files);
                }
            } finally {
                dstDir.deleteRecursive();
            }

            StashManager.copyAll(original, copy);
        }

        private FilePath createTmpDir() throws IOException {
            File dir = File.createTempFile("artifact", "copy");
            if (!(dir.delete() && dir.mkdirs())) {
                throw new IOException("Failed to create temporary directory " + dir.getPath());
            }
            return new FilePath(dir);
        }

    }
}
