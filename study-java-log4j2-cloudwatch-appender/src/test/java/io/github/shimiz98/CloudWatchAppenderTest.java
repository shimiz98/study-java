// SPDX-FileCopyrightText: 2025 shimiz98
// SPDX-License-Identifier: MIT
package io.github.shimiz98;

import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsResponse;

class CloudWatchAppenderTest {

    @Mock
    private CloudWatchLogsClient cwLogsClient;

    @BeforeAll
    static void setUpBeforeClass() throws Exception {
    }

    @BeforeEach
    void initMocks() {
        MockitoAnnotations.initMocks(this);
    }

    @BeforeEach
    void logBegin(TestInfo testInfo) {
        System.out.println("===BEGIN junit=== " + testInfo.getDisplayName());
    }

    @AfterEach
    void logEnd(TestInfo testInfo) {
        System.out.println("====END junit==== " + testInfo.getDisplayName());
    }

    @Test
    void test() throws InterruptedException {
        CloudWatchAppender testTarget = CloudWatchAppender.createAppender(null, "dummy");

        Mockito.when(cwLogsClient.putLogEvents((PutLogEventsRequest) Mockito.any()))
                .thenReturn(PutLogEventsResponse.builder().build());

        testTarget.start();
        testTarget.cwLogsClient = cwLogsClient;
        LogEvent log1 = Log4jLogEvent.newBuilder().setTimeMillis(11).setMessage(new SimpleMessage("111"))
                .setLoggerName("dummy").build();
        testTarget.append(log1);
        testTarget.stop();

        PutLogEventsRequest req = PutLogEventsRequest.builder().logGroupName("myapp-lg").logStreamName("myapp-ls").build();
        Mockito.verify(cwLogsClient, Mockito.times(1)).putLogEvents(
                req.toBuilder().logEvents(InputLogEvent.builder().timestamp(11L).message("111").build()).build());
    }

    @Test
    void testWait() throws InterruptedException {
        CloudWatchAppender testTarget = CloudWatchAppender.createAppender(null, "dummy");

        Mockito.when(cwLogsClient.putLogEvents((PutLogEventsRequest) Mockito.any()))
                .thenReturn(PutLogEventsResponse.builder().build());

        Log4jLogEvent baseLog = Log4jLogEvent.newBuilder().setLoggerName("dummy").build();

        int cfgMaxSendDelay = 2000;

        testTarget.start();
        testTarget.cwLogsClient = cwLogsClient;
        LogEvent log1 = Log4jLogEvent.newBuilder().setTimeMillis(11).setMessage(new SimpleMessage("111"))
                .setLoggerName("dummy").build();
        testTarget.append(log1);
        testTarget.append(baseLog.asBuilder().setTimeMillis(22).setMessage(new SimpleMessage("222")).build());
        testTarget.append(baseLog.asBuilder().setTimeMillis(33).setMessage(new SimpleMessage("333")).build());
        Thread.sleep(cfgMaxSendDelay + 1);
        testTarget.append(baseLog.asBuilder().setTimeMillis(44).setMessage(new SimpleMessage("444")).build());
        testTarget.stop();

        PutLogEventsRequest req = PutLogEventsRequest.builder().logGroupName("myapp-lg").logStreamName("myapp-ls")
                .build();
        Mockito.verify(cwLogsClient, Mockito.times(1)).putLogEvents(req.toBuilder()
                .logEvents(newInputLogEvent(11, "111"), newInputLogEvent(22, "222"), newInputLogEvent(33, "333"))
                .build());
        Mockito.verify(cwLogsClient, Mockito.times(1)).putLogEvents(req.toBuilder()
                .logEvents(newInputLogEvent(44, "444"))
                .build());
    }

    InputLogEvent newInputLogEvent(long timestamp, String message) {
        return InputLogEvent.builder().timestamp(timestamp).message(message).build();
    }
}
