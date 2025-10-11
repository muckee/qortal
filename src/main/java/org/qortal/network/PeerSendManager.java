package org.qortal.network;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.network.message.Message;
import org.qortal.network.helper.PeerSpeedTracker;
import org.qortal.network.message.MessageException;


public class PeerSendManager {
    private static final Logger LOGGER = LogManager.getLogger(PeerSendManager.class);

    private static final int MAX_FAILURES = 5; // was 15
    private static final int MAX_MESSAGE_ATTEMPTS = 2; 
    private static final int RETRY_DELAY_MS = 100;
    private static final long COOLDOWN_DURATION_MS = 1_000; // was 20K

    private final Peer peer;
    private PeerSpeedTracker peerSpeedTracker = new PeerSpeedTracker();
    private final BlockingQueue<TimedMessage> queue = new LinkedBlockingQueue<>();
    private final ExecutorService executor;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private static final AtomicInteger threadCount = new AtomicInteger(1);

    private volatile boolean coolingDown = false;
    private volatile long lastUsed = System.currentTimeMillis();

    // Some constants, but any integer 1 (best) to 10 (none/append last) are available
    public static final int HIGH_PRIORITY = 1;
    public static final int MEDIUM_PRIORITY = 5;
    public static final int NO_PRIORITY = 10;

    /**
     * Manages outbound message transmission for a single {@link Peer}, using a
     * background thread to process a prioritized message queue with failure recovery
     * and adaptive timing based on peer performance.
     *
     * <p>This class is responsible for:
     * <ul>
     *   <li>Queuing messages with optional priority ordering.</li>
     *   <li>Estimating transmission times using {@link PeerSpeedTracker}.</li>
     *   <li>Throttling and retrying failed sends up to {@code MAX_FAILURES}.</li>
     *   <li>Entering a cooldown state if too many consecutive send failures occur.</li>
     *   <li>Gracefully shutting down when requested.</li>
     * </ul>
     *
     * <p>Internally uses:
     * <ul>
     *   <li>A {@code LinkedBlockingQueue} for thread-safe message queuing.</li>
     *   <li>An {@code ExecutorService} for running a single background processing thread.</li>
     *   <li>A custom {@code TimedMessage} class to track queue timing and scheduling.</li>
     * </ul>
     *
     * <p>Usage typically involves calling {@code queueMessage()} or {@code queueMessageWithPriority()}
     * to enqueue messages, while the internal worker sends them asynchronously.
     *
     * @see org.qortal.network.Peer
     * @see PeerSpeedTracker
     * @see org.qortal.network.message.Message
     *
     * @since v5.0.1
     * @author Ice & Phil
     */
    public PeerSendManager(Peer peer) {
        this.peer = peer;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setName("PeerSendManager-" + peer.getResolvedAddress().getHostString() + "-" + threadCount.getAndIncrement());
            return t;
        });
        start();
    }

    /**
     * Starts the internal message processing thread for this {@code PeerSendManager}.
     *
     * <p>This method initializes a background task that continuously monitors and processes
     * the outbound message queue for a peer. It performs the following responsibilities:
     * <ul>
     *   <li>Blocks on {@code queue.take()} to retrieve the next {@link TimedMessage}.</li>
     *   <li>Skips processing if the message’s scheduled {@code getExpectedStartTime()} is still in the future.</li>
     *   <li>Attempts to send each message up to {@code MAX_MESSAGE_ATTEMPTS} times with a configurable timeout.</li>
     *   <li>Tracks and increments a failure count if sending fails.</li>
     *   <li>Triggers a peer disconnect and enters a cooldown state if the failure count exceeds {@code MAX_FAILURES}.</li>
     *   <li>Includes basic throttling between messages to prevent excessive send attempts.</li>
     * </ul>
     *
     * <p>The method is invoked once in the constructor and should not be called again manually.
     * It uses a single-threaded {@link ExecutorService} to execute the loop, and the thread
     * terminates if interrupted or a fatal error occurs.
     *
     * @since v5.0.1
     * @author Phil
     * @updated v5.0.2
     * @updater Ice
     */
    private void start() {
        executor.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {

                    TimedMessage timedMessage = queue.take();  // This is a blocking operation
                    if (System.currentTimeMillis() > timedMessage.getExpectedStartTime()) {
                        LOGGER.info("Dropped stale message ({}ms old to peer: {})", timedMessage.message.getId(), peer.toString());
                        continue;
                    }

                    boolean success = false;

                    final Message message = timedMessage.getMessage();

                    for (int attempt = 1; attempt <= MAX_MESSAGE_ATTEMPTS; attempt++) {
                        try {
                            if (peer.sendMessageWithTimeout(message, timedMessage.getExpectedRunTime())) {
                                success = true;
                                failureCount.set(0); // reset on success
                                break;
                            } else {
                                LOGGER.warn("Ut-Oh: peer.sendMessage returned false, timeout was {}", timedMessage.getExpectedRunTime());
                            }
                        } catch (Exception e) {
                            LOGGER.info("Attempt {} failed for message {} to peer {}: {}", attempt, message.getId(), peer, e.getMessage());
                        }

                        Thread.sleep(RETRY_DELAY_MS);
                    }

                    if (!success) {
                        // Maybe we requeue no priority?
                        int totalFailures = failureCount.incrementAndGet();
                        LOGGER.debug("Failed to send message {} to peer {}. Total failures: {}", message.getId(), peer, totalFailures);

                        if (totalFailures >= MAX_FAILURES) {
                            LOGGER.debug("Peer {} exceeded failure limit ({}). Disconnecting...", peer, totalFailures);
                            peer.disconnect("Too many message send failures");
                            coolingDown = true;
                            queue.clear();

                            try {
                                LOGGER.info("Entering sleep - MAX_FAILURES - Cooldown {} MS", COOLDOWN_DURATION_MS);
                                Thread.sleep(COOLDOWN_DURATION_MS);
                                LOGGER.info("Exiting sleep - MAX_FAILURES");
                            } catch (InterruptedException e) {
                                LOGGER.info("Called Thread.interrupt" );
                                Thread.currentThread().interrupt();
                                return;
                            } finally {
                                coolingDown = false;
                                failureCount.set(0);
                            }
                        }
                    }

                    Thread.sleep(25); // small throttle, was 50MS
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOGGER.error("ERROR - Caught InterruptedException");
                    break;
                } catch (Exception e) {
                    LOGGER.error("Unexpected error in PeerSendManager for peer {}: {}", peer, e.getMessage(), e);
                }
            }
        });
    }


    /**
     * Queues a message to be sent to the associated peer with the default priority ({@code NO_PRIORITY}).
     *
     * <p>This is a convenience method that delegates to {@link #queueMessageWithPriority(int, Message)},
     * placing the message at the end of the send queue. The message will be scheduled for transmission
     * according to its estimated send time and the current state of the queue.
     *
     * @param message the message to be queued
     * @throws MessageException if the message cannot be properly prepared or queued
     *
     * @since v5.0.1
     * @author Phil
     */
    public void queueMessage(Message message) throws MessageException {
        queueMessageWithPriority(NO_PRIORITY, message);
    }

    /**
     * Queues a message with a given priority into the send queue.
     *
     * <p>Priority ranges from {@code 1} (highest) to {@code 10} (lowest), where
     * {@code HIGH_PRIORITY} (1) inserts at the front of the queue,
     * {@code NO_PRIORITY} (10) simply appends the message, and other values are
     * placed proportionally in the queue based on their priority rank.
     *
     * @param priority the priority value, where 1 is highest and 10 is lowest
     * @param message the message to be queued
     *
     * @since v5.0.2
     * @author Ice
     */
    public void queueMessageWithPriority(int priority, Message message) throws MessageException {
        if (coolingDown) {
            LOGGER.info("In cooldown, ignoring message {}", message.getId());
            return;
        }

        lastUsed = System.currentTimeMillis();
        long fullQueueTime = lastUsed + 50;
        // @ToDo: due to race conditions we have to snapshot, in Java v16+ we can fix this
        // Other option is to try/catch, on catch leave fullQueueTime as is (empty queue)
        Object[] arr = queue.toArray();  // snapshot copy
        if (arr.length > 0) {
            TimedMessage lastQueueElement = (TimedMessage) arr[arr.length - 1];
            fullQueueTime = lastQueueElement.getExpectedFinishTime();
        }


        TimedMessage newTimedMessage = new TimedMessage(message,
                fullQueueTime,
                peerSpeedTracker.getEstimatedTime(message.toBytes().length));
        int queueSize = queue.size();

        // NO_PRIORITY? Just treat as normal
        if (priority == NO_PRIORITY || queueSize == 0) {
            queue.offer(newTimedMessage);
            return;
        }

        // HIGH_PRIORITY - Add to front
        if (priority == HIGH_PRIORITY ) {
            LOGGER.info("Added to the front of queue - HIGH_PRIORITY");
            LinkedBlockingQueue<TimedMessage> tempQueue = new LinkedBlockingQueue<>();
            tempQueue.offer(newTimedMessage);
            queue.drainTo(tempQueue);
            queue.clear();
            queue.add(newTimedMessage);
            queue.addAll(tempQueue);
            return;
        }

        // Below here is for 2-9 priorities
        // @ToDo Figure out what prios should be for other tasks
        // For other priorities: calculate insertion index based on priority scale (1–10)
        int insertIndex = (int) Math.round(((double) (priority - 1) / 9.0) * queueSize);
        insertIndex = Math.min(insertIndex, queueSize); // Safety bound

        TimedMessage[] allMessages = queue.toArray(new TimedMessage[0]);
        queue.clear();
        synchronized (queue) {
            for (int i = 0; i < insertIndex; i++) {
                queue.offer(allMessages[i]);
            }

            queue.offer(newTimedMessage); // Insert priority message

            for (int i = insertIndex; i < allMessages.length; i++) {
                queue.offer(allMessages[i]);
            }
        }
    }

    /**
     * Returns the current number of messages in the send queue.
     *
     * <p>This reflects how many messages are currently waiting to be sent
     * to the associated peer.
     *
     * @return the number of messages in the queue
     *
     * @since v5.0.2
     * @author Ice
     */
    public int getQueueMessageSize() {
        return queue.size();
    }

    /**
     * Returns the expected time in epoch milliseconds at which the last message
     * currently in the queue is estimated to complete processing.
     *
     * <p>This method takes a snapshot of the current message queue and identifies
     * the {@link TimedMessage} at the tail (i.e., the most recently scheduled message).
     * It then returns the {@code targetStartTime + targetRunTime} for that message,
     * which represents when it is expected to finish sending.
     *
     * <p>This value can be used to estimate overall queue latency or for pacing
     * the insertion of future messages.
     *
     * @return the expected finish time of the last queued message, in epoch milliseconds
     *
     * @throws ArrayIndexOutOfBoundsException if the queue is empty
     *
     * @since v5.0.2
     * @author Ice
     */
    public long getExpectedQueueFinishTime() {
        Object[] arr = queue.toArray();  // snapshot copy
        TimedMessage lastQueueElement = (TimedMessage) arr[arr.length - 1];
        return lastQueueElement.getExpectedFinishTime();
    }

    /**
     * Determines whether the {@code PeerSendManager} has been idle for longer than the specified duration.
     *
     * <p>This method compares the current system time with the timestamp of the last message-related
     * activity. If the elapsed time exceeds the provided {@code cutoffMillis}, the method returns {@code true}.
     * Otherwise, it returns {@code false}.
     *
     * <p>This can be useful for detecting inactive peers and performing cleanup or resource reallocation.
     *
     * @param cutoffMillis the duration in milliseconds to compare against the last activity timestamp
     * @return {@code true} if idle time exceeds {@code cutoffMillis}, otherwise {@code false}
     *
     * @since v5.0.1
     * @author Phil
     */
    public boolean isIdle(long cutoffMillis) {
        return System.currentTimeMillis() - lastUsed > cutoffMillis;
    }

    /**
     * Shuts down the {@code PeerSendManager}, stopping all message processing and clearing the send queue.
     *
     * <p>This method immediately halts the internal executor thread using {@code shutdownNow()}
     * and clears any messages currently pending in the queue. After shutdown, no further
     * message sending will occur, and the instance should be considered unusable.
     *
     * <p>Use this method during application shutdown or when the peer connection is being
     * permanently closed to ensure that system resources are properly released.
     *
     * @since v5.0.1
     * @author Phil
     */
    public void shutdown() {
        queue.clear();
        executor.shutdownNow();
    }

    /**
     * Internal helper class representing a message along with its timestamp and a calculated
     * target processing time based on queue delay and expected send time.
     *
     * <p>This class is used to track when a message was enqueued and to determine
     * when it is expected to be processed, helping with decisions around staleness
     * and scheduling.
     *
     * @since v5.0.2
     * @author Ice
     */
    private static class TimedMessage {
        final Message message;
        final long timestamp;
        final long targetStartTime;
        final int targetRunTime;

        /**
         * Constructs a {@code TimedMessage} with timing metadata.
         *
         * @param message           the message to be tracked
         * @param fullQueueTime     estimated time the message may wait in queue (in ms)
         * @param expectedSendTime  estimated time it will take to send the message (in ms)
         *
         * <p>The {@code targetProcessTime} is calculated as the current system time plus
         * {@code fullQueueTime} and {@code expectedSendTime}, indicating when this
         * message is expected to complete processing.
         *
         * @since v5.0.2
         * @author Ice
         */
        TimedMessage(Message message, long fullQueueTime, int expectedSendTime) {
            this.message = message;
            this.timestamp = System.currentTimeMillis();
            this.targetRunTime = expectedSendTime;
            this.targetStartTime = this.timestamp + fullQueueTime + expectedSendTime;  // is this a full epoch?
        }

        /**
         * Calculates the expected finish time of a message based on its scheduled start time
         * and its estimated run time.
         *
         * <p>This value is computed as the sum of {@code targetStartTime} and {@code targetRunTime}.
         * It represents the theoretical point in time (in epoch milliseconds) when processing
         * of the message is expected to complete.
         *
         * @return the expected finish time in epoch milliseconds
         *
         * @since v5.0.2
         * @author Ice
         */
        public long getExpectedFinishTime() {
            return this.targetStartTime + (long) this.targetRunTime;
        }

        /**
         * Returns the expected start time for processing this message.
         *
         * <p>This value represents the point in time, in epoch milliseconds, when
         * the message is scheduled to begin transmission based on the current queue
         * state and estimated delays.
         *
         * <p>It is primarily used for calculating when the message should be
         * processed and for estimating total queue duration.
         *
         * @return the expected start time in epoch milliseconds
         *
         * @since v5.0.2
         * @author Ice
         */
        public long getExpectedStartTime() {
            return targetStartTime;
        }

        public int getExpectedRunTime() {
            if (targetRunTime == 0)
                return 180000;
            return targetRunTime;
        }

        public Message getMessage() {
            return message;
        }
    }
}
