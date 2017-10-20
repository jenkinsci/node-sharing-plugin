package com.redhat.foreman.cli.model;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

/**
 * Created by shebert on 05/01/17.
 */
public class Medium {
    private String name;
    private String path;
    private String os_family;

    @Override @Nonnull
    public String toString() {
        return "Medium{" +
                "name='" + getName() + '\'' +
                ", path='" + getPath() + '\'' +
                ", os_family='" + getOs_family() + '\'' +
                ", id=" + id +
                '}';
    }

    public int id;

    @CheckForNull
    public String getName() {
        return name;
    }

    public void setName(@Nonnull final String name) {
        this.name = name;
    }

    @CheckForNull
    public String getPath() {
        return path;
    }

    public void setPath(@Nonnull final String path) {
        this.path = path;
    }

    @CheckForNull
    public String getOs_family() {
        return os_family;
    }

    public void setOs_family(@Nonnull final String os_family) {
        this.os_family = os_family;
    }
}
