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
package com.redhat.jenkins.nodesharing.transport;

import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;

import javax.annotation.Nonnull;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;

/**
 * Abstract entity transmitted over wire.
 *
 * By convention, the implementations are immutable for the advantage of the receiver and valid / fully initialized after
 * constructed by sender to make sure necessary invariants are met before the entity is transmitted.
 *
 * Preferred way to serialize/deserialize object is to use automatic stream based methods which subclasses can override
 * if needed. The String based methods are for more convenience. The implementations are expected to preserve the existing
 * invariant that guarantees the serialized object will be "equivalent" to the source one after deserialized.
 *
 * @author ogondza.
 */
public abstract class Entity {
    protected static final Charset TRANSPORT_CHARSET = Charset.forName("UTF-8");
    private static final Gson GSON = new Gson();

    // Fields transferred with every request
    private final @Nonnull String configRepoUrl;
    private final @Nonnull String version;

//    public Entity() {
//        this.configRepoUrl = null;
//        this.version = null;
//    }

    public Entity(@Nonnull String configRepoUrl, @Nonnull String version) {
        this.configRepoUrl = configRepoUrl;
        this.version = version;
    }

    @Nonnull
    public String getConfigRepoUrl() {
        return configRepoUrl;
    }

    @Nonnull
    public String getVersion() {
        return version;
    }

    /**
     * Write entity to {@link OutputStream}.
     *
     * @throws JsonIOException if there was a problem writing to the writer.
     */
    public void toOutputStream(@Nonnull OutputStream out) throws JsonIOException {
        GSON.toJson(this, new PrintStream(out));
    }

    /**
     * Read entity from stream.
     *
     * @return The entity created.
     * @throws JsonIOException if there was a problem reading from the Reader.
     * @throws JsonSyntaxException if json is not a valid representation for an object of type.
     */
    public static @Nonnull <T> T fromInputStream(@Nonnull InputStream inputStream, @Nonnull Class<T> type) throws JsonSyntaxException, JsonIOException {
        T out = GSON.fromJson(new InputStreamReader(inputStream), type);
        if (out == null) throw new JsonSyntaxException("There was nothing in the stream");
        return out;
    }

    /**
     * Write entity to string.
     *
     * This method delegates to {@link #toOutputStream(OutputStream)} so subclasses must only override that one if needed.
     *
     * @throws JsonIOException if there was a problem writing to the writer.
     */
    public @Nonnull String toString() throws JsonIOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        toOutputStream(baos);
        return baos.toString();
    }

    /**
     * Read entity from string.
     *
     * This method delegates to {@link #fromInputStream(InputStream, Class)} so subclasses must only override that one if needed.
     *
     * @return The entity created. Returns {@code null} if {@code json} is at EOF.
     * @throws JsonIOException if there was a problem reading from the Reader.
     * @throws JsonSyntaxException if json is not a valid representation for an object of type.
     */
    public static @Nonnull <T> T fromString(@Nonnull String in, @Nonnull Class<T> type) throws JsonSyntaxException, JsonIOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(in.getBytes(TRANSPORT_CHARSET));
        return fromInputStream(bais, type);
    }
}
