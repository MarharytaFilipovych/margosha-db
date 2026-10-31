package com.margosha.node.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(
		useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
		args = {"shard",
				"--margosha.shard.id=test-shard",
				"--margosha.data-dir=target/test-data/${random.uuid}",
				"--margosha.replication.factor=1",
				"--margosha.replication.write-quorum=1",
				"--margosha.replication.read-quorum=1"},
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public @interface ShardNodeTest { }
