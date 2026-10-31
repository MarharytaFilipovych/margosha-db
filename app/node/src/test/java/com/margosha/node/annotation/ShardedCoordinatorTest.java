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
		args = {"coordinator", "--mode=sharded",
				"--margosha.data-dir=target/test-data/${random.uuid}",
				"--margosha.replication.factor=1",
				"--margosha.replication.write-quorum=1",
				"--margosha.replication.read-quorum=1"},
		properties = "margosha.shard.timeout=500ms",
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
public @interface ShardedCoordinatorTest { }
