package com.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the Channel agent application.
 *
 * <p>Lives in {@code com.agent}, so component scanning automatically covers the agents,
 * controllers, tools, and model providers under {@code com.agent.*} — no explicit
 * {@code scanBasePackages} needed.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ChannelApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChannelApplication.class, args);
    }
}
