package org.ilabs;

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
import java.util.Map;
import java.util.Set;

public class FastStashStep extends Step {

    private final @Nonnull
    String name;
    private @CheckForNull
    String includes;
    private @CheckForNull
    String excludes;
    private boolean compress;

    private final boolean useDefaultExcludes = true;
    private final boolean allowEmpty = false;

    @DataBoundConstructor
    public FastStashStep(@Nonnull String name) {
        Jenkins.checkGoodName(name);
        this.name = name;
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

    @DataBoundSetter
    public void setExcludes(String excludes) {
        this.excludes = Util.fixEmpty(excludes);
    }

    @DataBoundSetter
    public void setCompress(Boolean compress) {
        this.compress = compress;
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
                    step.useDefaultExcludes, step.allowEmpty);
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
            return name instanceof String ? (String) name : null;
        }
    }
}