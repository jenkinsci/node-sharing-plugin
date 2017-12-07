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
import com.redhat.jenkins.nodesharing.transport.Entity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.AbstractHttpEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

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

    private final @Nonnull String url;

    public RestEndpoint(@Nonnull String url) {
        this.url = url;
    }

    public HttpPost post(@Nonnull String path) {
        return new HttpPost(url + '/' + path);
    }

    /**
     * Execute HttpRequest.
     *
     * @param method Method and url to be invoked.
     * @param returnType Type the response should be converted at.
     * @param requestEntity Entity to be sent in request body.
     * @throws ActionFailed.CommunicationError When there ware problems executing the request.
     */
    public <T extends Entity> T executeRequest(
            @Nonnull HttpEntityEnclosingRequestBase method,
            @Nullable Class<T> returnType,
            @Nullable Entity requestEntity
    ) throws ActionFailed {
        CloseableHttpClient client = HttpClients.createSystem();
        try {
            if (requestEntity != null) {
                method.setEntity(new WrappingEntity(requestEntity));
            }
            CloseableHttpResponse response = client.execute(method);
            // TODO check result
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

    // Wrap transport.Entity into HttpEntity
    private static final class WrappingEntity  extends AbstractHttpEntity {

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
