package io.jenkins.plugins.fastStash;

import com.google.common.collect.ImmutableSet;
import hudson.*;
import hudson.model.Run;
import hudson.model.TaskListener;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.steps.*;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

public class FastStashStep extends Step {

    private final @Nonnull
    String name;
    private @CheckForNull
    String includes;
    private @CheckForNull
    String excludes;
    private @CheckForNull
    String compression;
    //Allowed types for compression
    private final String[] COMPRESSION_TYPES = {"LZO1X"};
    private boolean useDefaultExcludes = true;
    private boolean allowEmpty = false;

    @DataBoundConstructor
    public FastStashStep(@Nonnull String name, @CheckForNull String exclude, String compression) {
        Jenkins.checkGoodName(name);
        this.name = name;
        this.excludes = exclude;
        this.compression = (Arrays.stream(this.COMPRESSION_TYPES).anyMatch(compression.trim().toUpperCase()::equals)) ? compression : null;
    }

    public String getName() {
        return name;
    }

    public String getIncludes() {
        return includes;
    }

    @DataBoundSetter
    public void setIncludes(String includes) {
        this.includes = Util.fixEmpty(includes);
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new Execution(this, context);
    }

    public static class Execution extends SynchronousNonBlockingStepExecution<Void> {

        private static final long serialVersionUID = 1L;

        private transient final FastStashStep step;

        Execution(FastStashStep step, StepContext context) {
            super(context);
            this.step = step;
        }

        @Override
        protected Void run() throws Exception {
            FastStashManager.stash(getContext().get(Run.class), step.name, getContext().get(FilePath.class), getContext().get(Launcher.class), getContext().get(EnvVars.class), getContext().get(TaskListener.class), step.includes, step.excludes,
                    step.useDefaultExcludes, step.allowEmpty, null);
            return null;
        }

    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "fastStash";
        }

        @Nonnull
        @Override
        public String getDisplayName() {
            return "Stash some files to be used later in the build but fast";
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return ImmutableSet.of(Run.class, FilePath.class, Launcher.class, EnvVars.class, TaskListener.class);
        }

        @Override
        public String argumentsToString(Map<String, Object> namedArgs) {
            Object name = namedArgs.get("name");
            Object compression = namedArgs.get("compression");
            return name instanceof String ? (String) name : null;
        }

    }

}