package io.github.shimiz98;

import java.time.LocalDateTime;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;

class CloudWatchAppenderTest {
    @Test
    void smorkTest() throws InterruptedException {
        Logger logger = LogManager.getLogger(this.getClass());
        logger.info("hello world!! {}", LocalDateTime.now().toString());

        Thread.sleep(3000);
    }
}
