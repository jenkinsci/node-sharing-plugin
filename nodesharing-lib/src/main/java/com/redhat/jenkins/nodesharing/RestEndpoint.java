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

import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import com.google.common.annotations.VisibleForTesting;
import com.google.gson.JsonParseException;
import com.redhat.jenkins.nodesharing.transport.AbstractEntity;
import com.redhat.jenkins.nodesharing.transport.CrumbResponse;
import com.redhat.jenkins.nodesharing.transport.Entity;
import hudson.Util;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.security.Permission;
import hudson.security.PermissionGroup;
import hudson.security.PermissionScope;
import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.auth.AuthScope;
import org.apache.http.client.AuthCache;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.entity.AbstractHttpEntity;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHeader;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Rest endpoint representing "the other side" to talk to.
 *
 * @author ogondza.
 */
public class RestEndpoint {
    private static final Logger LOGGER = Logger.getLogger(RestEndpoint.class.getName());

    // Timeout for REST network communication in ms
    public static final int TIMEOUT = parseTimeout();

    // Default REST calls timeout in ms
    private static final int DEFAULT_TIMEOUT = 30 * 1000;
    private static final String PROPERTY_NAME = "com.redhat.jenkins.nodesharing.RestEndpoint.TIMEOUT";

    private static int parseTimeout() {
        String strTimeout = Util.fixEmptyAndTrim(System.getProperty(PROPERTY_NAME));
        if (strTimeout != null) {
            try {
                int timeout = Integer.parseInt(strTimeout);
                if (timeout > 0) {
                    if (timeout < DEFAULT_TIMEOUT) {
                        LOGGER.warning("Using " + PROPERTY_NAME + " shorter than the default (" + DEFAULT_TIMEOUT + ") may be problematic");
                    }
                    return timeout;
                } else {
                    LOGGER.warning("Value of " + PROPERTY_NAME + " is invalid, using default " + DEFAULT_TIMEOUT);
                }
            } catch (NumberFormatException e) {
                LOGGER.log(Level.WARNING, "Unable to parse TIMEOUT, using default value " + DEFAULT_TIMEOUT, e);
            }
        }
        return DEFAULT_TIMEOUT;
    }

    private static final PermissionGroup NODE_SHARING_GROUP = new PermissionGroup(RestEndpoint.class, Messages._RestEndpoint_PermissionGroupName());
    public static final Permission RESERVE = new Permission(NODE_SHARING_GROUP, "Reserve", Messages._RestEndpoint_ReserveDescription(), null, PermissionScope.JENKINS);

    // Since the permission is declared in a class that might not be loaded for a while after Jenkins startup or plugin
    // install, adding dummy initializer to kick in during startup causing Jenkins to initialize this class and register
    // the permission.
    @Initializer(before = InitMilestone.JOB_LOADED) @Restricted(DoNotUse.class)
    public static void checkPermissionRegistered() {
        final String permId = "com.redhat.jenkins.nodesharing.RestEndpoint.Reserve";
        for (Permission permission : Permission.getAll()) {
            if (permId.equals(permission.getId())) return;
        }
        throw new AssertionError("Permission " + permId + " not registered");
    }

    private static final RequestConfig REQUEST_CONFIG = RequestConfig.custom()
            .setConnectTimeout(TIMEOUT)
            .setConnectionRequestTimeout(TIMEOUT)
            .setSocketTimeout(TIMEOUT)
            .build()
    ;

    private final @Nonnull String endpoint;
    private final @Nonnull String crumbIssuerEndpoint;
    private final @Nonnull UsernamePasswordCredentials creds;

    public RestEndpoint(@Nonnull String jenkinsUrl, @Nonnull String endpointPath, @Nonnull UsernamePasswordCredentials creds) {
        Objects.requireNonNull(jenkinsUrl);
        Objects.requireNonNull(endpointPath);
        Objects.requireNonNull(creds);

        this.endpoint = jenkinsUrl + endpointPath;
        this.crumbIssuerEndpoint = jenkinsUrl + "crumbIssuer/api/json";
        this.creds = creds;
    }

    public HttpPost post(@Nonnull String path) {
        return new HttpPost(endpoint + '/' + path);
    }

    /**
     * Execute HttpRequest.
     *
     * @param method Method and url to be invoked.
     * @param requestEntity Entity to be sent in request body.
     * @param returnType Type the response should be converted at.
     *
     * @throws ActionFailed.CommunicationError When there ware problems executing the request.
     * @throws ActionFailed.ProtocolMismatch When there is a problem reading the response.
     * @throws ActionFailed.RequestFailed When status code different from 200 was returned.
     */
    public <T extends AbstractEntity> T executeRequest(
            @Nonnull HttpEntityEnclosingRequestBase method,
            @Nonnull Entity requestEntity,
            @Nonnull Class<T> returnType
    ) throws ActionFailed {
        method.addHeader(getCrumbHeader());
        method.setEntity(new WrappingEntity(requestEntity));
        return _executeRequest(method, new DefaultResponseHandler<>(method, returnType));
    }

    /**
     * Execute HttpRequest.
     *
     * @param method Method and url to be invoked.
     * @param requestEntity Entity to be sent in request body.
     * @param handler Response handler to be used.
     *
     * @throws ActionFailed.CommunicationError When there ware problems executing the request.
     * @throws ActionFailed.RequestTimeout When the request timed out.
     * @throws ActionFailed.ProtocolMismatch When there is a problem reading the response.
     * @throws ActionFailed.RequestFailed When status code different from 200 was returned.
     */
    public <T> T executeRequest(
            @Nonnull HttpEntityEnclosingRequestBase method,
            @Nonnull Entity requestEntity,
            @Nonnull ResponseHandler<T> handler
    ) throws ActionFailed {
        method.addHeader(getCrumbHeader());
        method.setEntity(new WrappingEntity(requestEntity));
        return _executeRequest(method, handler);
    }

    @VisibleForTesting
    /*package*/ <T> T executeRequest(
            @Nonnull HttpEntityEnclosingRequestBase method,
            @Nonnull ResponseHandler<T> handler
    ) throws ActionFailed {
        method.addHeader(getCrumbHeader());
        return _executeRequest(method, handler);
    }

    @CheckForNull
    private <T> T _executeRequest(@Nonnull HttpRequestBase method, @Nonnull ResponseHandler<T> handler) {
        method.setConfig(REQUEST_CONFIG);

        CloseableHttpClient client = HttpClients.createSystem();
        try {
            return client.execute(method, handler, getAuthenticatingContext(method));
        } catch (SocketTimeoutException e) {
            throw new ActionFailed.RequestTimeout("Failed executing REST call: " + method, e);
        } catch (IOException e) {
            throw new ActionFailed.CommunicationError("Failed executing REST call: " + method, e);
        } finally {
            try {
                client.close();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Unable to close HttpClient", e); // $COVERAGE-IGNORE$
            }
        }
    }

    // https://hc.apache.org/httpcomponents-client-ga/tutorial/html/authentication.html#d5e717
    private @Nonnull HttpClientContext getAuthenticatingContext(@Nonnull HttpRequestBase method) {
        AuthCache authCache = new BasicAuthCache();
        BasicScheme basicAuth = new BasicScheme();
        authCache.put(URIUtils.extractHost(method.getURI()), basicAuth);

        CredentialsProvider provider = new BasicCredentialsProvider();
        provider.setCredentials(AuthScope.ANY, new org.apache.http.auth.UsernamePasswordCredentials(
                creds.getUsername(), creds.getPassword().getPlainText()
        ));
        HttpClientContext context = HttpClientContext.create();
        context.setCredentialsProvider(provider);
        context.setAuthCache(authCache);
        return context;
    }

    private Header getCrumbHeader() {
        final HttpGet method = new HttpGet(crumbIssuerEndpoint);
        CrumbResponse crumbResponse = _executeRequest(method, new AbstractResponseHandler<CrumbResponse>(method) {
            private final List<Integer> ACCEPTED_CODES = Arrays.asList(200, 404);

            @Override
            protected boolean shouldFail(@Nonnull StatusLine sl) {
                return !ACCEPTED_CODES.contains(sl.getStatusCode());
            }

            @Override
            protected @CheckForNull CrumbResponse consumeEntity(@Nonnull HttpResponse response) throws IOException {
                if (response.getStatusLine().getStatusCode() == 404) return null;
                return createEntity(response, CrumbResponse.class);
            }
        });

        if (crumbResponse == null) { // No crumb issuer used by other side
            return new BasicHeader("Jenkins-Crumb", "Not-Used");
        }

        return new BasicHeader(crumbResponse.getCrumbRequestField(), crumbResponse.getCrumb());
    }

    public static class AbstractResponseHandler<T> implements ResponseHandler<T> {
        protected final @Nonnull HttpRequestBase method;
        protected AbstractResponseHandler(@Nonnull HttpRequestBase method) {
            this.method = method;
        }

        @Override
        public @CheckForNull T handleResponse(HttpResponse response) throws IOException {
            StatusLine sl = response.getStatusLine();
            if (shouldFail(sl)) {
                String payload = proccessErrorPayload(getPayloadAsString(response));
                throw new ActionFailed.RequestFailed(method, response.getStatusLine(), payload);
            }

            return consumeEntity(response);
        }

        protected boolean shouldFail(@Nonnull StatusLine sl) {
            return sl.getStatusCode() != 200;
        }

        protected @CheckForNull T consumeEntity(@Nonnull HttpResponse response) throws IOException {
            return null;
        }

        protected final @Nonnull T createEntity(@Nonnull HttpResponse response, @Nonnull Class<? extends T> returnType) throws IOException {
            try (InputStream is = response.getEntity().getContent()) {
                return Entity.fromInputStream(is, returnType);
            } catch (JsonParseException ex) {
                throw new ActionFailed.ProtocolMismatch("Unable to create entity: " + returnType, ex);
            }
        }

        protected final @Nonnull String getPayloadAsString(@Nonnull HttpResponse response) throws IOException {
            try (InputStream is = response.getEntity().getContent()) {
                return IOUtils.toString(is);
            }
        }

        // Clear some of the common error causes
        private @Nonnull String proccessErrorPayload(@Nonnull String payload) {
            String JENKINS_LOADING = "Please wait while Jenkins is getting ready to work";
            if (payload.contains(JENKINS_LOADING)) {
                return JENKINS_LOADING;
            }

            return payload;
        }
    }

    /**
     * Fail in case of non-200 status code and create response entity.
     */
    private static final class DefaultResponseHandler<T extends Entity> extends AbstractResponseHandler<T> {

        private final @Nonnull Class<? extends T> returnType;

        private DefaultResponseHandler(@Nonnull HttpRequestBase method, @Nonnull Class<? extends T> returnType) {
            super(method);
            this.returnType = returnType;
        }

        @Override
        public final @Nonnull T handleResponse(HttpResponse response) throws IOException {
            T out = super.handleResponse(response);
            assert out != null;
            return out;
        }

        @Override
        protected @Nonnull T consumeEntity(@Nonnull HttpResponse response) throws IOException {
            return createEntity(response, returnType);
        }
    }

    // Wrap transport.Entity into HttpEntity
    private static final class WrappingEntity extends AbstractHttpEntity {

        private final @Nonnull Entity entity;

        private WrappingEntity(@Nonnull Entity entity) {
            this.entity = entity;
        }

        @Override public boolean isRepeatable() {
            return false;
        }

        @Override public long getContentLength() {
            return -1;
        }

        @Override public void writeTo(OutputStream outstream) {
            entity.toOutputStream(outstream);
        }

        // We should not need this as presumably this is used for receiving entities only
        @Override public InputStream getContent() {
            throw new UnsupportedOperationException(); // $COVERAGE-IGNORE$
        }

        // We should not need this as presumably this is used for receiving entities only
        @Override public boolean isStreaming() {
            return false; // $COVERAGE-IGNORE$
        }
    }
}
