package com.margosha.ring;

public final class HashFunctions {

    private HashFunctions() { }

    public static HashFunction forName(String name) {
        if (name != null && name.equals(Murmur3HashFunction.NAME)) {
            return new Murmur3HashFunction();
        }
        throw new IllegalArgumentException("Unknown hash function: " + name);
    }
}
