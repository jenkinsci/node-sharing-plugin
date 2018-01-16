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

import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpRequestBase;

import javax.annotation.Nonnull;
import java.io.IOException;

/**
 * Capture content of HttpResponse for test purposes.
 *
 * To be used as a {@link ResponseHandler} for {@link RestEndpoint}<tt>#executeRequest</tt>.
 */
public final class ResponseCaptor extends RestEndpoint.AbstractResponseHandler<ResponseCaptor.Capture> {
    public ResponseCaptor() {
        super(null);
    }

    @Override protected boolean shouldFail(@Nonnull StatusLine sl) { return false; }

    @Override protected boolean shouldWarn(@Nonnull StatusLine sl) { return false; }

    @Override
    public Capture handleResponse(HttpResponse response) throws IOException {
        return new Capture(response.getStatusLine(), getPayloadAsString(response));
    }

    public static final class Capture {

        public final StatusLine statusLine;
        public final String payload;

        public Capture(StatusLine statusLine, String payload) {

            this.statusLine = statusLine;
            this.payload = payload;
        }
    }
}
