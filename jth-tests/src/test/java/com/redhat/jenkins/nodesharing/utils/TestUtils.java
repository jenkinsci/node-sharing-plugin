/*
 * The MIT License
 *
 * Copyright (c) Red Hat, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.redhat.jenkins.nodesharing.utils;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Util;
import hudson.remoting.Which;
import hudson.util.StreamTaskListener;
import org.apache.commons.io.FileUtils;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.junit.Assert;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Map;
import java.util.regex.Pattern;

public class TestUtils {

    private TestUtils() {}

    public static GitClient createConfigRepo() throws URISyntaxException, IOException, InterruptedException {
        File orig = new File(NodeSharingJenkinsRule.class.getResource("../dummy_config_repo").toURI());
        Assert.assertTrue(orig.isDirectory());
        File repo = Files.createTempDirectory("jenkins.nodesharing" + NodeSharingJenkinsRule.class.getSimpleName()).toFile();
        FileUtils.copyDirectory(orig, repo);

        StreamTaskListener listener = new StreamTaskListener(System.err, Charset.defaultCharset());
        // To make it work with no gitconfig
        String name = "Pool Maintainer";
        String mail = "pool.maintainer@acme.com";
        EnvVars env = new EnvVars("GIT_AUTHOR_NAME", name, "GIT_AUTHOR_EMAIL", mail, "GIT_COMMITTER_NAME", name, "GIT_COMMITTER_EMAIL", mail);
        GitClient git = Git.with(listener, env).in(repo).using("git").getClient();
        git.init();
        git.checkout().branch("master").execute(); // Enforce branch name so this is not impacted by git config of `init.defaultBranch`
        git.add("*");
        git.commit("Init");
        return git;
    }

    public static void declareOrchestrator(GitClient git, String jenkinsUrl, String credentialId) throws IOException, InterruptedException {
        StringBuilder sb = new StringBuilder("orchestrator.url=");
        sb.append(jenkinsUrl);
        sb.append(System.lineSeparator());
        sb.append("enforce_https=false");
        if(Util.fixEmptyAndTrim(credentialId) != null) {
            sb.append(System.lineSeparator());
            sb.append("credential_id=");
            sb.append(Util.fixEmptyAndTrim(credentialId));
        }
        git.getWorkTree().child("config").write(sb.toString(), "UTF-8");
        git.add("config");
        git.commit("Writing config repo orchestrator");
    }

    /**
     * Write local urls of Jenkinses
     */
    public static void declareExecutors(GitClient git, Map<String, String> jenkinses) throws InterruptedException, IOException {
        FilePath jenkinsesDir = git.getWorkTree().child("jenkinses");
        for (FilePath filePath : jenkinsesDir.list()) {
            filePath.delete();
        }
        for (Map.Entry<String, String> j : jenkinses.entrySet()) {
            String url = j.getValue();
            jenkinsesDir.child(j.getKey()).write(getDummyExecutorFile(url), "UTF-8");
        }
        git.add("jenkinses");
        git.commit("Update Jenkinses");
    }

    private static @Nonnull String getDummyExecutorFile(String url) {
        StringBuilder sb = new StringBuilder("url=").append(url).append(System.lineSeparator());
        if (url.startsWith("http://")) {
            sb.append("enforce_https=false").append(System.lineSeparator());
        }
        return sb.toString();
    }

    public static void declareExecutor(GitClient git, String name, String url) throws IOException, InterruptedException {
        FilePath jenkinsFile = git.getWorkTree().child("jenkinses").child(name);
        assert !jenkinsFile.exists();

        jenkinsFile.write(getDummyExecutorFile(url), "UTF-8");

        git.add("jenkinses");
        git.commit("Add Jenkins");
    }

    // Make the nodes launchable by replacing for local launcher using default java and jar
    public static void makeNodesLaunchable(GitClient git) throws IOException, InterruptedException {
        final File slaveJar = Which.jarFile(hudson.remoting.Launcher.class).getAbsoluteFile();
        Pattern pattern = Pattern.compile("<launcher.*</launcher>", Pattern.DOTALL);
        for (FilePath file : git.getWorkTree().child("nodes").list("*.xml")) {
            String command = System.getProperty("java.home") + "/bin/java -jar " + slaveJar;
            String launcherTag = "<launcher class='hudson.slaves.CommandLauncher'><agentCommand>" + command + "</agentCommand></launcher>";

            String xml = pattern.matcher(file.readToString()).replaceAll(launcherTag);
            file.write(xml, "UTF-8");
        }
        git.add("nodes");
        git.commit("Making nodes in config repo launchable");
    }
}
