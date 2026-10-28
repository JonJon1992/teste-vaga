package dev.challenge.activation;

import org.springframework.boot.SpringApplication;

public class TestActivationApplication {

    public static void main(String[] args) {
        SpringApplication.from(ActivationApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
