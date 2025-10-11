package org.qortal.controller.arbitrary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.controller.Controller;
import org.qortal.data.arbitrary.ArbitraryFileListResponseInfo;
//import org.qortal.data.arbitrary.ArbitraryResourceData;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.network.NetworkData;
import org.qortal.network.Peer;
import org.qortal.network.PeerSendManagement;
import org.qortal.network.PeerSendManager;
import org.qortal.network.message.GetArbitraryDataFileMessage;
import org.qortal.network.message.GetArbitraryDataFilesMessage;
import org.qortal.network.message.MessageException;
import org.qortal.network.message.MessageType;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.utils.ArbitraryTransactionUtils;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;
import org.qortal.utils.NamedThreadFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static java.lang.Thread.NORM_PRIORITY;

public class ArbitraryDataFileRequestThread {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataFileRequestThread.class);

    private static final Integer FETCHER_LIMIT_PER_PEER = Settings.getInstance().getMaxThreadsForMessageType(MessageType.GET_ARBITRARY_DATA_FILE);
    //ToDo: replace static int in next line with FETCHER_LIMIT CONST
    private final ExecutorService masterFileFetcherPool = Executors.newFixedThreadPool(3);
    private static final String FETCHER_THREAD_PREFIX = "Arbitrary Data Fetcher ";
    private ConcurrentHashMap<String, ExecutorService> executorByPeer = new ConcurrentHashMap<>();

    private ArbitraryDataFileRequestThread() {
        cleanupExecutorByPeerScheduler.scheduleAtFixedRate(this::cleanupExecutorsByPeer, 1, 1, TimeUnit.MINUTES);
    }

    private static ArbitraryDataFileRequestThread instance = null;

    public static ArbitraryDataFileRequestThread getInstance() {

        if( instance == null ) {
            instance = new ArbitraryDataFileRequestThread();
        }

        return instance;
    }

    private final ScheduledExecutorService cleanupExecutorByPeerScheduler = Executors.newScheduledThreadPool(1);

    private void cleanupExecutorsByPeer() {

        try {
            this.executorByPeer.forEach((key, value) -> {
                if (value instanceof ThreadPoolExecutor) {
                    ThreadPoolExecutor threadPoolExecutor = (ThreadPoolExecutor) value;
                    if (threadPoolExecutor.getActiveCount() == 0) {
                        threadPoolExecutor.shutdown();
                        if (this.executorByPeer.computeIfPresent(key, (k, v) -> null) == null) {
                            LOGGER.trace("removed executor: peer = " + key);
                        }
                    }
                } else {
                    LOGGER.warn("casting issue in cleanup");
                }
            });
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        }
    }

    public void processFileHashes(Long now, List<ArbitraryFileListResponseInfo> responseInfos, ArbitraryDataFileManager arbitraryDataFileManager) throws InterruptedException, MessageException {
		if (Controller.isStopping()) {
            return;
        }

        Map<String, byte[]> signatureBySignature58 = new HashMap<>(responseInfos.size());
        Map<String, List<ArbitraryFileListResponseInfo>> responseInfoBySignature58 = new HashMap<>();

        // Used for directly connected peers, not remote/relay based
        List<Peer> completeConnectedPeers = NetworkData.getInstance().getImmutableHandshakedPeers();

        // Remove any pending direct connects that have exceeded the timeout, increment others
        for (Map.Entry<String, Integer> peerTimeLapse : arbitraryDataFileManager.getPeerTimeOuts().entrySet()) {

            String peer = peerTimeLapse.getKey();
            Integer elapsedSeconds = peerTimeLapse.getValue();

            if (elapsedSeconds > 8 ) {  // stale, drop list and counter
                arbitraryDataFileManager.removePeerTimeOut(peer);
                LOGGER.info("Removing Peer: {} for time out greater than 8", peer.toString());
            }
        }

        // Add +1 to all peers pending connection
        arbitraryDataFileManager.incrementTimeOuts();

        // If we have some held hashes waiting for a peer connection
        LOGGER.info("Do we have pending chunks?: {}", arbitraryDataFileManager.pendingPeersAndChunks());
        if (arbitraryDataFileManager.pendingPeersAndChunks()) {
            for (Map.Entry<String, List<ArbitraryFileListResponseInfo>> peerWithInfos: arbitraryDataFileManager.getPendingPeerAndChunks().entrySet()) {

                String peerString = peerWithInfos.getKey();
                // We need to check by IP/Host here, and then put in the proper peer object

                LOGGER.info("We are going to look for: {}",peerString);
                Peer connectedPeer = NetworkData.getInstance().getPeerByHostName(peerString);
                if (connectedPeer == null)
                    LOGGER.info("WARN: connectedPeer is null, not connected");
                //if (connectedPeer != null && completeConnectedPeers.contains(connectedPeer)) {            // If the peer is now connected
                if (connectedPeer != null ) {            // If the peer is now connected
                    LOGGER.info("We are adding responseInfos from the queue");
                    responseInfos.addAll(peerWithInfos.getValue());     // add all responseInfos for this peer to the list
                    arbitraryDataFileManager.removePeerTimeOut(peerString);
                    arbitraryDataFileManager.setIsConnecting(peerString, false);
                }
            }
        }

        if (responseInfos.isEmpty())
            return;

        for( ArbitraryFileListResponseInfo responseInfo : responseInfos) {

            if( responseInfo == null ) continue;

            if (Controller.isStopping()) {
                return;
            }

            Peer peer = responseInfo.getPeer();
            // Check if the peer we want a chunk from is connected?

            // If the Peer is directly connected the object will match
            boolean foundConnectedPeer = completeConnectedPeers.contains(peer);

            if (!foundConnectedPeer) {
                Peer findPeer;

                String peerHost = peer.getHostName();
                // Connected later by relay request we should find it
                findPeer = NetworkData.getInstance().getPeerByHostName(peerHost);
                if(findPeer != null) {
                    peer = findPeer;
                    foundConnectedPeer = true;
                }
            }

            if(!foundConnectedPeer) { // Peer is not connected
                // put the response info into a queue tied to this peers connection completed
                arbitraryDataFileManager.addResponseToPending(peer, responseInfo);

                if (!arbitraryDataFileManager.getIsConnectingPeer(peer.toString())) {  // If not tracking the peer in adfm
                        LOGGER.info("Forcing Connect for QDN to: {}", peer);
                        arbitraryDataFileManager.setIsConnecting(peer.toString(), true);
                        NetworkData.getInstance().forceConnectPeer(peer);
                        Thread.sleep(50);
                    }
                continue;
            }

            //LOGGER.info("Peer Object is {}", peer);
            if (now - responseInfo.getTimestamp() >= ArbitraryDataManager.ARBITRARY_RELAY_TIMEOUT || responseInfo.getSignature58() == null || peer == null) {
                LOGGER.trace("TIMED OUT in ArbitraryDataFileRequestThread");
                continue;
            }

            // Skip if already requesting, but don't remove, as we might want to retry later
            if (arbitraryDataFileManager.arbitraryDataFileRequests.containsKey(responseInfo.getHash58())) {
                // Already requesting - leave this attempt for later
                // @ToDo : don't think this next statement is true, this is why we are queueing up multiple requests for the same thing
                //arbitraryDataFileManager.addResponse(responseInfo); // don't remove -> adding back, because it was removed already above
                continue;
            }

            byte[] hash = Base58.decode(responseInfo.getHash58());
            byte[] signature = Base58.decode(responseInfo.getSignature58());

            // check for null
            if (signature == null || hash == null || peer == null) {
                continue;
            }

            // We want to process this file, store and map data to process later
            signatureBySignature58.put(responseInfo.getSignature58(), signature);
            responseInfoBySignature58 // Can contain different peers
                    .computeIfAbsent(responseInfo.getSignature58(), signature58 -> new ArrayList<>())
                    .add(responseInfo);
        }

        // if there are no signatures, then there is nothing to process and nothing query the database
        if( signatureBySignature58.isEmpty() ) return;

        List<ArbitraryTransactionData> arbitraryTransactionDataList = new ArrayList<>();

        // Fetch the transaction data
        try (final Repository repository = RepositoryManager.getRepository()) {
            arbitraryTransactionDataList.addAll(
                ArbitraryTransactionUtils.fetchTransactionDataList(repository, new ArrayList<>(signatureBySignature58.values())));
        } catch (DataException e) {
            LOGGER.warn("Unable to fetch transaction data from DB: {}", e.getMessage());
        }

        if( !arbitraryTransactionDataList.isEmpty() ) {
//            long start = System.currentTimeMillis();
            Peer peer = null;
            LOGGER.info("List of files is not empty, starting to build message of: GetArbitraryDataFilesMessage ");

            for(ArbitraryTransactionData data : arbitraryTransactionDataList ) {  // a file
                String signature58 = Base58.encode(data.getSignature());

                // Check if we have a connection to this peer
                peer = responseInfoBySignature58.get(signature58).get(0).getPeer();

                // @ToDo: can this if ever be true, see checks above,
                /*
                if(!completeConnectedPeers.contains(peer)) {
                    NetworkData.getInstance().addPeer(peer);
                    NetworkData.getInstance().forceConnectPeer(peer);
                    LOGGER.info("Starting New QDN Connection request");
                    break;
                } */

                for( ArbitraryFileListResponseInfo responseInfo : responseInfoBySignature58.get(signature58)) {
                    peer = responseInfo.getPeer();
                    if (!completeConnectedPeers.contains(peer)) {
                        peer = NetworkData.getInstance().getPeerByHostName(peer.getHostName());
                    }
                    String fileHash = responseInfo.getHash58();

                    byte[] fileHashBytes = Base58.decode(fileHash);

                    GetArbitraryDataFileMessage message = new GetArbitraryDataFileMessage(data.getSignature(), fileHashBytes);
                    // this.replyQueues is null, caused crash
                    int msgId = peer.addToReplyQueue();
                    message.setId(msgId);

                    LOGGER.trace("Adding hash {} to PeerSendManager send to {}", fileHash, peer);
                    PeerSendManagement.getInstance().getOrCreateSendManager(peer).queueMessage(message);
                }

                // New Single Request queue tool
                //List<byte[]> fileHashes = new ArrayList<>();
                // @ToDo: Com back to this because of below
                // New Message Type has issues with the response side generating messageId,
                //GetArbitraryDataFilesMessage message = new GetArbitraryDataFilesMessage(sigBytes, fileHashes);
//                for( ArbitraryFileListResponseInfo responseInfo : responseInfoBySignature58.get(signature58)) {
//                    GetArbitraryDataFileMessage message = new GetArbitraryDataFileMessage(sigBytes, responseInfo.getHash58());
//                    int msgId = peer.addToReplyQueue();
//                    message.setId(msgId);
//                    if (peer != null) {
//                        PeerSendManagement.getInstance().getOrCreateSendManager(peer).queueMessage(message);
//                    }
//                }
//                LOGGER.info("Completed Queueing message for send files to remote host");
//                List<Peer> connectedPeers = NetworkData.getInstance().getImmutableConnectedPeers();
                // Check if we have a connection to this peer
//                if(!connectedPeers.contains(peer)) {
//                    // Send to new Thread to Connect
//                    NetworkData.getInstance().addPeer(peer);
//                    Peer finalPeer = peer;
//                    LOGGER.info("Starting New QDN Connection Thread");
//                    Runnable requestConnect = () -> {
//                        try {
//                            NetworkData.getInstance().connectPeerThenFetch(finalPeer, responseInfos);
//                        } catch (InterruptedException e) {
//                            throw new RuntimeException(e);
//                        }
//                    };
//                    new Thread (requestConnect).start();
//                } else {

                    // Legacy Fetch Loop - 1 Thread per chunk
                /*
                    for (ArbitraryFileListResponseInfo responseInfo : responseInfoBySignature58.get(signature58)) {
                        LOGGER.info("Starting Thread to get a file: {}", responseInfo.getHash58());

                        Runnable fetcher = () -> {
                            try {
                                arbitraryDataFileFetcher(arbitraryDataFileManager, responseInfo, data);
                            } finally {
                                LOGGER.info("File fetcher thread for hash {} is exiting.", responseInfo.getHash58());
                            }
                        };

                        this.executorByPeer
                                .computeIfAbsent(
                                        responseInfo.getPeer().toString(),
                                        peerThreadPool -> Executors.newFixedThreadPool(
                                                FETCHER_LIMIT_PER_PEER,
                                                new NamedThreadFactory(FETCHER_THREAD_PREFIX + responseInfo.getPeer().toString(), NORM_PRIORITY)
                                        )
                                )
                                .execute(fetcher);
                    }*/
                    // End Legacy Fetch Loop
                    // The new, simplified loop
                    for (ArbitraryFileListResponseInfo responseInfo : responseInfoBySignature58.get(signature58)) {
                        // This part is the same
                        LOGGER.info("Starting Thread to get a file: {}", responseInfo.getHash58());

                        Runnable fetcher = () -> {
                            try {
                                arbitraryDataFileFetcher(arbitraryDataFileManager, responseInfo, data);
                            } finally {
                                LOGGER.info("File fetcher thread for hash {} is exiting.", responseInfo.getHash58());
                            }
                        };
                        this.masterFileFetcherPool.execute(fetcher);
                    }
                //}
            }
//            long timeLapse = System.currentTimeMillis() - start;
        }
    }

    public void shutdownFileFetcherPool() {
        masterFileFetcherPool.shutdown(); // Stops accepting new tasks
        try {
            // Wait a reasonable amount of time (e.g., 15 seconds) for existing tasks to complete
            if (!masterFileFetcherPool.awaitTermination(15, TimeUnit.SECONDS)) {
                masterFileFetcherPool.shutdownNow(); // Cancel currently executing tasks
            }
        } catch (InterruptedException e) {
            masterFileFetcherPool.shutdownNow(); // Cancel if interrupted while waiting
            Thread.currentThread().interrupt();
        }
    }

    private void arbitraryDataFileFetcher(ArbitraryDataFileManager arbitraryDataFileManager, ArbitraryFileListResponseInfo responseInfo, ArbitraryTransactionData arbitraryTransactionData)  {
        try {
            Long now = NTP.getTime();

            // @ToDo: Old Timing Method
//            if (now - responseInfo.getTimestamp() >= ArbitraryDataManager.ARBITRARY_RELAY_TIMEOUT ) {
//
//                Peer peer = responseInfo.getPeer();
//                String hash58 = responseInfo.getHash58();
//                String signature58 = responseInfo.getSignature58();
//                LOGGER.debug("Peer {} version {} didn't fetch data file {} for signature {} due to relay timeout.", peer, peer.getPeersVersionString(), hash58, signature58);
//                return;
//            }

            // Before we just passed responseInfo.getPeer() which might not be the connected object
            // Instead get it from the NetworkData Object
            arbitraryDataFileManager.fetchArbitraryDataFiles(
                //responseInfo.getPeer(),
                NetworkData.getInstance().getPeerByHostName(responseInfo.getPeer().getHostName()),
                arbitraryTransactionData.getSignature(),
                arbitraryTransactionData,
                Arrays.asList(Base58.decode(responseInfo.getHash58()))
            );
        } catch (DataException e) {
            LOGGER.warn("Unable to process file hashes: {}", e.getMessage());
        }
    }
}