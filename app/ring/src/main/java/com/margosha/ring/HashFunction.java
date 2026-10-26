package com.margosha.ring;

@FunctionalInterface
public interface HashFunction {

    long hash(String value);
}
