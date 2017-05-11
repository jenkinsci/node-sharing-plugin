package com.scoheb.foreman.cli.model;

/**
 * Created by shebert on 05/01/17.
 */
public class OperatingSystem {
    private String name;
    private String major;
    private String minor;
    private String family;

    @Override
    public String toString() {
        return "OperatingSystem{" +
                "name='" + getName() + '\'' +
                ", major='" + getMajor() + '\'' +
                ", minor='" + getMinor() + '\'' +
                ", family='" + getFamily() + '\'' +
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

    public String getMajor() {
        return major;
    }

    public void setMajor(String major) {
        this.major = major;
    }

    public String getMinor() {
        return minor;
    }

    public void setMinor(String minor) {
        this.minor = minor;
    }

    public String getFamily() {
        return family;
    }

    public void setFamily(String family) {
        this.family = family;
    }
}
