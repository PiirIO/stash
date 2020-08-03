package io.jenkins.plugins.fastStash;

import edu.umd.cs.findbugs.annotations.*;
import hudson.*;
import hudson.model.*;
import hudson.util.*;
import hudson.util.io.*;
import jenkins.model.*;
import org.anarres.lzo.*;
import org.kohsuke.accmod.*;
import org.kohsuke.accmod.restrictions.*;

import javax.annotation.CheckForNull;
import javax.annotation.*;
import java.io.*;


public class FastStashManager {

    private FastStashManager() {
    }

    @SuppressFBWarnings(
            value = {"RV_RETURN_VALUE_IGNORED_BAD_PRACTICE"},
            justification = "fine if mkdirs returns false"
    )
    public static void stash(@Nonnull Run<?, ?> build, @Nonnull String name, @Nonnull FilePath workspace, @Nonnull Launcher launcher, @Nonnull EnvVars env, @Nonnull TaskListener listener, @CheckForNull String includes, @CheckForNull String excludes, boolean useDefaultExcludes, boolean allowEmpty, String compression) throws IOException, InterruptedException {
        Jenkins.checkGoodName(name);

        FastStashManager.StashAwareArtifactManager saam = stashAwareArtifactManager(build);
        if (saam != null) {
            saam.stash(name, workspace, launcher, env, listener, includes, excludes, useDefaultExcludes, allowEmpty);
        } else {
            File storage = storage(build, name);
            storage.getParentFile().mkdirs();
            if (storage.isFile()) {
                listener.getLogger().println("Warning: overwriting stash ‘" + name + "’");
            }
            try (FileOutputStream os = new FileOutputStream(storage)) {

                if (compression.equals(null)) {
                    int count = workspace.archive(ArchiverFactory.TAR, os, new DirScanner.Glob(Util.fixEmpty(includes) == null ? "**" : includes, excludes, useDefaultExcludes));
                    if (count == 0 && !allowEmpty) {
                        throw new AbortException("No files included in stash ‘" + name + "’");
                    }
                    listener.getLogger().println("Stashed " + count + " file(s)");

                } else if (compression.equals("LZO1X")) {
                    LzoAlgorithm alg = LzoAlgorithm.LZO1X;
                    LzoCompressor compressor = LzoLibrary.getInstance().newCompressor(alg, null);
                    LzoOutputStream stream = new LzoOutputStream(os, compressor, 256);
                    stream.write(256);
                    if (stream.toString().isEmpty() && !allowEmpty) {
                        throw new AbortException("No files included in stash ‘" + name + "’");
                    }
                    listener.getLogger().println("Stashed " + stream.toString() + " file(s)");
                }
            }
        }
    }

    public static void unstash(@Nonnull Run<?, ?> build, @Nonnull String name, @Nonnull FilePath workspace, @Nonnull Launcher launcher, @Nonnull EnvVars env, @Nonnull TaskListener listener, String compression) throws IOException, InterruptedException {
        Jenkins.checkGoodName(name);
        FastStashManager.StashAwareArtifactManager saam = stashAwareArtifactManager(build);
        if (saam != null) {
            saam.unstash(name, workspace, launcher, env, listener);
        } else {
            File storage = storage(build, name);
            if (!storage.isFile()) {
                throw new AbortException("No such saved stash ‘" + name + "’");
            } else {
                if(compression.equals(null)){
                    new FilePath(storage);
                } else if(compression.equals("LZO1X")){
                    InputStream in = new FileInputStream(storage);
                    LzoAlgorithm alg = LzoAlgorithm.LZO1X;
                    LzoDecompressor decompressor = LzoLibrary.getInstance().newDecompressor(alg, null);
                    LzoInputStream stream = new LzoInputStream(in, decompressor);
                    stream.read();
                    new FilePath(storage);
                }
            }
        }
    }

    @Nonnull
    private static File storage(@Nonnull Run<?, ?> build) throws IOException {
        assert stashAwareArtifactManager(build) == null;

        return new File(build.getRootDir(), "stashes");
    }

    @Nonnull
    private static File storage(@Nonnull Run<?, ?> build, @Nonnull String name) throws IOException {
        File dir = storage(build);
        File f = new File(dir, name);
        if (!f.getParentFile().equals(dir)) {
            throw new IllegalArgumentException();
        } else {
            return f;
        }
    }

    public static void clearAll(@Nonnull Run<?, ?> build, @Nonnull TaskListener listener) throws IOException, InterruptedException {
        FastStashManager.StashAwareArtifactManager saam = stashAwareArtifactManager(build);
        if (saam != null) {
            saam.clearAllStashes(listener);
        } else {
            Util.deleteRecursive(storage(build));
        }
    }

    @CheckForNull
    private static FastStashManager.StashAwareArtifactManager stashAwareArtifactManager(@Nonnull Run<?, ?> build) throws IOException {
        ArtifactManager am = build.pickArtifactManager();
        return am instanceof FastStashManager.StashAwareArtifactManager ? (FastStashManager.StashAwareArtifactManager) am : null;
    }


    @Restricted({Beta.class})
    public interface StashAwareArtifactManager {
        void stash(@Nonnull String var1, @Nonnull FilePath var2, @Nonnull Launcher var3, @Nonnull EnvVars var4, @Nonnull TaskListener var5, @CheckForNull String var6, @CheckForNull String var7, boolean var8, boolean var9) throws IOException, InterruptedException;

        void unstash(@Nonnull String var1, @Nonnull FilePath var2, @Nonnull Launcher var3, @Nonnull EnvVars var4, @Nonnull TaskListener var5) throws IOException, InterruptedException;

        void clearAllStashes(@Nonnull TaskListener var1) throws IOException, InterruptedException;
    }
}
