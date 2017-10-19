package com.redhat.foreman.cli.model;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by shebert on 20/01/17.
 */
public class HostTypeAdapter extends TypeAdapter<Host> {
    final private transient static Map<String, String> parameterMapping = new HashMap<>();
    static {
        parameterMapping.put("labels", "JENKINS_LABEL");
        parameterMapping.put("remoteFs", "JENKINS_SLAVE_REMOTEFS_ROOT");
        parameterMapping.put("javaPath", "JENKINS_SLAVE_JAVA_PATH");
    }

    @Nonnull
    static public Map<String, String> getParameterMapping() {
        return new HashMap<String, String>(parameterMapping);
    }

    @Override
    public void write(@Nonnull final JsonWriter out, @Nonnull final Host host) throws IOException {
        out.beginObject();
        out.name("name").value(host.getName());
        out.name("labels").value(Host.getParamOrEmptyString(host, "JENKINS_LABEL"));
        out.name("remoteFs").value(Host.getParamOrEmptyString(host, "JENKINS_SLAVE_REMOTEFS_ROOT"));
        out.name("javaPath").value(Host.getParamOrEmptyString(host, "JENKINS_SLAVE_JAVA_PATH"));
        out.endObject();
    }

    @Override @Nonnull
    public Host read(@Nonnull final JsonReader reader) throws IOException {
        final Host host = new Host();
        JsonToken token = reader.peek();
        if (token.equals(JsonToken.BEGIN_OBJECT)) {
            reader.beginObject();
            while (!reader.peek().equals(JsonToken.END_OBJECT)) {
                if (reader.peek().equals(JsonToken.NAME)) {
                    String next = reader.nextName();
                    switch (next) {
                        case "name":
                            host.setName(reader.nextString());
                            break;
                        case "labels":
                            host.addParameter(new Parameter(parameterMapping.get(next), reader.nextString()));
                            break;
                        case "remoteFs":
                            host.addParameter(new Parameter(parameterMapping.get(next), reader.nextString()));
                            break;
                        case "javaPath":
                            host.addParameter(new Parameter(parameterMapping.get(next), reader.nextString()));
                            break;
                        default:
                            reader.skipValue();
                            break;
                    }
                }
            }
            reader.endObject();
        }
        return host;
    }
}
