package com.example.ftpservers.Client;

public class FTPItem {
    private String name;
    private String type;
    private long size;
    private String lastModified;

    public FTPItem(String name, String type, long size, String lastModified) {
        this.name = name;
        this.type = type;
        this.size = size;
        this.lastModified = lastModified;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public long getSize() {
        return size;
    }

    public String getLastModified() {
        return lastModified;
    }

    @Override
    public String toString() {
        return String.format("%s\t%s\t%d\t%s", getName(), type, size, lastModified);
    }
}
