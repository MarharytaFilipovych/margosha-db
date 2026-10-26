package com.margosha.ring;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

public final class RingSettings {

    static final String RESOURCE = "/ring.properties";
    static final String HASH_FUNCTION_KEY = "ring.hash-function";
    static final String VIRTUAL_NODES_KEY = "ring.virtual-nodes";

    private final String hashFunction;
    private final int virtualNodes;

    private RingSettings(String hashFunction, int virtualNodes) {
        this.hashFunction = hashFunction;
        this.virtualNodes = virtualNodes;
    }

    public static RingSettings load() {
        try (InputStream in = RingSettings.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " not found on the classpath");
            }
            Properties properties = new Properties();
            properties.load(in);
            return fromProperties(properties);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + RESOURCE, e);
        }
    }

    static RingSettings fromProperties(Properties properties) {
        String hashFunction = required(properties, HASH_FUNCTION_KEY);
        String virtualNodesValue = required(properties, VIRTUAL_NODES_KEY);
        int virtualNodes;
        try {
            virtualNodes = Integer.parseInt(virtualNodesValue);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(VIRTUAL_NODES_KEY + " must be a number, was " + virtualNodesValue, e);
        }
        if (virtualNodes < 1) {
            throw new IllegalArgumentException(VIRTUAL_NODES_KEY + " must be at least 1, was " + virtualNodes);
        }
        return new RingSettings(hashFunction, virtualNodes);
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " must be set in " + RESOURCE);
        }
        return value.trim();
    }

    public String hashFunction() {
        return hashFunction;
    }

    public int virtualNodes() {
        return virtualNodes;
    }

    public HashRing newRing() {
        return new HashRing(HashFunctions.forName(hashFunction), virtualNodes);
    }
}
