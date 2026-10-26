package com.margosha.ring;

import java.nio.charset.StandardCharsets;

import org.apache.commons.codec.digest.MurmurHash3;

public final class Murmur3HashFunction implements HashFunction {

    public static final String NAME = "murmur3-128";

    @Override
    public long hash(String value) {
        return MurmurHash3.hash128x64(value.getBytes(StandardCharsets.UTF_8))[0];
    }
}
