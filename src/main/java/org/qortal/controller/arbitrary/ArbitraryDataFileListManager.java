package org.qortal.controller.arbitrary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.arbitrary.ArbitraryDataFile;
import org.qortal.arbitrary.ArbitraryDataFileChunk;
import org.qortal.controller.Controller;
import org.qortal.data.arbitrary.ArbitraryDirectConnectionInfo;
import org.qortal.data.arbitrary.ArbitraryFileListResponseInfo;
import org.qortal.data.arbitrary.ArbitraryRelayInfo;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.network.Network;
import org.qortal.network.Peer;
import org.qortal.network.message.ArbitraryDataFileListMessage;
import org.qortal.network.message.GetArbitraryDataFileListMessage;
import org.qortal.network.message.Message;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.utils.Base58;
import org.qortal.utils.ListUtils;
import org.qortal.utils.NTP;
import org.qortal.utils.Triple;

import java.util.*;

import static org.qortal.controller.arbitrary.ArbitraryDataFileManager.MAX_FILE_HASH_RESPONSES;

public class ArbitraryDataFileListManager {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataFileListManager.class);

    private static ArbitraryDataFileListManager instance;

    private static String MIN_PEER_VERSION_FOR_FILE_LIST_STATS = "3.2.0";

    /**
     * Map of recent incoming requests for ARBITRARY transaction data file lists.
     * <p>
     * Key is original request's message ID<br>
     * Value is Triple&lt;transaction signature in base58, first requesting peer, first request's timestamp&gt;
     * <p>
     * If peer is null then either:<br>
     * <ul>
     * <li>we are the original requesting peer</li>
     * <li>we have already sent data payload to original requesting peer.</li>
     * </ul>
     * If signature is null then we have already received the file list and either:<br>
     * <ul>
     * <li>we are the original requesting peer and have processed it</li>
     * <li>we have forwarded the file list</li>
     * </ul>
     */
    public Map<Integer, Triple<String, Peer, Long>> arbitraryDataFileListRequests = Collections.synchronizedMap(new HashMap<>());

    /**
     * Map to keep track of in progress arbitrary data signature requests
     * Key: string - the signature encoded in base58
     * Value: Triple<networkBroadcastCount, directPeerRequestCount, lastAttemptTimestamp>
     */
    private Map<String, Triple<Integer, Integer, Long>> arbitraryDataSignatureRequests = Collections.synchronizedMap(new HashMap<>());


    /** Maximum number of seconds that a file list relay request is able to exist on the network */
    public static long RELAY_REQUEST_MAX_DURATION = 5000L;
    /** Maximum number of hops that a file list relay request is allowed to make */
    public static int RELAY_REQUEST_MAX_HOPS = 4;

    /** Minimum peer version to use relay */
    public static String RELAY_MIN_PEER_VERSION = "3.4.0";


    private ArbitraryDataFileListManager() {
    }

    public static ArbitraryDataFileListManager getInstance() {
        if (instance == null)
            instance = new ArbitraryDataFileListManager();

        return instance;
    }


    public void cleanupRequestCache(Long now) {
        if (now == null) {
            return;
        }
        final long requestMinimumTimestamp = now - ArbitraryDataManager.ARBITRARY_REQUEST_TIMEOUT;
        arbitraryDataFileListRequests.entrySet().removeIf(entry -> entry.getValue().getC() == null || entry.getValue().getC() < requestMinimumTimestamp);
    }


    // Track file list lookups by signature

    private boolean shouldMakeFileListRequestForSignature(String signature58) {
        Triple<Integer, Integer, Long> request = arbitraryDataSignatureRequests.get(signature58);

        if (request == null) {
            // Not attempted yet
            return true;
        }

        // Extract the components
        Integer networkBroadcastCount = request.getA();
        // Integer directPeerRequestCount = request.getB();
        Long lastAttemptTimestamp = request.getC();

        if (lastAttemptTimestamp == null) {
            // Not attempted yet
            return true;
        }

        long timeSinceLastAttempt = NTP.getTime() - lastAttemptTimestamp;

        // Allow a second attempt after 15 seconds, and another after 30 seconds
        if (timeSinceLastAttempt > 15 * 1000L) {
            // We haven't tried for at least 15 seconds

            if (networkBroadcastCount < 3) {
                // We've made less than 3 total attempts
                return true;
            }
        }

        // Then allow another 5 attempts, each 1 minute apart
        if (timeSinceLastAttempt > 60 * 1000L) {
            // We haven't tried for at least 1 minute

            if (networkBroadcastCount < 8) {
                // We've made less than 8 total attempts
                return true;
            }
        }

        // Then allow another 8 attempts, each 15 minutes apart
        if (timeSinceLastAttempt > 15 * 60 * 1000L) {
            // We haven't tried for at least 15 minutes

            if (networkBroadcastCount < 16) {
                // We've made less than 16 total attempts
                return true;
            }
        }

        // From then on, only try once every 6 hours, to reduce network spam
        if (timeSinceLastAttempt > 6 * 60 * 60 * 1000L) {
            // We haven't tried for at least 6 hours
            return true;
        }

        return false;
    }

    private boolean shouldMakeDirectFileRequestsForSignature(String signature58) {
        if (!Settings.getInstance().isDirectDataRetrievalEnabled()) {
            // Direct connections are disabled in the settings
            return false;
        }

        Triple<Integer, Integer, Long> request = arbitraryDataSignatureRequests.get(signature58);

        if (request == null) {
            // Not attempted yet
            return true;
        }

        // Extract the components
        //Integer networkBroadcastCount = request.getA();
        Integer directPeerRequestCount = request.getB();
        Long lastAttemptTimestamp = request.getC();

        if (lastAttemptTimestamp == null) {
            // Not attempted yet
            return true;
        }

        if (directPeerRequestCount == 0) {
            // We haven't tried asking peers directly yet, so we should
            return true;
        }

        long timeSinceLastAttempt = NTP.getTime() - lastAttemptTimestamp;
        if (timeSinceLastAttempt > 10 * 1000L) {
            // We haven't tried for at least 10 seconds
            if (directPeerRequestCount < 5) {
                // We've made less than 5 total attempts
                return true;
            }
        }

        if (timeSinceLastAttempt > 5 * 60 * 1000L) {
            // We haven't tried for at least 5 minutes
            if (directPeerRequestCount < 10) {
                // We've made less than 10 total attempts
                return true;
            }
        }

        if (timeSinceLastAttempt > 60 * 60 * 1000L) {
            // We haven't tried for at least 1 hour
            return true;
        }

        return false;
    }

    public boolean isSignatureRateLimited(byte[] signature) {
        String signature58 = Base58.encode(signature);
        return !this.shouldMakeFileListRequestForSignature(signature58)
                && !this.shouldMakeDirectFileRequestsForSignature(signature58);
    }

    public long lastRequestForSignature(byte[] signature) {
        String signature58 = Base58.encode(signature);
        Triple<Integer, Integer, Long> request = arbitraryDataSignatureRequests.get(signature58);

        if (request == null) {
            // Not attempted yet
            return 0;
        }

        // Extract the components
        Long lastAttemptTimestamp = request.getC();
        if (lastAttemptTimestamp != null) {
            return  lastAttemptTimestamp;
        }
        return 0;
    }

    public void addToSignatureRequests(String signature58, boolean incrementNetworkRequests, boolean incrementPeerRequests) {
        Triple<Integer, Integer, Long> request  = arbitraryDataSignatureRequests.get(signature58);
        Long now = NTP.getTime();

        if (request == null) {
            // No entry yet
            Triple<Integer, Integer, Long> newRequest = new Triple<>(0, 0, now);
            arbitraryDataSignatureRequests.put(signature58, newRequest);
        }
        else {
            // There is an existing entry
            if (incrementNetworkRequests) {
                request.setA(request.getA() + 1);
            }
            if (incrementPeerRequests) {
                request.setB(request.getB() + 1);
            }
            request.setC(now);
            arbitraryDataSignatureRequests.put(signature58, request);
        }
    }

    public void removeFromSignatureRequests(String signature58) {
        arbitraryDataSignatureRequests.remove(signature58);
    }


    // Lookup file lists by signature (and optionally hashes)

    public boolean fetchArbitraryDataFileList(ArbitraryTransactionData arbitraryTransactionData) {
        byte[] signature = arbitraryTransactionData.getSignature();
        String signature58 = Base58.encode(signature);

        // Require an NTP sync
        Long now = NTP.getTime();
        if (now == null) {
            return false;
        }

        // If we've already tried too many times in a short space of time, make sure to give up
        if (!this.shouldMakeFileListRequestForSignature(signature58)) {
            // Check if we should make direct connections to peers
            if (this.shouldMakeDirectFileRequestsForSignature(signature58)) {
                return ArbitraryDataFileManager.getInstance().fetchDataFilesFromPeersForSignature(signature);
            }

            LOGGER.trace("Skipping file list request for signature {} due to rate limit", signature58);
            return false;
        }
        this.addToSignatureRequests(signature58, true, false);

        List<Peer> handshakedPeers = Network.getInstance().getImmutableHandshakedPeers();
        List<byte[]> missingHashes = null;

        // Find hashes that we are missing
        try {
            ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromTransactionData(arbitraryTransactionData);
            missingHashes = arbitraryDataFile.missingHashes();
        } catch (DataException e) {
            // Leave missingHashes as null, so that all hashes are requested
        }
        int hashCount = missingHashes != null ? missingHashes.size() : 0;

        LOGGER.debug(String.format("Sending data file list request for signature %s with %d hashes to %d peers...", signature58, hashCount, handshakedPeers.size()));

        // Send our address as requestingPeer, to allow for potential direct connections with seeds/peers
        String requestingPeer = Network.getInstance().getOurExternalIpAddressAndPort();

        // Build request
        Message getArbitraryDataFileListMessage = new GetArbitraryDataFileListMessage(signature, missingHashes, now, 0, requestingPeer);

        // Save our request into requests map
        Triple<String, Peer, Long> requestEntry = new Triple<>(signature58, null, NTP.getTime());

        // Assign random ID to this message
        int id;
        do {
            id = new Random().nextInt(Integer.MAX_VALUE - 1) + 1;

            // Put queue into map (keyed by message ID) so we can poll for a response
            // If putIfAbsent() doesn't return null, then this ID is already taken
        } while (arbitraryDataFileListRequests.put(id, requestEntry) != null);
        getArbitraryDataFileListMessage.setId(id);

        // Broadcast request
        Network.getInstance().broadcast(peer -> getArbitraryDataFileListMessage);

        // Poll to see if data has arrived
        final long singleWait = 100;
        long totalWait = 0;
        while (totalWait < ArbitraryDataManager.ARBITRARY_REQUEST_TIMEOUT) {
            try {
                Thread.sleep(singleWait);
            } catch (InterruptedException e) {
                break;
            }

            requestEntry = arbitraryDataFileListRequests.get(id);
            if (requestEntry == null)
                return false;

            if (requestEntry.getA() == null)
                break;

            totalWait += singleWait;
        }
        return true;
    }

    public boolean fetchArbitraryDataFileList(Peer peer, byte[] signature) {
        String signature58 = Base58.encode(signature);

        // Require an NTP sync
        Long now = NTP.getTime();
        if (now == null) {
            return false;
        }

        int hashCount = 0;
        LOGGER.debug(String.format("Sending data file list request for signature %s with %d hashes to peer %s...", signature58, hashCount, peer));

        // Build request
        // Use a time in the past, so that the recipient peer doesn't try and relay it
        // Also, set hashes to null since it's easier to request all hashes than it is to determine which ones we need
        // This could be optimized in the future
        long timestamp = now - 60000L;
        List<byte[]> hashes = null;
        Message getArbitraryDataFileListMessage = new GetArbitraryDataFileListMessage(signature, hashes, timestamp, 0, null);

        // Save our request into requests map
        Triple<String, Peer, Long> requestEntry = new Triple<>(signature58, null, NTP.getTime());

        // Assign random ID to this message
        int id;
        do {
            id = new Random().nextInt(Integer.MAX_VALUE - 1) + 1;

            // Put queue into map (keyed by message ID) so we can poll for a response
            // If putIfAbsent() doesn't return null, then this ID is already taken
        } while (arbitraryDataFileListRequests.put(id, requestEntry) != null);
        getArbitraryDataFileListMessage.setId(id);

        // Send the request
        peer.sendMessage(getArbitraryDataFileListMessage);

        // Poll to see if data has arrived
        final long singleWait = 100;
        long totalWait = 0;
        while (totalWait < ArbitraryDataManager.ARBITRARY_REQUEST_TIMEOUT) {
            try {
                Thread.sleep(singleWait);
            } catch (InterruptedException e) {
                break;
            }

            requestEntry = arbitraryDataFileListRequests.get(id);
            if (requestEntry == null)
                return false;

            if (requestEntry.getA() == null)
                break;

            totalWait += singleWait;
        }
        return true;
    }

    public void deleteFileListRequestsForSignature(byte[] signature) {
        String signature58 = Base58.encode(signature);
        for (Iterator<Map.Entry<Integer, Triple<String, Peer, Long>>> it = arbitraryDataFileListRequests.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Integer, Triple<String, Peer, Long>> entry = it.next();
            if (entry == null || entry.getKey() == null || entry.getValue() != null) {
                continue;
            }
            if (Objects.equals(entry.getValue().getA(), signature58)) {
                // Update requests map to reflect that we've received all chunks
                Triple<String, Peer, Long> newEntry = new Triple<>(null, null, entry.getValue().getC());
                arbitraryDataFileListRequests.put(entry.getKey(), newEntry);
            }
        }
    }

    // Network handlers

    public void onNetworkArbitraryDataFileListMessage(Peer peer, Message message) {
        // Don't process if QDN is disabled
        if (!Settings.getInstance().isQdnEnabled()) {
            return;
        }

        ArbitraryDataFileListMessage arbitraryDataFileListMessage = (ArbitraryDataFileListMessage) message;
        LOGGER.debug("Received hash list from peer {} with {} hashes", peer, arbitraryDataFileListMessage.getHashes().size());

        if (LOGGER.isDebugEnabled() && arbitraryDataFileListMessage.getRequestTime() != null) {
            long totalRequestTime = NTP.getTime() - arbitraryDataFileListMessage.getRequestTime();
            LOGGER.debug("totalRequestTime: {}, requestHops: {}, peerAddress: {}, isRelayPossible: {}",
                    totalRequestTime, arbitraryDataFileListMessage.getRequestHops(),
                    arbitraryDataFileListMessage.getPeerAddress(), arbitraryDataFileListMessage.isRelayPossible());
        }

        // Do we have a pending request for this data?
        Triple<String, Peer, Long> request = arbitraryDataFileListRequests.get(message.getId());
        if (request == null || request.getA() == null) {
            return;
        }
        boolean isRelayRequest = (request.getB() != null);

        // Does this message's signature match what we're expecting?
        byte[] signature = arbitraryDataFileListMessage.getSignature();
        String signature58 = Base58.encode(signature);
        if (!request.getA().equals(signature58)) {
            return;
        }

        List<byte[]> hashes = arbitraryDataFileListMessage.getHashes();
        if (hashes == null || hashes.isEmpty()) {
            return;
        }

        ArbitraryTransactionData arbitraryTransactionData = null;

        // Check transaction exists and hashes are correct
        try (final Repository repository = RepositoryManager.getRepository()) {
            TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
            if (!(transactionData instanceof ArbitraryTransactionData))
                return;

            arbitraryTransactionData = (ArbitraryTransactionData) transactionData;

//          // Load data file(s)
//          ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromTransactionData(arbitraryTransactionData);
//
//			// Check all hashes exist
//			for (byte[] hash : hashes) {
//				//LOGGER.debug("Received hash {}", Base58.encode(hash));
//				if (!arbitraryDataFile.containsChunk(hash)) {
//					// Check the hash against the complete file
//					if (!Arrays.equals(arbitraryDataFile.getHash(), hash)) {
//						LOGGER.info("Received non-matching chunk hash {} for signature {}. This could happen if we haven't obtained the metadata file yet.", Base58.encode(hash), signature58);
//						return;
//					}
//				}
//			}

            if (!isRelayRequest || !Settings.getInstance().isRelayModeEnabled()) {
                Long now = NTP.getTime();

                if (ArbitraryDataFileManager.getInstance().arbitraryDataFileHashResponses.size() < MAX_FILE_HASH_RESPONSES) {
                    // Keep track of the hashes this peer reports to have access to
                    for (byte[] hash : hashes) {
                        String hash58 = Base58.encode(hash);

                        // Treat null request hops as 100, so that they are able to be sorted (and put to the end of the list)
                        int requestHops = arbitraryDataFileListMessage.getRequestHops() != null ? arbitraryDataFileListMessage.getRequestHops() : 100;

                        ArbitraryFileListResponseInfo responseInfo = new ArbitraryFileListResponseInfo(hash58, signature58,
                                peer, now, arbitraryDataFileListMessage.getRequestTime(), requestHops);

                        ArbitraryDataFileManager.getInstance().arbitraryDataFileHashResponses.add(responseInfo);
                    }
                }

                // Keep track of the source peer, for direct connections
                if (arbitraryDataFileListMessage.getPeerAddress() != null) {
                    ArbitraryDataFileManager.getInstance().addDirectConnectionInfoIfUnique(
                            new ArbitraryDirectConnectionInfo(signature, arbitraryDataFileListMessage.getPeerAddress(), hashes, now));
                }
            }

        } catch (DataException e) {
            LOGGER.error(String.format("Repository issue while finding arbitrary transaction data list for peer %s", peer), e);
        }

        // Forwarding
        if (isRelayRequest && Settings.getInstance().isRelayModeEnabled()) {
            boolean isBlocked = (arbitraryTransactionData == null || ListUtils.isNameBlocked(arbitraryTransactionData.getName()));
            if (!isBlocked) {
                Peer requestingPeer = request.getB();
                if (requestingPeer != null) {
                    Long requestTime = arbitraryDataFileListMessage.getRequestTime();
                    Integer requestHops = arbitraryDataFileListMessage.getRequestHops();

                    // Add each hash to our local mapping so we know who to ask later
                    Long now = NTP.getTime();
                    for (byte[] hash : hashes) {
                        String hash58 = Base58.encode(hash);
                        ArbitraryRelayInfo relayInfo = new ArbitraryRelayInfo(hash58, signature58, peer, now, requestTime, requestHops);
                        ArbitraryDataFileManager.getInstance().addToRelayMap(relayInfo);
                    }

                    // Bump requestHops if it exists
                    if (requestHops != null) {
                        requestHops++;
                    }

                    ArbitraryDataFileListMessage forwardArbitraryDataFileListMessage;

                    // Remove optional parameters if the requesting peer doesn't support it yet
                    // A message with less statistical data is better than no message at all
                    if (!requestingPeer.isAtLeastVersion(MIN_PEER_VERSION_FOR_FILE_LIST_STATS)) {
                        forwardArbitraryDataFileListMessage = new ArbitraryDataFileListMessage(signature, hashes);
                    } else {
                        forwardArbitraryDataFileListMessage = new ArbitraryDataFileListMessage(signature, hashes, requestTime, requestHops,
                                arbitraryDataFileListMessage.getPeerAddress(), arbitraryDataFileListMessage.isRelayPossible());
                    }
                    forwardArbitraryDataFileListMessage.setId(message.getId());

                    // Forward to requesting peer
                    LOGGER.debug("Forwarding file list with {} hashes to requesting peer: {}", hashes.size(), requestingPeer);
                    if (!requestingPeer.sendMessage(forwardArbitraryDataFileListMessage)) {
                        requestingPeer.disconnect("failed to forward arbitrary data file list");
                    }
                }
            }
        }
    }

    public void onNetworkGetArbitraryDataFileListMessage(Peer peer, Message message) {
        // Don't respond if QDN is disabled
        if (!Settings.getInstance().isQdnEnabled()) {
            return;
        }

        Controller.getInstance().stats.getArbitraryDataFileListMessageStats.requests.incrementAndGet();

        GetArbitraryDataFileListMessage getArbitraryDataFileListMessage = (GetArbitraryDataFileListMessage) message;
        byte[] signature = getArbitraryDataFileListMessage.getSignature();
        String signature58 = Base58.encode(signature);
        Long now = NTP.getTime();
        Triple<String, Peer, Long> newEntry = new Triple<>(signature58, peer, now);

        // If we've seen this request recently, then ignore
        if (arbitraryDataFileListRequests.putIfAbsent(message.getId(), newEntry) != null) {
            LOGGER.trace("Ignoring hash list request from peer {} for signature {}", peer, signature58);
            return;
        }

        List<byte[]> requestedHashes = getArbitraryDataFileListMessage.getHashes();
        int hashCount = requestedHashes != null ? requestedHashes.size() : 0;
        String requestingPeer = getArbitraryDataFileListMessage.getRequestingPeer();

        if (requestingPeer != null) {
            LOGGER.debug("Received hash list request with {} hashes from peer {} (requesting peer {}) for signature {}", hashCount, peer, requestingPeer, signature58);
        }
        else {
            LOGGER.debug("Received hash list request with {} hashes from peer {} for signature {}", hashCount, peer, signature58);
        }

        List<byte[]> hashes = new ArrayList<>();
        ArbitraryTransactionData transactionData = null;
        boolean allChunksExist = false;
        boolean hasMetadata = false;

        try (final Repository repository = RepositoryManager.getRepository()) {

            // Firstly we need to lookup this file on chain to get a list of its hashes
            transactionData = (ArbitraryTransactionData)repository.getTransactionRepository().fromSignature(signature);
            if (transactionData instanceof ArbitraryTransactionData) {

                // Check if we're even allowed to serve data for this transaction
                if (ArbitraryDataStorageManager.getInstance().canStoreData(transactionData)) {

                    // Load file(s) and add any that exist to the list of hashes
                    ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromTransactionData(transactionData);

                    // If the peer didn't supply a hash list, we need to return all hashes for this transaction
                    if (requestedHashes == null || requestedHashes.isEmpty()) {
                        requestedHashes = new ArrayList<>();

                        // Add the metadata file
                        if (arbitraryDataFile.getMetadataHash() != null) {
                            requestedHashes.add(arbitraryDataFile.getMetadataHash());
                            hasMetadata = true;
                        }

                        // Add the chunk hashes
                        if (!arbitraryDataFile.getChunkHashes().isEmpty()) {
                            requestedHashes.addAll(arbitraryDataFile.getChunkHashes());
                        }
                        // Add complete file if there are no hashes
                        else {
                            requestedHashes.add(arbitraryDataFile.getHash());
                        }
                    }

                    // Assume all chunks exists, unless one can't be found below
                    allChunksExist = true;

                    for (byte[] requestedHash : requestedHashes) {
                        ArbitraryDataFileChunk chunk = ArbitraryDataFileChunk.fromHash(requestedHash, signature);
                        if (chunk.exists()) {
                            hashes.add(chunk.getHash());
                            //LOGGER.trace("Added hash {}", chunk.getHash58());
                        } else {
                            LOGGER.trace("Couldn't add hash {} because it doesn't exist", chunk.getHash58());
                            allChunksExist = false;
                        }
                    }
                }
            }

        } catch (DataException e) {
            LOGGER.error(String.format("Repository issue while fetching arbitrary file list for peer %s", peer), e);
        }

        // If the only file we have is the metadata then we shouldn't respond. Most nodes will already have that,
        // or can use the separate metadata protocol to fetch it. This should greatly reduce network spam.
        if (hasMetadata && hashes.size() == 1) {
            hashes.clear();
        }

        // We should only respond if we have at least one hash
        if (!hashes.isEmpty()) {

            // Firstly we should keep track of the requesting peer, to allow for potential direct connections later
            ArbitraryDataFileManager.getInstance().addRecentDataRequest(requestingPeer);

            // We have all the chunks, so update requests map to reflect that we've sent it
            // There is no need to keep track of the request, as we can serve all the chunks
            if (allChunksExist) {
                newEntry = new Triple<>(null, null, now);
                arbitraryDataFileListRequests.put(message.getId(), newEntry);
            }

            String ourAddress = Network.getInstance().getOurExternalIpAddressAndPort();
            ArbitraryDataFileListMessage arbitraryDataFileListMessage;

            // Remove optional parameters if the requesting peer doesn't support it yet
            // A message with less statistical data is better than no message at all
            if (!peer.isAtLeastVersion(MIN_PEER_VERSION_FOR_FILE_LIST_STATS)) {
                arbitraryDataFileListMessage = new ArbitraryDataFileListMessage(signature, hashes);
            } else {
                arbitraryDataFileListMessage = new ArbitraryDataFileListMessage(signature,
                        hashes, NTP.getTime(), 0, ourAddress, true);
            }

            arbitraryDataFileListMessage.setId(message.getId());

            if (!peer.sendMessage(arbitraryDataFileListMessage)) {
                LOGGER.debug("Couldn't send list of hashes");
                peer.disconnect("failed to send list of hashes");
                return;
            }
            LOGGER.debug("Sent list of hashes (count: {})", hashes.size());

            if (allChunksExist) {
                // Nothing left to do, so return to prevent any unnecessary forwarding from occurring
                LOGGER.debug("No need for any forwarding because file list request is fully served");
                return;
            }

        }

        // We may need to forward this request on
        boolean isBlocked = (transactionData == null || ListUtils.isNameBlocked(transactionData.getName()));
        if (Settings.getInstance().isRelayModeEnabled() && !isBlocked) {
            // In relay mode - so ask our other peers if they have it

            long requestTime = getArbitraryDataFileListMessage.getRequestTime();
            int requestHops = getArbitraryDataFileListMessage.getRequestHops() + 1;
            long totalRequestTime = now - requestTime;

            if (totalRequestTime < RELAY_REQUEST_MAX_DURATION) {
                // Relay request hasn't timed out yet, so can potentially be rebroadcast
                if (requestHops < RELAY_REQUEST_MAX_HOPS) {
                    // Relay request hasn't reached the maximum number of hops yet, so can be rebroadcast

                    Message relayGetArbitraryDataFileListMessage = new GetArbitraryDataFileListMessage(signature, hashes, requestTime, requestHops, requestingPeer);
                    relayGetArbitraryDataFileListMessage.setId(message.getId());

                    LOGGER.debug("Rebroadcasting hash list request from peer {} for signature {} to our other peers... totalRequestTime: {}, requestHops: {}", peer, Base58.encode(signature), totalRequestTime, requestHops);
                    Network.getInstance().broadcast(
                            broadcastPeer ->
                                    !broadcastPeer.isAtLeastVersion(RELAY_MIN_PEER_VERSION) ? null :
                                    broadcastPeer == peer || Objects.equals(broadcastPeer.getPeerData().getAddress().getHost(), peer.getPeerData().getAddress().getHost()) ? null : relayGetArbitraryDataFileListMessage
                    );

                }
                else {
                    // This relay request has reached the maximum number of allowed hops
                }
            }
            else {
                // This relay request has timed out
            }
        }
    }

}
