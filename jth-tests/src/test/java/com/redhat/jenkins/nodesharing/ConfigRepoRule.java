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
package com.redhat.jenkins.nodesharing;

import com.redhat.jenkins.nodesharingbackend.Pool;
import com.redhat.jenkins.nodesharingfrontend.SharedNode;
import com.redhat.jenkins.nodesharingfrontend.SharedNodeFactory;
import hudson.EnvVars;
import hudson.ExtensionList;
import hudson.Util;
import hudson.remoting.Launcher;
import hudson.remoting.Which;
import hudson.slaves.CommandLauncher;
import hudson.util.StreamTaskListener;
import jenkins.model.Jenkins;
import org.apache.commons.io.FileUtils;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.File;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * Create fake upstream config repo from resources directory stub.
 *
 * Manipulating {@link GitClient} session mimics changes to actual remote config repo. Not to be confused by a clone, local
 * to each Jenkins, that is supposed to receive the changes pulling them.
 *
 * Use <tt>gitClient.getWorkTree().getRemote()</tt> to refer to this repo remotely.
 */
public class ConfigRepoRule implements TestRule {

    private final List<File> repos = new ArrayList<>();

    @Override
    public Statement apply(final Statement base, Description description) {
        return new Statement() {
            @Override public void evaluate() throws Throwable {
                String oldEndpoint = System.getProperty(Pool.CONFIG_REPO_PROPERTY_NAME);
                try {
                    base.evaluate();
                } finally {
                    if (oldEndpoint == null) {
                        System.clearProperty(Pool.CONFIG_REPO_PROPERTY_NAME);
                    } else {
                        System.setProperty(Pool.CONFIG_REPO_PROPERTY_NAME, oldEndpoint);
                    }
                    for (File repo : repos) {
                        Util.deleteRecursive(repo);
                    }
                }
            }
        };
    }

    protected GitClient create(URL repoSources) throws Exception {
        File orig = new File(repoSources.toURI());
        assertTrue(orig.isDirectory());
        File repo = File.createTempFile("jenkins.nodesharing", getClass().getSimpleName());
        assert repo.delete();
        assert repo.mkdir();
        FileUtils.copyDirectory(orig, repo);
        repos.add(repo);

        StreamTaskListener listener = new StreamTaskListener(System.err, Charset.defaultCharset());
        // To make it work with no gitconfig
        String name = "Pool Maintainer";
        String mail = "pool.maintainer@acme.com";
        EnvVars env = new EnvVars("GIT_AUTHOR_NAME", name, "GIT_AUTHOR_EMAIL", mail, "GIT_COMMITTER_NAME", name, "GIT_COMMITTER_EMAIL", mail);
        GitClient git = Git.with(listener, env).in(repo).using("git").getClient();
        git.init();
        git.add("*");
        git.commit("Init");
        return git;
    }

    protected GitClient createReal(URL repoSources, Jenkins j) throws Exception {
        GitClient git = create(repoSources);

        git.getWorkTree().child("config").write("orchestrator.url=" + j.getRootUrl(), "UTF-8");
        git.add("config");

        git.getWorkTree().child("jenkinses").write("jenkins1=" + j.getRootUrl(), "UTF-8");
        git.add("jenkinses");
        git.commit("Update");

        // Register conversion handler that delegates to production implementation and decorates with local launcher
        final File slaveJar = Which.jarFile(Launcher.class).getAbsoluteFile();
        ExtensionList<SharedNodeFactory> el = ExtensionList.lookup(SharedNodeFactory.class);
        final List<SharedNodeFactory> oldFactories = new ArrayList<>(el);
        el.clear();
        el.add(0, new SharedNodeFactory() {
            @Override public SharedNode create(@Nonnull NodeDefinition def) {
                for (SharedNodeFactory factory : oldFactories) {
                    SharedNode node = factory.create(def);
                    if (node != null) {
                        node.setLauncher(new CommandLauncher(
                                System.getProperty("java.home") + "/bin/java -jar " + slaveJar
                        ));
                        return node;
                    }
                }

                throw new IllegalArgumentException("No SharedNodeFactory to process " + def + '/' + def.getDeclaringFileName());
            }
        });
        return git;
    }
}
