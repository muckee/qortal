package org.qortal.controller.arbitrary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.controller.Controller;
import org.qortal.data.arbitrary.ArbitraryFileListResponseInfo;
//import org.qortal.data.arbitrary.ArbitraryResourceData;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.network.*;
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

import javax.xml.crypto.Data;
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
import java.util.stream.Collectors;

import static java.lang.Thread.NORM_PRIORITY;

public class ArbitraryDataFileRequestThread {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataFileRequestThread.class);

    private static final Integer FETCHER_LIMIT_PER_PEER = Settings.getInstance().getMaxThreadsForMessageType(MessageType.GET_ARBITRARY_DATA_FILE);
    //ToDo: replace static int in next line with FETCHER_LIMIT CONST
    // The defined value of 20 is based on a 100mb connection processing 20 file chunks in a second
    private final ExecutorService masterFileFetcherPool = Executors.newFixedThreadPool(1000);
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
            shutdownFileFetcherPool();
            return;
        }

        Map<String, byte[]> signatureBySignature58 = new HashMap<>(responseInfos.size());
        Map<String, List<ArbitraryFileListResponseInfo>> responseInfoBySignature58 = new HashMap<>();

        PeerList completeConnectedPeers = NetworkData.getInstance().getImmutableHandshakedPeers();

        // Remove any pending direct connects that have exceeded the timeout, increment others
        for (Map.Entry<String, Integer> peerTimeLapse : arbitraryDataFileManager.getPeerTimeOuts().entrySet()) {
            // ... (Peer Timeout Logic) ...
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
                PeerAddress peerAddress = new PeerAddress(peerString);
                // We need to check by IP/Host here, and then put in the proper peer object

                LOGGER.info("We are going to look for: {}",peerString);
                Peer connectedPeer = NetworkData.getInstance().getPeerByPeerAddress( peerAddress );

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

            Boolean isDirectlyConnectable = responseInfo.isDirectConnectable();

            Peer peer = responseInfo.getPeer();
            Peer connectedPeer = completeConnectedPeers.get(peer.getPeerData());

            // Check if the peer we want a chunk from is directly connected?
            if (connectedPeer != null) {
                // If found, update the 'peer' object to the actual connected one (important for messaging)
                peer = connectedPeer;
            } else {
                LOGGER.debug("We did not find a directly connected peer for : {}", peer);
            }

            // INSERT LOGIC FORK HERE....
            if (isDirectlyConnectable) {
                if (connectedPeer == null) { // Peer is not connected
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
                if (now - responseInfo.getTimestamp() >= ArbitraryDataManager.ARBITRARY_RELAY_TIMEOUT || responseInfo.getSignature58() == null || peer == null) {
                    LOGGER.trace("TIMED OUT in ArbitraryDataFileRequestThread");
                    continue;
                }
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
                LOGGER.debug("Signature was null or hash was null or peer was null");
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
            long start = System.currentTimeMillis();

            LOGGER.info("List of files is not empty, starting to build message of: GetArbitraryDataFilesMessage ");

            for(ArbitraryTransactionData data : arbitraryTransactionDataList ) {  // a file
                String signature58 = Base58.encode(data.getSignature());
                for( ArbitraryFileListResponseInfo responseInfo : responseInfoBySignature58.get(signature58)) {
                    Peer peer = responseInfo.getPeer();
                    LOGGER.debug("ResponseInfo peer: {}", peer);

                    // Use the PeerList snapshot for a host/IP-only lookup to ensure we have
                    // the *current* connected Peer object for messaging.
                    Peer connectedPeer = completeConnectedPeers.get(peer.getPeerData());

                    if (connectedPeer == null) {
                        // Peer is no longer connected/handshaked in the snapshot. Skip processing this request.
                        LOGGER.debug("Peer {} for hash {} is no longer handshaked/connected. Skipping.", peer, responseInfo.getHash58());
                        continue;
                    }

                    // Update the 'peer' variable to the connected one
                    peer = connectedPeer;
                    LOGGER.debug("We set peer to: {}", peer);

                    String fileHash = responseInfo.getHash58();

                    byte[] fileHashBytes = Base58.decode(fileHash);

                    GetArbitraryDataFileMessage message = new GetArbitraryDataFileMessage(data.getSignature(), fileHashBytes);
                    // this.replyQueues is null, caused crash
                    int msgId = peer.addToReplyQueue();
                    message.setId(msgId);

                    LOGGER.debug("Adding hash {} to PeerSendManager send to {}", fileHash, peer);
                    PeerSendManagement.getInstance().getOrCreateSendManager(peer).queueMessage(message);
                }

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

                    if(responseInfo.isDirectConnectable()) {
                        Runnable fetcher = () -> {
                            try {
                                arbitraryDataFileConnectedFetcher(arbitraryDataFileManager, responseInfo, data);
                            } catch (DataException e) {
                                LOGGER.warn("Unable to process file hashes: {}", e.getMessage());
                            } finally {
                                LOGGER.info("File fetcher thread for hash {} is exiting.", responseInfo.getHash58());
                            }
                        };
                        this.masterFileFetcherPool.execute(fetcher);
                    } else {
                        Runnable fetcher = () -> {
                            try {
                                arbitraryDataFileRelayFetcher(arbitraryDataFileManager, responseInfo, data);
                            } catch (DataException e) {
                                LOGGER.warn("Data Exception while processing (RelayFetcher) file hashes: {}", e.getMessage());
                                throw new RuntimeException(e);
                            }
                        };
                        this.executorByPeer
                            .computeIfAbsent(
                                    responseInfo.getPeer().toString(),
                                    peer -> Executors.newFixedThreadPool(
                                            FETCHER_LIMIT_PER_PEER,
                                            new NamedThreadFactory(FETCHER_THREAD_PREFIX + responseInfo.getPeer().toString(), NORM_PRIORITY)
                                    )
                            )
                            .execute(fetcher);
                    }
                }
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

    private void arbitraryDataFileConnectedFetcher(ArbitraryDataFileManager arbitraryDataFileManager, ArbitraryFileListResponseInfo responseInfo, ArbitraryTransactionData arbitraryTransactionData) throws DataException {
            Long now = NTP.getTime();

            // @ToDo: Old Timing Method - Consider a way to
//            if (now - responseInfo.getTimestamp() >= ArbitraryDataManager.ARBITRARY_RELAY_TIMEOUT ) {
//
//                Peer peer = responseInfo.getPeer();
//                String hash58 = responseInfo.getHash58();
//                String signature58 = responseInfo.getSignature58();
//                LOGGER.debug("Peer {} version {} didn't fetch data file {} for signature {} due to relay timeout.", peer, peer.getPeersVersionString(), hash58, signature58);
//                return;
//            }

            // LOGGER.info("We are going to try and find : {}", responseInfo.getPeer().getHostName());
            LOGGER.info("We are going to try for peer:: {}", responseInfo.getPeer());
            // LOGGER.info("Returned result will be: {}", responseInfo.getPeer().getHostName());
            arbitraryDataFileManager.fetchArbitraryDataFiles(
                    NetworkData.getInstance().getPeerByPeerData(responseInfo.getPeer().getPeerData()),
                    arbitraryTransactionData.getSignature(),
                    arbitraryTransactionData,
                    Arrays.asList(Base58.decode(responseInfo.getHash58())), responseInfo
            );
    }

    private void arbitraryDataFileRelayFetcher(ArbitraryDataFileManager arbitraryDataFileManager, ArbitraryFileListResponseInfo responseInfo, ArbitraryTransactionData arbitraryTransactionData)  throws DataException{
        Long now = NTP.getTime();
        if (now != null && now - responseInfo.getTimestamp() >= ArbitraryDataManager.ARBITRARY_RELAY_TIMEOUT ) {
            Peer peer = responseInfo.getPeer();
            String hash58 = responseInfo.getHash58();
            String signature58 = responseInfo.getSignature58();
            LOGGER.debug("Peer {} version {} didn't fetch data file {} for signature {} due to relay timeout.", peer, peer.getPeersVersionString(), hash58, signature58);
            return;
        }

        arbitraryDataFileManager.fetchArbitraryDataFiles(
                responseInfo.getPeer(),
                arbitraryTransactionData.getSignature(),
                arbitraryTransactionData,
                Arrays.asList(Base58.decode(responseInfo.getHash58())),
                responseInfo
        );
    }
}