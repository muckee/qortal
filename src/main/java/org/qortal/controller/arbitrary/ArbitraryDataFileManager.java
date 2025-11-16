package org.qortal.controller.arbitrary;

import com.google.common.net.InetAddresses;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.arbitrary.ArbitraryDataFile;
import org.qortal.controller.Controller;
import org.qortal.data.arbitrary.ArbitraryDirectConnectionInfo;
import org.qortal.data.arbitrary.ArbitraryFileListResponseInfo;
import org.qortal.data.arbitrary.ArbitraryRelayInfo;
import org.qortal.data.network.PeerData;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.network.NetworkData;
import org.qortal.network.Peer;
import org.qortal.network.PeerSendManagement;
import org.qortal.network.PeerSendManager;
import org.qortal.network.message.*;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.utils.ArbitraryTransactionUtils;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;
import org.qortal.utils.Triple;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.qortal.network.PeerSendManager.HIGH_PRIORITY;

public class ArbitraryDataFileManager extends Thread {

    //public static final int SEND_TIMEOUT_MS = 500; We Now use the new TimedMessage Features
    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataFileManager.class);

    private static ArbitraryDataFileManager instance;
    private volatile boolean isStopping = false;

    // Map to keep track of our in progress (outgoing) arbitrary data file requests
    public Map<String, Long> arbitraryDataFileRequests = Collections.synchronizedMap(new HashMap<>());

    // Map to keep track of hashes that we might need to relay
    public final List<ArbitraryRelayInfo> arbitraryRelayMap = Collections.synchronizedList(new ArrayList<>());

    // List to keep track of any arbitrary data file hash responses
    private final List<ArbitraryFileListResponseInfo> arbitraryDataFileHashResponses = Collections.synchronizedList(new ArrayList<>());

    // List to keep track of peers potentially available for direct connections, based on recent requests
    private final List<ArbitraryDirectConnectionInfo> directConnectionInfo = Collections.synchronizedList(new ArrayList<>());

    /**
     * Map to keep track of peers requesting QDN data that we hold.
     * Key = peer address string, value = time of last request.
     * This allows for additional "burst" connections beyond existing limits.
     */
    private Map<String, Long> recentDataRequests = Collections.synchronizedMap(new HashMap<>());

    // This needs to be a private class
    //private Triple<Peer, Integer, List<ArbitraryFileListResponseInfo>> pendingPeerConnectionsWHashes = new
    private final PendingPeersWithHashes pendingPeersWithHashes = new PendingPeersWithHashes();

    public static int MAX_FILE_HASH_RESPONSES = 1000;

    private final Map<String, PeerSendManager> peerSendManagers = new ConcurrentHashMap<>();

    private PeerSendManager getOrCreateSendManager(Peer peer) {
        try {
            return peerSendManagers.computeIfAbsent(peer.toString(), key -> new PeerSendManager(peer));
        } catch (Exception e) {
            LOGGER.info("FAILED - Could not map to a peer or create a peer");
            return null;
        }
    }

    private ArbitraryDataFileManager() {
        this.arbitraryDataFileHashResponseScheduler.scheduleAtFixedRate(this::processResponses, 60, 1, TimeUnit.SECONDS);
        this.arbitraryDataFileHashResponseScheduler.scheduleAtFixedRate(this::handleFileListRequestProcess, 60, 1, TimeUnit.SECONDS);

        ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor();

        cleaner.scheduleAtFixedRate(() -> {
            long idleCutoff = TimeUnit.MINUTES.toMillis(2);
            Iterator<Map.Entry<String, PeerSendManager>> iterator = peerSendManagers.entrySet().iterator();

            while (iterator.hasNext()) {
                Map.Entry<String, PeerSendManager> entry = iterator.next();
                String peerHash = entry.getKey();
                PeerSendManager manager = entry.getValue();

                if (manager.isIdle(idleCutoff)) {
                    iterator.remove(); // SAFE removal during iteration
                    manager.shutdown();
                    LOGGER.debug("Cleaned up PeerSendManager for peer {}", peerHash);
                }
            }
        }, 0, 5, TimeUnit.MINUTES);
    }

    public static ArbitraryDataFileManager getInstance() {
        if (instance == null)
            instance = new ArbitraryDataFileManager();

        return instance;
    }

    @Override
    public void run() {
        Thread.currentThread().setName("Arbitrary Data File Manager");

        try {
            while (!isStopping) {
                // Nothing to do yet
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            // Fall-through to exit thread...
        }
    }

    public void shutdown() {
        isStopping = true;
        this.interrupt();
    }

    public void cleanupRequestCache(Long now) {
        if (now == null) {
            return;
        }
        final long requestMinimumTimestamp = now - ArbitraryDataManager.getInstance().ARBITRARY_REQUEST_TIMEOUT;
        arbitraryDataFileRequests.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue() < requestMinimumTimestamp);

        final long relayMinimumTimestamp = now - ArbitraryDataManager.getInstance().ARBITRARY_RELAY_TIMEOUT;
        arbitraryRelayMap.removeIf(entry -> entry == null || entry.getTimestamp() == null || entry.getTimestamp() < relayMinimumTimestamp);

        final long directConnectionInfoMinimumTimestamp = now - ArbitraryDataManager.getInstance().ARBITRARY_DIRECT_CONNECTION_INFO_TIMEOUT;
        directConnectionInfo.removeIf(entry -> entry.getTimestamp() < directConnectionInfoMinimumTimestamp);

        final long recentDataRequestMinimumTimestamp = now - ArbitraryDataManager.getInstance().ARBITRARY_RECENT_DATA_REQUESTS_TIMEOUT;
        recentDataRequests.entrySet().removeIf(entry -> entry.getValue() < recentDataRequestMinimumTimestamp);
    }

    // Fetch data files by hash
    public boolean fetchArbitraryDataFiles(Peer peer,
                                           byte[] signature,
                                           ArbitraryTransactionData arbitraryTransactionData,
                                           List<byte[]> hashes) throws DataException {
        if (peer == null) {
            LOGGER.info("Received a NULL peer, dropping back");
            return false;
        }
        // Load data file(s)
        ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromTransactionData(arbitraryTransactionData);
        boolean receivedAtLeastOneFile = false;

        // Now fetch actual data from this peer
        for (byte[] hash : hashes) {
            if (isStopping) {
                return false;
            }
            String hash58 = Base58.encode(hash);
            if (!arbitraryDataFile.chunkExists(hash)) {
                // Only request the file if we aren't already requesting it from someone else
                if (!arbitraryDataFileRequests.containsKey(Base58.encode(hash))) {
                    LOGGER.info("Requesting data file {} from peer {}", hash58, peer);
                    Long startTime = NTP.getTime();
                    // peer == null, why? or how
                    peer.QDNUse();
                    ArbitraryDataFile receivedArbitraryDataFile = fetchArbitraryDataFile(peer, arbitraryTransactionData, signature, hash);
                    Long endTime = NTP.getTime();
                    if (receivedArbitraryDataFile != null) {
                        LOGGER.trace("Received data file {} from peer {}. Time taken: {} ms", receivedArbitraryDataFile.getHash58(), peer, (endTime - startTime));
                        receivedAtLeastOneFile = true;
                    } else {
                        LOGGER.trace("Peer {} didn't respond with data file {} for signature {}. Time taken: {} ms", peer, Base58.encode(hash), Base58.encode(signature), (endTime - startTime));
                        // Stop asking for files from this peer
                        break;
                    }
                } else {
                    LOGGER.info("Already requesting data file {} for signature {} from peer {}", arbitraryDataFile, Base58.encode(signature), peer);
                }
            }
        }

        if (receivedAtLeastOneFile) {
            // Invalidate the hosted transactions cache as we are now hosting something new
            ArbitraryDataStorageManager.getInstance().invalidateHostedTransactionsCache();

            // Check if we have all the files we need for this transaction
            if (arbitraryDataFile.allFilesExist()) {

                // We have all the chunks for this transaction, so we should invalidate the transaction's name's
                // data cache so that it is rebuilt the next time we serve it
                ArbitraryDataManager.getInstance().invalidateCache(arbitraryTransactionData);
            }
        }

        return receivedAtLeastOneFile;
    }

    // Lock to synchronize access to the list
    private final Object arbitraryDataFileHashResponseLock = new Object();

    // Scheduled executor service to process messages every second
    private final ScheduledExecutorService arbitraryDataFileHashResponseScheduler = Executors.newScheduledThreadPool(1);

    public void addResponse(ArbitraryFileListResponseInfo responseInfo) {

        synchronized (arbitraryDataFileHashResponseLock) {
            this.arbitraryDataFileHashResponses.add(responseInfo);
        }
    }

    private void processResponses() {
        try {
            List<ArbitraryFileListResponseInfo> responsesToProcess;
            synchronized (arbitraryDataFileHashResponseLock) {
                if (arbitraryDataFileHashResponses.isEmpty() && !pendingPeersAndChunks()) {
                    return; // nothing to process
                }
                responsesToProcess = new ArrayList<>(arbitraryDataFileHashResponses);
                arbitraryDataFileHashResponses.clear();
            }

            long now = NTP.getTime();
            ArbitraryDataFileRequestThread.getInstance().processFileHashes(now, responsesToProcess, this);
        } catch (InterruptedException e) {
            LOGGER.error("InterruptedException: {}", e.getMessage());
        } catch (MessageException e) {
            LOGGER.error("MessageException: {}", e.getMessage());
        }
        catch (Exception e) {
            LOGGER.error("Error while processing responses: {}", e.getMessage(), e);
        }
    }

    private int requestArbitraryDataFile(Peer peer, ArbitraryTransactionData arbitraryTransactionData, byte[] signature, byte[] hash) throws DataException {

        try {
            ArbitraryDataFile existingFile = ArbitraryDataFile.fromHash(hash, signature);
            boolean fileAlreadyExists = existingFile.exists();
            if (fileAlreadyExists) {
                return 1;
            }
            String hash58 = Base58.encode(hash);
            arbitraryDataFileRequests.put(hash58, NTP.getTime());
            Message getArbitraryDataFileMessage = new GetArbitraryDataFileMessage(signature, hash);
            boolean wilco = peer.sendMessageWhenReady(getArbitraryDataFileMessage);
            if (wilco) {
                return 0;
            } else {
                LOGGER.warn("Failed to queue arbitrary file request message");
                return -1;
            }
        } catch (DataException e) {
            LOGGER.error(e.getMessage(), e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return 0;
    }

    private ArbitraryDataFile fetchArbitraryDataFile(Peer peer, ArbitraryTransactionData arbitraryTransactionData, byte[] signature, byte[] hash) throws DataException {
        ArbitraryDataFile arbitraryDataFile;

        try {
            ArbitraryDataFile existingFile = ArbitraryDataFile.fromHash(hash, signature);
            boolean fileAlreadyExists = existingFile.exists();
            String hash58 = Base58.encode(hash);

            // Fetch the file if it doesn't exist locally
            if (!fileAlreadyExists) {
                LOGGER.info(String.format("Fetching data file %.8s from peer %s", hash58, peer));
                arbitraryDataFileRequests.put(hash58, NTP.getTime());
                Message getArbitraryDataFileMessage = new GetArbitraryDataFileMessage(signature, hash);

                // @ToDo - This is where we are
                Message response = peer.getResponseToDataFile(getArbitraryDataFileMessage);

                arbitraryDataFileRequests.remove(hash58);
                LOGGER.info(String.format("Removed hash %.8s from arbitraryDataFileRequests", hash58));

                if (response == null) {
                    LOGGER.debug("Received null response from peer {}", peer);
                    return null;
                }
                if (response.getType() != MessageType.ARBITRARY_DATA_FILE) {
                    LOGGER.debug("Received response with invalid type: {} from peer {}", response.getType(), peer);
                    return null;
                }

                ArbitraryDataFileMessage peersArbitraryDataFileMessage = (ArbitraryDataFileMessage) response;
                arbitraryDataFile = peersArbitraryDataFileMessage.getArbitraryDataFile();
            } else {
                LOGGER.debug(String.format("File hash %s already exists, so skipping the request", hash58));
                arbitraryDataFile = existingFile;
            }

            if (arbitraryDataFile != null) {

                arbitraryDataFile.save();

                // If this is a metadata file then we need to update the cache
                if (arbitraryTransactionData != null && arbitraryTransactionData.getMetadataHash() != null) {
                    if (Arrays.equals(arbitraryTransactionData.getMetadataHash(), hash)) {
                        ArbitraryDataCacheManager.getInstance().addToUpdateQueue(arbitraryTransactionData);
                    }
                }

                // We may need to remove the file list request, if we have all the files for this transaction
                this.handleFileListRequests(signature);
            } else {
                LOGGER.info("arbitraryDataFile was NULL");
            }

        } catch (DataException e) {
            LOGGER.error(e.getMessage(), e);
            arbitraryDataFile = null;
        }

        return arbitraryDataFile;
    }

    public void receivedArbitraryDataFile(Peer peer, ArbitraryDataFile adf) {

        //ArbitraryDataFile existingFile = ArbitraryDataFile.fromHash(hash, signature);
        //boolean fileAlreadyExists = existingFile.exists();
        byte[] signature = adf.getSignature();

        String hash58 = adf.getHash58();
        byte[] hash = adf.getHash();

        arbitraryDataFileRequests.remove(hash58);
        LOGGER.info(String.format("Removed hash %.8s from arbitraryDataFileRequests", hash58));

        try {
            adf.save();
        } catch (DataException de) {
            LOGGER.error("FAILED to write hash chunk to disk!");
            return;
        }

        ArbitraryTransactionData arbitraryTransactionData = null;
        // Fetch the transaction data
        try (final Repository repository = RepositoryManager.getRepository()) {
            //arbitraryTransactionDataList.addAll(
            //        ArbitraryTransactionUtils.fetchTransactionDataList(repository, new ArrayList<>(signatureBySignature58.values())));
            arbitraryTransactionData = ArbitraryTransactionUtils.fetchTransactionData(repository, signature);
        } catch (DataException e) {
            LOGGER.warn("Unable to fetch transaction data from DB: {}", e.getMessage());
        }

        // If this is a metadata file then we need to update the cache
        if (arbitraryTransactionData != null && arbitraryTransactionData.getMetadataHash() != null) {
            if (Arrays.equals(arbitraryTransactionData.getMetadataHash(), hash)) {
                ArbitraryDataCacheManager.getInstance().addToUpdateQueue(arbitraryTransactionData);
            }
        }

        // We may need to remove the file list request, if we have all the files for this transaction
        this.handleFileListRequests(signature);

    }

    private void fetchFileForRelay(Peer peer, Peer requestingPeer, byte[] signature, byte[] hash, Message originalMessage) throws DataException {
        try {
            String hash58 = Base58.encode(hash);

            LOGGER.debug(String.format("Fetching data file %.8s from peer %s", hash58, peer));
            arbitraryDataFileRequests.put(hash58, NTP.getTime());
            Message getArbitraryDataFileMessage = new GetArbitraryDataFileMessage(signature, hash);

            Message response = null;
            try {
                response = peer.getResponseWithTimeout(getArbitraryDataFileMessage, (int) ArbitraryDataManager.ARBITRARY_REQUEST_TIMEOUT);
            } catch (InterruptedException e) {
                // Will return below due to null response
            }
            arbitraryDataFileRequests.remove(hash58);
            LOGGER.trace(String.format("Removed hash %.8s from arbitraryDataFileRequests", hash58));

            if (response == null) {
                LOGGER.debug("Received null response from peer {}", peer);
                return;
            }
            if (response.getType() != MessageType.ARBITRARY_DATA_FILE) {
                LOGGER.debug("Received response with invalid type: {} from peer {}", response.getType(), peer);
                return;
            }

            ArbitraryDataFileMessage peersArbitraryDataFileMessage = (ArbitraryDataFileMessage) response;
            ArbitraryDataFile arbitraryDataFile = peersArbitraryDataFileMessage.getArbitraryDataFile();

            if (arbitraryDataFile != null) {

                // We might want to forward the request to the peer that originally requested it
                this.handleArbitraryDataFileForwarding(requestingPeer, new ArbitraryDataFileMessage(signature, arbitraryDataFile), originalMessage);
            }
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        }
    }

    Map<String, byte[]> signatureBySignature58 = new HashMap<>();

    // Lock to synchronize access to the list
    private final Object handleFileListRequestsLock = new Object();

    // Scheduled executor service to process messages every second
    private final ScheduledExecutorService handleFileListRequestsScheduler = Executors.newScheduledThreadPool(1);

    private void handleFileListRequests(byte[] signature) {

        synchronized (handleFileListRequestsLock) {
            signatureBySignature58.put(Base58.encode(signature), signature);
        }
    }

    private void handleFileListRequestProcess() {

        Map<String, byte[]> signaturesToProcess;

        synchronized (handleFileListRequestsLock) {
            signaturesToProcess = new HashMap<>(signatureBySignature58);
            signatureBySignature58.clear();
        }

        if( signaturesToProcess.isEmpty() ) return;

        try (final Repository repository = RepositoryManager.getRepository()) {

            // Fetch the transaction data
            List<ArbitraryTransactionData> arbitraryTransactionDataList
                = ArbitraryTransactionUtils.fetchTransactionDataList(repository, new ArrayList<>(signaturesToProcess.values()));

            for( ArbitraryTransactionData arbitraryTransactionData : arbitraryTransactionDataList ) {
                boolean completeFileExists = ArbitraryTransactionUtils.completeFileExists(arbitraryTransactionData);

                if (completeFileExists) {
                    String signature58 = Base58.encode(arbitraryTransactionData.getSignature());
                    LOGGER.debug("All chunks or complete file exist for transaction {}", signature58);

                    ArbitraryDataFileListManager.getInstance().deleteFileListRequestsForSignature(signature58);
                }
            }

        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        }
    }

    public void handleArbitraryDataFileForwarding(Peer requestingPeer, Message message, Message originalMessage) {
        // Return if there is no originally requesting peer to forward to
        if (requestingPeer == null) {
            return;
        }

        // Return if we're not in relay mode or if this request doesn't need forwarding
        if (!Settings.getInstance().isRelayModeEnabled()) {
            return;
        }

        LOGGER.debug("Received arbitrary data file - forwarding is needed");

        try {
            // The ID needs to match that of the original request
            message.setId(originalMessage.getId());

            PeerSendManagement.getInstance().getOrCreateSendManager(requestingPeer).queueMessage(message); //, SEND_TIMEOUT_MS

        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        }
    }

    // Fetch data directly from peers
    private List<ArbitraryDirectConnectionInfo> getDirectConnectionInfoForSignature(byte[] signature) {
        synchronized (directConnectionInfo) {
            return directConnectionInfo.stream().filter(i -> Arrays.equals(i.getSignature(), signature)).collect(Collectors.toList());
        }
    }

    /**
     * Add an ArbitraryDirectConnectionInfo item, but only if one with this peer-signature combination
     * doesn't already exist.
     * @param connectionInfo - the direct connection info to add
     */
    public void addDirectConnectionInfoIfUnique(ArbitraryDirectConnectionInfo connectionInfo) {
        boolean peerAlreadyExists;
        synchronized (directConnectionInfo) {
            peerAlreadyExists = directConnectionInfo.stream()
                    .anyMatch(i -> Arrays.equals(i.getSignature(), connectionInfo.getSignature())
                            && Objects.equals(i.getPeerAddress(), connectionInfo.getPeerAddress()));
        }
        if (!peerAlreadyExists) {
            directConnectionInfo.add(connectionInfo);
        }
    }

    private void removeDirectConnectionInfo(ArbitraryDirectConnectionInfo connectionInfo) {
        this.directConnectionInfo.remove(connectionInfo);
    }

    public boolean fetchDataFilesFromPeersForSignature(byte[] signature) {
        String signature58 = Base58.encode(signature);

        boolean success = false;

        try {
            while (!success) {
                if (isStopping) {
                    return false;
                }
                Thread.sleep(500L);

                // Firstly fetch peers that claim to be hosting files for this signature
                List<ArbitraryDirectConnectionInfo> connectionInfoList = getDirectConnectionInfoForSignature(signature);
                if (connectionInfoList == null || connectionInfoList.isEmpty()) {
                    LOGGER.debug("No remaining direct connection peers found for signature {}", signature58);
                    return false;
                }

                LOGGER.debug("Attempting a direct peer connection for signature {}...", signature58);

                // Peers found, so pick one with the highest number of chunks
                Comparator<ArbitraryDirectConnectionInfo> highestChunkCountFirstComparator =
                        Comparator.comparingInt(ArbitraryDirectConnectionInfo::getHashCount).reversed();
                ArbitraryDirectConnectionInfo directConnectionInfo = connectionInfoList.stream()
                        .sorted(highestChunkCountFirstComparator).findFirst().orElse(null);

                if (directConnectionInfo == null) {
                    return false;
                }

                // Remove from the list so that a different peer is tried next time
                removeDirectConnectionInfo(directConnectionInfo);

                String peerAddressString = directConnectionInfo.getPeerAddress();

                // Parse the peer address to find the host and port
                String host = null;
                int port = -1;
                String[] parts = peerAddressString.split(":");
                if (parts.length > 1) {
                    host = parts[0];
                    port = Integer.parseInt(parts[1]);
                } else {
                    // Assume no port included
                    host = peerAddressString;
                    // Use default listen port
                    port = Settings.getInstance().getDefaultQDNListenPort();
                }

                String peerAddressStringWithPort = String.format("%s:%d", host, port);
                success = NetworkData.getInstance().requestDataFromPeer(peerAddressStringWithPort, signature);

                int defaultPort = Settings.getInstance().getDefaultQDNListenPort();

                // If unsuccessful, and using a non-standard port, try a second connection with the default listen port,
                // since almost all nodes use that. This is a workaround to account for any ephemeral ports that may
                // have made it into the dataset.
                if (!success) {
                    if (host != null && port > 0) {
                        if (port != defaultPort) {
                            String newPeerAddressString = String.format("%s:%d", host, defaultPort);
                            success = NetworkData.getInstance().requestDataFromPeer(newPeerAddressString, signature);
                        }
                    }
                }

                // If _still_ unsuccessful, try matching the peer's IP address with some known peers, and then connect
                // to each of those in turn until one succeeds.
                if (!success) {
                    if (host != null) {
                        final String finalHost = host;
                        List<PeerData> knownPeers = NetworkData.getInstance().getAllKnownPeers().stream()
                                .filter(knownPeerData -> knownPeerData.getAddress().getHost().equals(finalHost))
                                .collect(Collectors.toList());
                        // Loop through each match and attempt a connection
                        for (PeerData matchingPeer : knownPeers) {
                            String matchingPeerAddress = matchingPeer.getAddress().toString();
                            int matchingPeerPort = matchingPeer.getAddress().getPort();
                            // Make sure that it's not a port we've already tried
                            if (matchingPeerPort != port && matchingPeerPort != defaultPort) {
                                success = NetworkData.getInstance().requestDataFromPeer(matchingPeerAddress, signature);
                                if (success) {
                                    // Successfully connected, so stop making connections
                                    break;
                                }
                            }
                        }
                    }
                }

                if (success) {
                    // We were able to connect with a peer, so track the request
                    ArbitraryDataFileListManager.getInstance().addToSignatureRequests(signature58, false, true);
                }

            }
        } catch (InterruptedException e) {
            // Do nothing
        }

        return success;
    }

    // Relays
    private List<ArbitraryRelayInfo> getRelayInfoListForHash(String hash58) {
        synchronized (arbitraryRelayMap) {
            return arbitraryRelayMap.stream()
                    .filter(relayInfo -> Objects.equals(relayInfo.getHash58(), hash58))
                    .collect(Collectors.toList());
        }
    }

    private ArbitraryRelayInfo getOptimalRelayInfoEntryForHash(String hash58) {
        LOGGER.trace("Fetching relay info for hash: {}", hash58);
        List<ArbitraryRelayInfo> relayInfoList = this.getRelayInfoListForHash(hash58);
        if (relayInfoList != null && !relayInfoList.isEmpty()) {

            // Remove any with null requestHops
            relayInfoList.removeIf(r -> r.getRequestHops() == null);

            // If list is now empty, then just return one at random
            if (relayInfoList.isEmpty()) {
                return this.getRandomRelayInfoEntryForHash(hash58);
            }

            // Sort by number of hops (lowest first)
            relayInfoList.sort(Comparator.comparingInt(ArbitraryRelayInfo::getRequestHops));

            // FUTURE: secondary sort by requestTime?

            ArbitraryRelayInfo relayInfo = relayInfoList.get(0);

            LOGGER.debug("Returning optimal relay info for hash: {} (requestHops {})", hash58, relayInfo.getRequestHops());
            return relayInfo;
        }
        LOGGER.trace("No relay info exists for hash: {}", hash58);
        return null;
    }

    private ArbitraryRelayInfo getRandomRelayInfoEntryForHash(String hash58) {
        LOGGER.trace("Fetching random relay info for hash: {}", hash58);
        List<ArbitraryRelayInfo> relayInfoList = this.getRelayInfoListForHash(hash58);
        if (relayInfoList != null && !relayInfoList.isEmpty()) {

            // Pick random item
            int index = new SecureRandom().nextInt(relayInfoList.size());
            LOGGER.trace("Returning random relay info for hash: {} (index {})", hash58, index);
            return relayInfoList.get(index);
        }
        LOGGER.trace("No relay info exists for hash: {}", hash58);
        return null;
    }

    public void addToRelayMap(ArbitraryRelayInfo newEntry) {
        if (newEntry == null || !newEntry.isValid()) {
            return;
        }

        // Remove existing entry for this peer if it exists, to renew the timestamp
        this.removeFromRelayMap(newEntry);

        // Re-add
        arbitraryRelayMap.add(newEntry);
        LOGGER.debug("Added entry to relay map: {}", newEntry);
    }

    private void removeFromRelayMap(ArbitraryRelayInfo entry) {
        arbitraryRelayMap.removeIf(relayInfo -> relayInfo.equals(entry));
    }

    // Peers requesting QDN data from us
    /**
     * Adds a recent data request timestamp for the specified peer address.
     * <p>
     * The method performs validation on the input to ensure it is non-null,
     * that the current NTP time is available, and that the host portion of
     * the peer address is a valid IP address. If valid, the host portion
     * (without the port) is recorded with the current timestamp.
     * <p>
     * This is typically used to track which peers have recently requested data,
     * while normalizing peer addresses to avoid mismatches due to port differences.
     *
     * @param peerAddress the address of the peer in the format "host:port" (e.g., "192.168.0.2:1234")
     *
     */
    public void addRecentDataRequest(String peerAddress) {
        if (peerAddress == null) {
            return;
        }

        Long now = NTP.getTime();
        if (now == null) {
            return;
        }

        // Make sure to remove the port, since it isn't guaranteed to match next time
        String[] parts = peerAddress.split(":");
        if (parts.length == 0) {
            return;
        }
        String host = parts[0];
        if (!InetAddresses.isInetAddress(host)) {
            // Invalid host
            return;
        }

        this.recentDataRequests.put(host, now);
    }

    public boolean isPeerRequestingData(String peerAddressWithoutPort) {
        return this.recentDataRequests.containsKey(peerAddressWithoutPort);
    }

    public boolean hasPendingDataRequest() {
        return !this.recentDataRequests.isEmpty();
    }

    // Network handlers
    private void processDataFile(Peer peer, byte[] hash, byte[] sig, int msgId) {
        final String hash58 = Base58.encode(hash);
        try {
            ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(hash, sig);
            ArbitraryRelayInfo relayInfo = this.getOptimalRelayInfoEntryForHash(hash58);
            if (arbitraryDataFile.exists()) {
                LOGGER.trace("Hash {} exists, queueing send file to {}", hash58, peer);

                // We can serve the file directly as we already have it
                ArbitraryDataFileMessage arbitraryDataFileMessage = new ArbitraryDataFileMessage(sig, arbitraryDataFile);
                arbitraryDataFileMessage.setId(msgId);

                PeerSendManagement.getInstance().getOrCreateSendManager(peer).queueMessage(arbitraryDataFileMessage);

            }
            else if (relayInfo != null) {
                LOGGER.debug("We have relay info for hash {}", Base58.encode(hash));
                // We need to ask this peer for the file
                Peer peerToAsk = relayInfo.getPeer();
                if (peerToAsk != null) {
                    // New Logic in v5.20 - Provide relay data to peer instead of fetching it
                    PeerRelayDataMessage prdm = new PeerRelayDataMessage(peerToAsk.getPeerData().getAddress().getHost(), hash);
                    PeerSendManagement.getInstance().getOrCreateSendManager(peer).queueMessageWithPriority(HIGH_PRIORITY, prdm);
                    /* Old Logic,
                    // Forward the message to this peer
                    LOGGER.debug("Asking peer {} for hash {}", peerToAsk, hash58);
                    // No need to pass arbitraryTransactionData below because this is only used for metadata caching,
                    // and metadata isn't retained when relaying.
                    // This is old logic,
                    this.fetchFileForRelay(peerToAsk, peer, signature, hash, message);
                    // Instead of relay tell them where we know about it
                    */
                }
                else {
                    LOGGER.debug("Peer {} not found in relay info", peer);
                }
            }
            else {
                LOGGER.debug("Hash {} doesn't exist and we don't have relay info", hash58);

                // We don't have this file
                Controller.getInstance().stats.getArbitraryDataFileMessageStats.unknownFiles.getAndIncrement();

                // Send valid, yet unexpected message type in response, so peer's synchronizer doesn't have to wait for timeout
                LOGGER.debug("Sending 'file unknown' response to peer {} for GET_FILE request for unknown file {}", peer, arbitraryDataFile);

                // Send generic 'unknown' message as it's very short
                Message fileUnknownMessage = peer.getPeersVersion() >= GenericUnknownMessage.MINIMUM_PEER_VERSION
                        ? new GenericUnknownMessage()
                        : new BlockSummariesMessage(Collections.emptyList());
                fileUnknownMessage.setId(msgId);
                if (!peer.sendMessage(fileUnknownMessage)) {
                    LOGGER.debug("Couldn't sent file-unknown response");
                }
                else {
                    LOGGER.debug("Sent file-unknown response for file {}", arbitraryDataFile);
                }
            }
        }
        catch (DataException e) {
            LOGGER.debug("Unable to handle request for arbitrary data file: {}", hash58);
        } catch (MessageException e) {
            throw new RuntimeException(e);
        }

    }

    // @ToDo: New Message Type, get a series of file hashes
//    public void onNetworkGetArbitraryDataFilesMessage(Peer peer, Message message) {
//        // Request for Multiple-Files
//        if (!Settings.getInstance().isQdnEnabled()) {
//            return;
//        }
//        LOGGER.info("NEW MESSAGE - GetArbitraryDataFiles");
//        GetArbitraryDataFilesMessage getArbitraryDataFilesMessage = (GetArbitraryDataFilesMessage) message;
//        List<byte[]> hashes = getArbitraryDataFilesMessage.getHashes();
//
//        byte[] signature = getArbitraryDataFilesMessage.getSignature();
//        int hashCount = getArbitraryDataFilesMessage.getHashCount();
//        Controller.getInstance().stats.getArbitraryDataFileMessageStats.requests.addAndGet(hashCount);
//        //LOGGER.info("Received GetArbitraryDataFilesMessages from peer {} for {} hashes", peer, hashCount);
//        for (byte[] hash : hashes) {
//            processDataFile(peer, hash, signature, message.getId());
//        }
//    }

    public void onNetworkGetArbitraryDataFileMessage(Peer peer, Message message) {
        // Don't respond if QDN is disabled
        if (!Settings.getInstance().isQdnEnabled()) {
            return;
        }
        GetArbitraryDataFileMessage getArbitraryDataFileMessage = (GetArbitraryDataFileMessage) message;
        byte[] hash = getArbitraryDataFileMessage.getHash();
        byte[] signature = getArbitraryDataFileMessage.getSignature();
        Controller.getInstance().stats.getArbitraryDataFileMessageStats.requests.incrementAndGet();

        processDataFile(peer, hash, signature, message.getId());
    }


    /*  Original Good working Method
    public void onNetworkGetArbitraryDataFileMessage(Peer peer, Message message) {
        // Don't respond if QDN is disabled
        if (!Settings.getInstance().isQdnEnabled()) {
            return;
        }

        GetArbitraryDataFileMessage getArbitraryDataFileMessage = (GetArbitraryDataFileMessage) message;
        byte[] hash = getArbitraryDataFileMessage.getHash();
        String hash58 = Base58.encode(hash);
        byte[] signature = getArbitraryDataFileMessage.getSignature();
        Controller.getInstance().stats.getArbitraryDataFileMessageStats.requests.incrementAndGet();

        LOGGER.info("Received GetArbitraryDataFileMessage from peer {} for hash {}", peer, Base58.encode(hash));

        try {
            ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(hash, signature);
            ArbitraryRelayInfo relayInfo = this.getOptimalRelayInfoEntryForHash(hash58);

            if (arbitraryDataFile.exists()) {
                LOGGER.info("Hash {} exists", hash58);

                // We can serve the file directly as we already have it
                LOGGER.info("Sending file {}...", arbitraryDataFile);
                ArbitraryDataFileMessage arbitraryDataFileMessage = new ArbitraryDataFileMessage(signature, arbitraryDataFile);
                arbitraryDataFileMessage.setId(message.getId());

                PeerSendManagement.getInstance().getOrCreateSendManager(peer).queueMessage(arbitraryDataFileMessage); // , SEND_TIMEOUT_MS

            }
            else if (relayInfo != null) {
                LOGGER.debug("We have relay info for hash {}", Base58.encode(hash));
                // We need to ask this peer for the file
                Peer peerToAsk = relayInfo.getPeer();
                if (peerToAsk != null) {

                    // Forward the message to this peer
                    LOGGER.debug("Asking peer {} for hash {}", peerToAsk, hash58);
                    // No need to pass arbitraryTransactionData below because this is only used for metadata caching,
                    // and metadata isn't retained when relaying.
                    this.fetchFileForRelay(peerToAsk, peer, signature, hash, message);
                }
                else {
                    LOGGER.debug("Peer {} not found in relay info", peer);
                }
            }
            else {
                LOGGER.debug("Hash {} doesn't exist and we don't have relay info", hash58);

                // We don't have this file
                Controller.getInstance().stats.getArbitraryDataFileMessageStats.unknownFiles.getAndIncrement();

                // Send valid, yet unexpected message type in response, so peer's synchronizer doesn't have to wait for timeout
                LOGGER.debug("Sending 'file unknown' response to peer {} for GET_FILE request for unknown file {}", peer, arbitraryDataFile);

                // Send generic 'unknown' message as it's very short
                Message fileUnknownMessage = peer.getPeersVersion() >= GenericUnknownMessage.MINIMUM_PEER_VERSION
                        ? new GenericUnknownMessage()
                        : new BlockSummariesMessage(Collections.emptyList());
                fileUnknownMessage.setId(message.getId());
                if (!peer.sendMessage(fileUnknownMessage)) {
                    LOGGER.debug("Couldn't sent file-unknown response");
                }
                else {
                    LOGGER.debug("Sent file-unknown response for file {}", arbitraryDataFile);
                }
            }
        }
        catch (DataException e) {
            LOGGER.debug("Unable to handle request for arbitrary data file: {}", hash58);
        } catch (MessageException e) {
            throw new RuntimeException(e);
        }
    }
     */

    /* Calls to SubClass */
    Map<String, Integer> getPeerTimeOuts() {
        return this.pendingPeersWithHashes.getTimeOuts();
    }

    Map<String, List<ArbitraryFileListResponseInfo>> getPendingPeerAndChunks() {
        return this.pendingPeersWithHashes.getPendingPeersWithChunks();
    }

    void removePeerTimeOut(String peer) {
        this.pendingPeersWithHashes.cleanOut(peer);
    }

    void removePeerChunk(String peer, String hash58) {
        this.pendingPeersWithHashes.removePeerChunk(peer, hash58);
    }

    void incrementTimeOuts() {
        this.pendingPeersWithHashes.incrementTimeOuts();
    }

    boolean pendingPeersAndChunks() {
        return this.pendingPeersWithHashes.isPending();
    }

    void addResponseToPending(Peer peer, ArbitraryFileListResponseInfo ri) {
        this.pendingPeersWithHashes.addPeerAndInfo(peer, ri);
    }

    boolean getIsConnectingPeer(String peer) {
        return this.pendingPeersWithHashes.isConnecting(peer);
    }

    void setIsConnecting(String peer, boolean v) {
        this.pendingPeersWithHashes.setConnecting(peer, v);
    }

    public static class PendingPeersWithHashes {
        //private final Map<Peer, List<ArbitraryFileListResponseInfo>> pendingPeerAndChunks;
        //private final Map<Peer, Integer> pendingPeerTries;
        // All keys are in the format "Host:Port"
        private final Map<String, List<ArbitraryFileListResponseInfo>> pendingPeerAndChunks;
        private final Map<String, Integer> pendingPeerTries;

        private final List<String> isConnectingPeers;
        private final Object combinedLock = new Object();

        public PendingPeersWithHashes() {
            pendingPeerTries = new HashMap<>();
            pendingPeerAndChunks = new HashMap<>();
            isConnectingPeers = new ArrayList<>();
        }

        Map<String, Integer> getTimeOuts() {
            synchronized (combinedLock) {
                return new HashMap<>(this.pendingPeerTries);
            }
        }

        // Snapshot of pendingPeerAndChunks (deep-ish copy of lists)
        Map<String, List<ArbitraryFileListResponseInfo>> getPendingPeersWithChunks() {
            synchronized (combinedLock) {
                Map<String, List<ArbitraryFileListResponseInfo>> copy = new HashMap<>(this.pendingPeerAndChunks.size());
                for (Map.Entry<String, List<ArbitraryFileListResponseInfo>> e : this.pendingPeerAndChunks.entrySet()) {
                    // create a new List copy so callers can iterate safely
                    copy.put(e.getKey(), new ArrayList<>(e.getValue()));
                }
                return copy;
            }
        }

        void cleanOut(String peer) {
            synchronized (combinedLock) {
                this.pendingPeerTries.remove(peer);
                this.pendingPeerAndChunks.remove(peer);
            }
        }

        void incrementTimeOuts() {
            synchronized (combinedLock) {
                this.pendingPeerTries.replaceAll((key, value) -> value + 1);
            }
        }

        boolean isPending() {
            return !this.pendingPeerAndChunks.isEmpty();
        }

        boolean isPending(Peer peer) {
            return this.pendingPeerAndChunks.containsKey(peer);
        }

        void removePeerChunk(String peer, String hash58){
            // Find the specific hash and remove it from list
            // if the list is empty remove PeerTimeOut if it hasn't expired
            // setIsConnecting(false)
        }

        void addPeerAndInfo(Peer peer, ArbitraryFileListResponseInfo aflri) {
            synchronized (combinedLock) {
                String peerKey = peer.toString();
                this.pendingPeerTries.putIfAbsent(peerKey, 0);
                //LOGGER.info("Unique peer count is: {}", pendingPeerTries.size());

                // 1. Get the current list for this peer, or create a new one
                List<ArbitraryFileListResponseInfo> currentList = pendingPeerAndChunks
                        .computeIfAbsent(peerKey, k -> new ArrayList<>());

                // 2. Check if the hash58 is already in the list
                boolean isDuplicate = currentList.stream()
                        .anyMatch(existingAflri -> aflri.getHash58().equals(existingAflri.getHash58()));

                // 3. Only add if it is not a duplicate
                if (!isDuplicate) {
                    currentList.add(aflri);
                    LOGGER.info("ADDED unique AFLRI with hash {} for peer {}", aflri.getHash58(), peerKey);
                } else {
                    LOGGER.info("SKIPPED adding duplicate AFLRI with hash {} for peer {}", aflri.getHash58(), peerKey);
                }

            } // End of synchronized block
        }

        /* First Attempt
        void addPeerAndInfo(Peer peer, ArbitraryFileListResponseInfo aflri) {
            synchronized (combinedLock) {
                this.pendingPeerTries.putIfAbsent(peer.toString(), 0);
                LOGGER.info("Unique peer count is: {}", pendingPeerTries.size());
                pendingPeerAndChunks
                        .computeIfAbsent(peer.toString(), k -> new ArrayList<>())
                        .add(aflri);
                // @ToDo: only add aflri to the array if it does not exist
                // Check aflri.getHash58() to determine if it exists
            }

            LOGGER.info("pendingPeerAndChucks has {} peers ", pendingPeerAndChunks.size());
        }
        */

        void setConnecting(String peer, boolean v) {
            synchronized (combinedLock) {
                if (v) {
                    isConnectingPeers.add(peer);
                }
                else {
                    try {
                        isConnectingPeers.remove(peer);
                    } catch (Exception ignored) {

                    }
                }
            }
        }

        boolean isConnecting(String peer) {
            return isConnectingPeers.contains(peer);
        }
    }
}
