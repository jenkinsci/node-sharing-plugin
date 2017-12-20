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

import com.google.gson.JsonParseException;
import com.redhat.jenkins.nodesharing.transport.AbstractEntity;
import com.redhat.jenkins.nodesharing.transport.CrumbResponse;
import com.redhat.jenkins.nodesharing.transport.Entity;
import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.AbstractHttpEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHeader;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Rest endpoint representing "the other side" to talk to.
 *
 * @author ogondza.
 */
public class RestEndpoint {
    private static final Logger LOGGER = Logger.getLogger(RestEndpoint.class.getName());

    private final @Nonnull String endpoint;
    private final @Nonnull String crumbIssuerEndpoint;

    public RestEndpoint(@Nonnull String jenkinsUrl, @Nonnull String endpointPath) {
        this.endpoint = jenkinsUrl + endpointPath;
        this.crumbIssuerEndpoint = jenkinsUrl + "crumbIssuer/api/json";
    }

    public HttpPost post(@Nonnull String path) {
        return new HttpPost(endpoint + '/' + path);
    }

    public HttpGet get(@Nonnull String path) {
        return new HttpGet(endpoint + '/' + path);
    }

    /**
     * Execute HttpRequest.
     *
     * @param method Method and url to be invoked.
     * @param returnType Type the response should be converted at.
     * @param requestEntity Entity to be sent in request body.
     * @throws ActionFailed.CommunicationError When there ware problems executing the request.
     * @throws ActionFailed.ProtocolMismatch When there is a problem reading the response.
     * @throws ActionFailed.RequestFailed When status code different from 200 was returned.
     */
    public <T extends AbstractEntity> T executeRequest(
            @Nonnull HttpEntityEnclosingRequestBase method,
            @Nullable Class<T> returnType,
            @Nullable Entity requestEntity
    ) throws ActionFailed {
        method.addHeader(getCrumbHeader());
        if (requestEntity != null) {
            method.setEntity(new WrappingEntity(requestEntity));
        }
        return _executeRequest(method, returnType);
    }

    /**
     * Execute HttpRequest.
     *
     * @param method Method and url to be invoked.
     * @param returnType Type the response should be converted at.
     * @throws ActionFailed.CommunicationError When there ware problems executing the request.
     * @throws ActionFailed.ProtocolMismatch When there is a problem reading the response.
     * @throws ActionFailed.RequestFailed When status code different from 200 was returned.
     */
    public <T extends AbstractEntity> T executeRequest(
            @Nonnull HttpRequestBase method,
            @Nullable Class<T> returnType
    ) throws ActionFailed {
        method.addHeader(getCrumbHeader());
        return _executeRequest(method, returnType);
    }

    @CheckForNull
    private <T extends Entity> T _executeRequest(@Nonnull HttpRequestBase method, @Nullable Class<T> returnType) {
        CloseableHttpClient client = HttpClients.createSystem();
        try {
            CloseableHttpResponse response = client.execute(method);

            // Check exit code
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode != 200) {
                try (InputStream is = response.getEntity().getContent()) {
                    ActionFailed.RequestFailed requestFailed = new ActionFailed.RequestFailed(
                            method, response.getStatusLine(), IOUtils.toString(is)
                    );
                    LOGGER.info(requestFailed.getMessage());
                    throw requestFailed;
                }
            }

            try {
                if (returnType == null) {
                    return null;
                }

                try (InputStream is = response.getEntity().getContent()) {
                    return Entity.fromInputStream(is, returnType);
                } catch (JsonParseException ex) {
                    throw new ActionFailed.ProtocolMismatch("Unable to create entity: " + returnType, ex);
                }
            } finally {
                try {
                    response.close();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Unable to close the HttpResponse", e);
                }
            }
        } catch (IOException e) {
            throw new ActionFailed.CommunicationError("Failed executing REST call: " + method, e);
        } finally {
            try {
                client.close();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Unable to close the HttpClient", e);
            }
        }
    }

    private Header getCrumbHeader() {
        CrumbResponse crumbResponse;
        try {
            crumbResponse = _executeRequest(new HttpGet(crumbIssuerEndpoint), CrumbResponse.class);
        } catch (ActionFailed.RequestFailed e) {
            if (e.getStatusCode() == 404) { // No crumb issuer used
                return new BasicHeader("Jenkins-Crumb", "Not-Used");
            }
            throw e;
        }
        return new BasicHeader(crumbResponse.getCrumbRequestField(), crumbResponse.getCrumb());
    }

    // Wrap transport.Entity into HttpEntity
    private static final class WrappingEntity extends AbstractHttpEntity {

        private final @Nonnull Entity entity;

        public WrappingEntity(@Nonnull Entity entity) {
            this.entity = entity;
        }

        @Override public boolean isRepeatable() {
            return false;
        }

        @Override public long getContentLength() {
            return -1;
        }

        @Override public InputStream getContent() throws IOException, UnsupportedOperationException {
            throw new UnsupportedOperationException(
                    "We should not need this as presumably this is used for receiving entities only"
            );
        }

        @Override public void writeTo(OutputStream outstream) throws IOException {
            entity.toOutputStream(outstream);
        }

        @Override public boolean isStreaming() {
            return false;
        }
    }
}
