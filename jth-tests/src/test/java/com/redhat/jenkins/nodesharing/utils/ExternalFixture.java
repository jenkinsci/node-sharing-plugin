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

import java.lang.annotation.Annotation;
import java.lang.annotation.Documented;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;

/**
 * Annotation for test suite or test method to provision a Jenkins for test.
 *
 * @author ogondza.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({METHOD, TYPE})
@Documented
@Repeatable(ExternalFixture.Container.class)
public @interface ExternalFixture {

    /**
     * Name of the fixture, use {@link ExternalJenkinsRule#fixture(String)} to get the provisioned fixture from test.
     */
    String name();

    /**
     * Path to resource file with JCasC YAML definition.
     */
    String resource();

    /**
     * Additional plugins to install for the test.
     *
     * Note JCasC plugin(s) are added by the {@link ExternalJenkinsRule}. So are their dependencies and dependencies of these plugins.
     */
    String[] injectPlugins() default {};

    /**
     * A set of custom roles fixture can be part of. Can be used by {@link ExternalJenkinsRule} subclass' hook methods for customization.
     */
    Class<? extends Role>[] roles() default {};

    interface Role {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target({METHOD, TYPE})
    @Documented
    @interface Container {
        ExternalFixture[] value();
    }

    class Builder {
        // Name is intentionally left without setter here for the lack of use-case and possible confusion
        private String name;
        private String resource;
        private List<String> injectPlugins = new ArrayList<>();
        private List<Class<? extends Role>> roles = new ArrayList<>();

        public static Builder from(ExternalFixture f) {
            Builder builder = new Builder().setResource(f.resource()).setInjectPlugins(f.injectPlugins()).setRoles(f.roles());
            builder.name = f.name();
            return builder;
        }

        public Builder setResource(String resource) {
            this.resource = resource;
            return this;
        }

        public Builder setInjectPlugins(String... injectPlugins) {
            this.injectPlugins = new ArrayList<>(Arrays.asList(injectPlugins));
            return this;
        }

        public Builder setInjectPlugins(List<String> injectPlugins) {
            this.injectPlugins = new ArrayList<>(injectPlugins);
            return this;
        }

        public Builder addInjectPlugins(String... injectPlugins) {
            this.injectPlugins.addAll(Arrays.asList(injectPlugins));
            return this;
        }

        @SafeVarargs
        public final Builder setRoles(Class<? extends Role>... roles) {
            this.roles = Arrays.asList(roles);
            return this;
        }

        public void setRoles(List<Class<? extends Role>> roles) {
            this.roles = roles;
        }

        public ExternalFixture build() {
            return new ExternalFixture() {

                @Override public Class<? extends Annotation> annotationType() {
                    return this.getClass();
                }

                @Override public String name() {
                    return name;
                }

                @Override public String resource() {
                    return resource;
                }

                @Override public String[] injectPlugins() {
                    return injectPlugins.toArray(new String[0]);
                }

                @Override public Class<? extends Role>[] roles() {
                    @SuppressWarnings("unchecked")
                    Class<? extends Role>[] a = new Class[0];
                    return roles.toArray(a);
                }
            };
        }
    }
}
