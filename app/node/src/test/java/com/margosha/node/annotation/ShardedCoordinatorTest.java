package com.margosha.node.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(
		useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
		args = {"coordinator", "--mode=sharded"},
		properties = "margosha.shard.timeout=500ms",
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
public @interface ShardedCoordinatorTest { }
