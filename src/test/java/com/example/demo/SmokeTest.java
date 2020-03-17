package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@SpringBootTest
public class SmokeTest {

    @Autowired
    private MoodController controller;

    @Test
    public void contexLoads() {
        assertThat(controller, is(notNullValue()));
    }
}
