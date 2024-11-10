package org.qortal.network.task;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.network.Peer;
import org.qortal.network.message.Message;
import org.qortal.network.message.MessageType;
import org.qortal.network.message.PingMessage;
import org.qortal.utils.ExecuteProduceConsume.Task;
import org.qortal.utils.NTP;

public class PingTask implements Task {
    private static final Logger LOGGER = LogManager.getLogger(PingTask.class);

    private final Peer peer;
    private final Long now;
    private final String name;

    public PingTask(Peer peer, Long now) {
        this.peer = peer;
        this.now = now;
        this.name = "PingTask::" + peer;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void perform() throws InterruptedException {
        PingMessage pingMessage = new PingMessage();
        Message message = peer.getResponse(pingMessage);

        if (message == null || message.getType() != MessageType.PING) {
            LOGGER.debug("[{}] Didn't receive reply from {} for PING ID {}",
                    peer.getPeerConnectionId(), peer, pingMessage.getId());
            peer.disconnect("no ping received");
            return;
        }

        peer.setLastPing(NTP.getTime() - now);
    }
}
