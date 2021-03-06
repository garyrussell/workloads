package com.pivotal.resilient;

import com.pivotal.resilient.chaos.ChaosMonkeyProperties;
import com.pivotal.resilient.chaos.FaultyConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.annotation.Input;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.function.Consumer;

@Service
@EnableBinding(DurableTradeLogger.MessagingBridge.class)
@ConditionalOnProperty(name="durableTradeLogger", matchIfMissing = true)
public class DurableTradeLogger {
    private final Logger logger = LoggerFactory.getLogger(DurableTradeLogger.class);

    @Autowired private MessagingBridge messagingBridge;
    @Autowired private FaultyConsumer faultyConsumer;
    @Value("${processingTime:1s}") private Duration processingTime;

    private TrackMissingTrades missingTradesTracker = new TrackMissingTrades();
    private long processedTradeCount;

    interface MessagingBridge {

        String INPUT = "durable-trade-logger-input";

        @Input(INPUT)
        SubscribableChannel input();

    }

    public DurableTradeLogger() {
        logger.info("Created");
    }

    @StreamListener(MessagingBridge.INPUT)
    public void execute(Trade trade) {
        missingTradesTracker.accept(trade);
        logger.info("Received {}", trade);

        boolean successfully = false;
        try {
            Thread.sleep(processingTime.toMillis());
            faultyConsumer.accept(trade);
            successfully = true;
        } catch (RuntimeException | InterruptedException e) {
            logger.error("An error occurred while processing trade {}. Reason: {}", trade.getId(), e.getMessage());
        } finally {
            logger.info("Processed {} {} ", trade, successfully ? "success" : "failed");
            if (successfully) processedTradeCount++;
            logSummary(trade);
        }
    }

    private void logSummary(Trade trade) {
        logger.info("Trade summary after trade {}: total received:{}, missed:{}, processed:{}",
                trade.getId(), missingTradesTracker.receivedTradeCount,
                missingTradesTracker.missedTradeCount,
                processedTradeCount);
    }

    class TrackMissingTrades implements Consumer<Trade> {
        private long missedTradeCount;
        private long lastTradeId = -1;
        private long receivedTradeCount;

        @Override
        public void accept(Trade trade) {
            receivedTradeCount++;
            if (lastTradeId > -1 && lastTradeId != trade.id) {
                if (trade.id > lastTradeId + 1) missedTradeCount++;
            }
            lastTradeId = trade.id;
        }
    }
}
