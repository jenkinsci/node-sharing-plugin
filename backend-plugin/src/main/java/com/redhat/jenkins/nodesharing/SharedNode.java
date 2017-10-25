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

import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.Slave;
import hudson.model.TaskListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.EphemeralNode;
import hudson.slaves.NodeProperty;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.SlaveComputer;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Collections;

/**
 * @author ogondza.
 */
public class SharedNode extends Slave implements EphemeralNode {
    private static final long serialVersionUID = 1864241962205144748L;

    /*package*/ SharedNode(@Nonnull String name, String labelString) throws Descriptor.FormException, IOException {
        super(name, name, "/unused", 1, Mode.EXCLUSIVE, labelString, new NoopLauncher(), RetentionStrategy.NOOP, Collections.<NodeProperty<?>>emptyList());
    }

    @Override public Computer createComputer() {
        return new FakeComputer(this);
    }

    @Override public SharedNode asNode() {
        return this;
    }

    public static final class NoopLauncher extends ComputerLauncher {
        @Override
        public boolean isLaunchSupported() {
            // TODO: is this desirable? We want is launched once and never relaunched
            return true;
        }

        @Override
        public void launch(SlaveComputer computer, TaskListener listener) throws IOException, InterruptedException {
            // NOOP
        }
    }
}
