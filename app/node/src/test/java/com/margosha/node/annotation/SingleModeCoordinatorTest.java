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
		args = {"coordinator", "--mode=single"},
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public @interface SingleModeCoordinatorTest { }
