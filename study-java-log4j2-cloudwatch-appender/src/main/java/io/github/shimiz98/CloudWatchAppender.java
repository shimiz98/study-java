// SPDX-FileCopyrightText: 2025 shimiz98
// SPDX-License-Identifier: MIT
package io.github.shimiz98;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Node;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginConfiguration;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsResponse;

@Plugin(name = "CloudWatchAppender", category = Node.CATEGORY, elementType = Appender.ELEMENT_TYPE)
public class CloudWatchAppender extends AbstractAppender {
    // ===== 定数 =====
    private final int CWL_MAX_EVENT_COUNT = 10_000;
    // https://docs.oracle.com/javase/jp/8/docs/api/java/util/concurrent/BlockingQueue.html
    // コンシューマによって取得されたときに適宜解釈される特殊なend-of-streamまたはpoisonオブジェクトを挿入するという一般的な方法があります。
    private final LogEvent STOP_SENDER_THREAD_LOG_EVENT = new Log4jLogEvent();
     
    // ===== static メソッド =====
    // https://logging.apache.org/log4j/2.x/manual/plugins.html#plugin-discovery
    @PluginFactory
    public static CloudWatchAppender createAppender( // @formatter:off
            @PluginConfiguration final Configuration config,
            @PluginAttribute("name") String name) {
            // @formatter:on
        return new CloudWatchAppender(name, null, null, false, null);
    }

    // ===== インスタンス変数 =====
    private final BlockingQueue<LogEvent> blockingQueue;
    CloudWatchLogsClient cwLogsClient; // TODO junitのためprivateを外したのを戻す
    private Thread logSenderThread;

    private int cfgMaxQueueLength = 999;
    private long cfgMaxSendDelayNano = 2_000_000_000;
    private long cfgMaxStopDelay = 1_000;
    // private long cfgCwMaxCountPerSend = 10_000;
    // private long cfgCwMaxBytesPerSend
    // private long cfgCwMaxBytesPerLogEvent
    private String cfgRegionName = "ap-northeast-1";
    // TODO private String cfgEndpointUrl = null;
    private String cfgLogGroupName = "myapp-lg";
    private String cfgLogStreamName = "myapp-ls";

    // ===== コンストラクタ =====
    public CloudWatchAppender(final String name, final Filter filter, final Layout<? extends Serializable> layout,
            final boolean ignoreExceptions, final Property[] properties) {
        super(name, filter, layout, ignoreExceptions, properties);
        blockingQueue = new ArrayBlockingQueue<>(cfgMaxQueueLength);
    }

    // ===== AbstractAppender の Override メソッド =====
    @Override
    public void append(LogEvent event) {
        System.out.printf("===append()=== %s\n", event.getMessage().getFormattedMessage());
        if (blockingQueue.offer(event) == false) {
            System.err.printf("[ERROR] blockingQueue is full: size=%d: %s\n", blockingQueue.size(),
                    this.getClass().getName());
        }
    }

    @Override
    public void start() {
        System.out.printf("===start()===\n", this.getClass().getName());
        super.start();
        this.cwLogsClient = newCloudWatchLogsClient();
        this.logSenderThread = newLogSenderThread();
        this.logSenderThread.start();
    }

    @Override
    public void stop() {
        System.out.printf("===stop()===\n", this.getClass().getName());
        
        try {
            if (this.blockingQueue.offer(STOP_SENDER_THREAD_LOG_EVENT, cfgMaxStopDelay, TimeUnit.MICROSECONDS)) {
                System.err.printf("[ERROR} blockingQueue is full: STOP_SENDER_THREAD\n");
            }
            this.logSenderThread.join(cfgMaxStopDelay); // TODO offer()とjoin()のそれぞれ最大1秒待ちを、合計で最大1秒待ちにする

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // TODO stop()の仕様的にこれで良いか?
        }
        super.stop();
    }

    // ===== 内部処理 =====
    CloudWatchLogsClient newCloudWatchLogsClient() {
        return CloudWatchLogsClient.builder().region(Region.of(cfgRegionName)).build();
    }

    Thread newLogSenderThread() {
        String threadName = this.getClass().getName();
        Thread t = new Thread(threadName) {
            public void run() {
                waitAndSendLogs();
            }
        };
        t.setDaemon(true);
        return t;
    }

    /**
     * ログ転送する
     * <ul>
     *   <li>ログが来たら最大2秒待ち、待っている間に来たログと1個のリクエストにしてCloudWatchへログ転送する
     *   <li>ただし、1回にCloudWatchにPutLogEventsできる上限を超えたら、直ちにCloudWatchへログ転送する。
     *   <li>また STOP_SENDER_THREADが来たら、そこまでのログをログ転送してから、returnする。
     * </ul>
     */
    void waitAndSendLogs() {
        InputLogEvent nextCwLogEvent = null; // 次にログ転送するログ。初回は無いのでnull。
        long nextSendNanoTime = 0; // 次のログ転送する時刻。System.nanoTime()はゼロになる可能性もあるので、このゼロを判定に使わないこと。
        boolean logSenderThreadStopFlag = false;
        
        while (logSenderThreadStopFlag == false) {
            List<InputLogEvent> cwLogEvents = new ArrayList<>();
            int cwLogEventsLength = 0;
            if (nextCwLogEvent != null) {
                cwLogEvents.add(nextCwLogEvent);
                nextCwLogEvent = null;
            }
            // 「i=0」でないのが若干気持ち悪いが、1回のログ転送で最大10,000個を明確化するためfor文を使う
            for (int i = cwLogEvents.size(); i < CWL_MAX_EVENT_COUNT; i++) {
                long timeout;
                if (cwLogEvents.isEmpty()) {
                    timeout = Long.MAX_VALUE; // まだログが無いため、無限に待つ
                } else {
                    timeout = nextSendNanoTime - System.nanoTime();
                    if (timeout <= 0) {
                        break; // 時間経過したため、ログ転送する
                    }
                }

                LogEvent logEvent;
                try {
                    logEvent = blockingQueue.poll(timeout, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    // stop()が呼ばれたので、ループを抜けてログ転送してから、returnする
                    logSenderThreadStopFlag = true; // TODO すぐに return する方が良いか?
                    break;
                }
                System.out.printf("===END== blockingQueue.poll()=== %s %s\n", this.getClass().getName(), logEvent);
                if (logEvent == null) {
                    break; // 時間経過したため、ループを抜けて、ログ転送する
                }
                if (logEvent == STOP_SENDER_THREAD_LOG_EVENT) {
                    logSenderThreadStopFlag = true;
                    break; // stop()が呼ばれたので、ループを抜けてログ転送してから、returnする
                }
                if (cwLogEvents.isEmpty()) {
                    // 次にログ転送する時刻を決める
                    nextSendNanoTime = System.nanoTime() + cfgMaxSendDelayNano;
                }

                InputLogEvent cwLogEvent = newCwLogEvent(logEvent);

                // TODO check 「Each log event can be no larger than 1 MB.」
                // WANT-TO 1MBを超えた部分は切り捨てと、1MBごとに分割してログ転送を選択可能にする。

                cwLogEventsLength += cwLogEvent.message().length() + 26; // TODO 正確に UTF-8 で計算する
                if (1_048_576 < cwLogEventsLength) {
                    // https://docs.aws.amazon.com/AmazonCloudWatchLogs/latest/APIReference/API_PutLogEvents.html
                    // check 「The maximum batch size is 1,048,576 bytes. UTF-8,plus 26 bytes for
                    // each log event.」
                    nextCwLogEvent = cwLogEvent;
                    // TODO 次にログ転送する時刻を決める
                    break; // 合計サイズが超過したため、ループを抜けて、ログ転送する
                }
                cwLogEvents.add(cwLogEvent);
            }
            if (!cwLogEvents.isEmpty()) { // stop()が呼ばれた場合は、emptyの可能性があるので判定する
                // CloudWatch へのログ転送する。※ここに書くと長いので別のメソッドに切り出した
                sendLogs(cwLogEvents);
            }
        }
    }

    void sendLogs(List<InputLogEvent> cwLogEvents) {
        PutLogEventsRequest req = PutLogEventsRequest.builder().logGroupName(cfgLogGroupName)
                .logStreamName(cfgLogStreamName).logEvents(cwLogEvents).build();
        PutLogEventsResponse res = cwLogsClient.putLogEvents(req);
        // TODO 10回くらいリトライする ※ per-second per-account の rate limit と、一時的なネットワーク障害への対処として。
        // https://docs.aws.amazon.com/AmazonCloudWatchLogs/latest/APIReference/API_PutLogEvents.html
        // The quota of five requests per second per log stream has been removed. Instead, PutLogEvents actions are throttled based on a per-second per-account quota. You can request an increase to the per-second throttling quota by using the Service Quotas service.
        if (res.rejectedLogEventsInfo() != null) {
            System.err.printf("[ERROR] CloudWatchClient.PutLogEvents: %s: %s\n", this.getClass().getName(),
                    res);
        }        
    }

    InputLogEvent newCwLogEvent(LogEvent log4j2LogEvent) {
        // TODO Layoutを使って文字列に変換する
        return InputLogEvent.builder().timestamp(log4j2LogEvent.getTimeMillis())
                .message(log4j2LogEvent.getMessage().getFormattedMessage()).build();
    }
}
