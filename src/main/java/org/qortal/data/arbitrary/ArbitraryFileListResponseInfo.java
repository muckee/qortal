package org.qortal.data.arbitrary;

import org.qortal.network.Peer;

public class ArbitraryFileListResponseInfo extends  ArbitraryRelayInfo {

    public ArbitraryFileListResponseInfo(String hash58, String signature58, Peer peer, Long timestamp, Long requestTime, Integer requestHops, Boolean isDirectConnectable) {
        super(hash58, signature58, peer, timestamp, requestTime, requestHops, isDirectConnectable);
        //    Chunk , File       , peer
    }

}
