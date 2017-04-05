package com.vividseats.k8s;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.jenkins_ci.plugins.run_condition.RunCondition;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

public class PodStatusCondition extends RunCondition {

    private final String podName;
    private final String namespace;
    private final PodStatus podStatus;

    @DataBoundConstructor
    public PodStatusCondition(String podName,
                              String namespace,
                              String podStatus) {
        this.podName = podName;
        this.namespace = namespace;
        this.podStatus = PodStatus.valueOf(podStatus);
    }

    @Override
    public boolean runPrebuild(AbstractBuild<?, ?> abstractBuild, BuildListener buildListener) throws Exception {
        return true;
    }

    @Override
    public boolean runPerform(AbstractBuild<?, ?> abstractBuild, BuildListener buildListener) throws Exception {
        EnvVars environment = abstractBuild.getEnvironment(buildListener);
        Launcher.ProcStarter launcher = Jenkins.getInstance().createLauncher(buildListener).launch();

        try (PipedOutputStream pipedOutputStream    = new PipedOutputStream();
             PipedInputStream pipedInputStream      = new PipedInputStream(pipedOutputStream);
             BufferedReader bufferedReader          = new BufferedReader(new InputStreamReader(pipedInputStream))) {

            int exitStatus = launcher
                    .cmdAsSingleString(new CommandFactory().getCommand(environment))
                    .envs(environment)
                    .stderr(buildListener.getLogger())
                    .stdout(pipedOutputStream)
                    .quiet(true)
                    .join();

            pipedOutputStream.flush();
            pipedOutputStream.close();

            if (exitStatus == 0) {
                String output;
                while ((output = bufferedReader.readLine()) != null) {
                    PodStatus status = PodStatus.fromStatus(output);
                    if (podStatus == status) {
                        return true;
                    }
                }
            }

        } catch (IOException e) {
            buildListener.getLogger().append(e.getMessage() + "\n");
        }
        return false;
    }

    public String getPodName() {
        return podName;
    }

    public String getNamespace() {
        return namespace;
    }

    private class CommandFactory {
        public String getCommand(EnvVars environment) {
            if (StringUtils.isNotBlank(namespace)) {
                return String.format("kubectl get -o template pod/%s --namespace=%s --template={{.status.phase}}",
                        Util.replaceMacro(podName, environment).replaceAll("_", "-").toLowerCase(),
                        Util.replaceMacro(namespace, environment));
            } else {
                return String.format("kubectl get -o template pod/%s --template={{.status.phase}}",
                        Util.replaceMacro(podName, environment).replaceAll("_", "-").toLowerCase());
            }
        }
    }

    private enum PodStatus {
        COMPLETED,
        RUNNING;

        public static PodStatus fromStatus(String status) {
            switch (status.toLowerCase()) {
                case "pending":
                case "running":
                case "containercreating":
                    return RUNNING;
                default:
                    return COMPLETED;
            }
        }
    }

    @Extension
    public static class PodStatusConditionDescriptor extends RunCondition.RunConditionDescriptor {

        @Override
        public String getDisplayName() {
            return "POD Status is";
        }

        public ListBoxModel doFillPodStatusItems() {
            ListBoxModel credentialList = new ListBoxModel();
            credentialList.add("Running", "RUNNING");
            credentialList.add("Completed", "COMPLETED");
            return credentialList;
        }

    }
}
