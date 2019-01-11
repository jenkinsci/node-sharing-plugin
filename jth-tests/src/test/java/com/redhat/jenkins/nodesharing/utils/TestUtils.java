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

import com.redhat.jenkins.nodesharing.NodeSharingJenkinsRule;
import hudson.EnvVars;
import hudson.util.StreamTaskListener;
import org.apache.commons.io.FileUtils;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.junit.Assert;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.Charset;

public class TestUtils {

    private TestUtils() {}

    public static GitClient createConfigRepo() throws URISyntaxException, IOException, InterruptedException {
        File orig = new File(NodeSharingJenkinsRule.class.getResource("dummy_config_repo").toURI());
        Assert.assertTrue(orig.isDirectory());
        File repo = File.createTempFile("jenkins.nodesharing", NodeSharingJenkinsRule.class.getSimpleName());
        assert repo.delete();
        assert repo.mkdir();
        FileUtils.copyDirectory(orig, repo);

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
}
