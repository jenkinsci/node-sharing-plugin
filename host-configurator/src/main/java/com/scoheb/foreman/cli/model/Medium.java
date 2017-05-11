package com.scoheb.foreman.cli.model;

/**
 * Created by shebert on 05/01/17.
 */
public class Medium {
    private String name;
    private String path;
    private String os_family;

    @Override
    public String toString() {
        return "Medium{" +
                "name='" + getName() + '\'' +
                ", path='" + getPath() + '\'' +
                ", os_family='" + getOs_family() + '\'' +
                ", id=" + id +
                '}';
    }

    public int id;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getOs_family() {
        return os_family;
    }

    public void setOs_family(String os_family) {
        this.os_family = os_family;
    }
}
