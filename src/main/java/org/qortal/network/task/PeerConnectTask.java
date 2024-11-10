package org.qortal.network.task;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.network.Network;
import org.qortal.network.Peer;
import org.qortal.utils.ExecuteProduceConsume.Task;

public class PeerConnectTask implements Task {
    private static final Logger LOGGER = LogManager.getLogger(PeerConnectTask.class);

    private final Peer peer;
    private final String name;

    public PeerConnectTask(Peer peer) {
        this.peer = peer;
        this.name = "PeerConnectTask::" + peer;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void perform() throws InterruptedException {
        Network.getInstance().connectPeer(peer);
    }
}
