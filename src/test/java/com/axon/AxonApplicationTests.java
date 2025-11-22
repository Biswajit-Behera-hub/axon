package com.axon;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        // Dummy key to satisfy @Value("${app.fireworks.api-key}")
        "app.fireworks.api-key=test-dummy-key",
        "app.ai.api-url=http://localhost:8080",

        // IMPORTANT: Disable interactive mode so tests don't wait for user input
        "spring.shell.interactive.enabled=false",
        "spring.shell.script.enabled=false"
})
class AxonApplicationTests {

    @Test
    void contextLoads() {
        // Verifies Spring Context starts successfully
    }
}