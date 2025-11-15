package org.qortal.network;

import com.dosse.upnp.UPnP;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.eclipse.persistence.sessions.remote.corba.sun._CORBARemoteSessionControllerImplBase;
import org.qortal.arbitrary.ArbitraryDataFile;
import org.qortal.block.BlockChain;
import org.qortal.controller.Controller;
import org.qortal.controller.arbitrary.ArbitraryDataFileListManager;
import org.qortal.controller.arbitrary.ArbitraryDataFileManager;
import org.qortal.crypto.Crypto;
import org.qortal.data.network.PeerData;
import org.qortal.network.message.*;
import org.qortal.network.task.*;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.utils.Base58;
import org.qortal.utils.ExecuteProduceConsume;
import org.qortal.utils.ExecuteProduceConsume.StatsSnapshot;
import org.qortal.utils.NTP;
import org.qortal.utils.NamedThreadFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.net.UnknownHostException;
import java.nio.channels.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

// For managing arbitrary data between peers
public class NetworkData {
    private static final Logger LOGGER = LogManager.getLogger(NetworkData.class);

    // the maximum number of pending connections the operating system will allow to queue up for the server socket
    private static final int LISTEN_BACKLOG = 5;

    // How long before retrying after a connection failure, in milliseconds.
    private static final long CONNECT_FAILURE_BACKOFF = 5 * 60 * 1000L; // ms

    // Maximum time since last successful connection for peer info to be propagated, in milliseconds.
    private static final long RECENT_CONNECTION_THRESHOLD = 24 * 60 * 60 * 1000L; // ms

    // Maximum time since last connection attempt before a peer is potentially considered "old", in milliseconds.
    private static final long OLD_PEER_ATTEMPTED_PERIOD = 24 * 60 * 60 * 1000L; // ms

    //  Maximum time since last successful connection before a peer is potentially considered "old", in milliseconds.
    private static final long OLD_PEER_CONNECTION_PERIOD = 7 * 24 * 60 * 60 * 1000L; // ms

    //  Maximum time allowed for handshake to complete, in milliseconds.
    private static final long HANDSHAKE_TIMEOUT = 60 * 1000L; // ms

    private static final byte[] MAINNET_MESSAGE_MAGIC = new byte[]{0x51, 0x4f, 0x52, 0x54}; // QORT
    private static final byte[] TESTNET_MESSAGE_MAGIC = new byte[]{0x71, 0x6f, 0x72, 0x54}; // qorT

    private static final long NETWORK_EPC_KEEPALIVE = 5L; // seconds

    private static final long DISCONNECTION_CHECK_INTERVAL = 180 * 1000L; // milliseconds - 3min

    // Generate our node keys / ID
    private final Ed25519PrivateKeyParameters edPrivateKeyParams = new Ed25519PrivateKeyParameters(new SecureRandom());
    private final Ed25519PublicKeyParameters edPublicKeyParams = edPrivateKeyParams.generatePublicKey();
    private final String ourNodeId = Crypto.toNodeAddress(edPublicKeyParams.getEncoded());

    private final int maxMessageSize;
    private final int minOutboundPeers;
    private final int maxPeers;

    private long nextDisconnectionCheck = 0L;

    private final List<PeerData> allKnownPeers = new ArrayList<>();

    /**
     * Maintain a list for each subset of peers:
     * - A synchronizedList, to be modified when peers are added/removed
     */
    private final List<Peer> connectedPeers = Collections.synchronizedList(new ArrayList<>());
    private final List<Peer> handshakedPeers = Collections.synchronizedList(new ArrayList<>());
    private final List<Peer> outboundHandshakedPeers = Collections.synchronizedList(new ArrayList<>());

//    private List<Peer> immutableConnectedPeers = Collections.emptyList(); // always rebuilt from mutable, synced list above
//    private List<Peer> immutableHandshakedPeers = Collections.emptyList(); // always rebuilt from mutable, synced list above
//    private List<Peer> immutableOutboundHandshakedPeers = Collections.emptyList(); // always rebuilt from mutable, synced list above

    //  Count threads per message type in order to enforce limits
    private final Map<MessageType, Integer> threadsPerMessageType = Collections.synchronizedMap(new HashMap<>());

    //  Keep track of total thread count, to warn when the thread pool is getting low
    private int totalThreadCount = 0;

    // * Thresholds at which to warn about the number of active threads
    private final int threadCountWarningThreshold = (int) (Settings.getInstance().getMaxNetworkThreadPoolSize() * 0.9f);
    private final Integer threadCountPerMessageTypeWarningThreshold = Settings.getInstance().getThreadCountPerMessageTypeWarningThreshold();

    private final List<PeerAddress> selfPeers = new ArrayList<>();

    private String bindAddress = null;

    private final ExecuteProduceConsume networkEPC;
    private Selector channelSelector;
    private ServerSocketChannel serverChannel;
    private SelectionKey serverSelectionKey;
    private final Set<SelectableChannel> channelsPendingWrite = ConcurrentHashMap.newKeySet();

    //private final Lock mergePeersLock = new ReentrantLock();

    private final List<String> ourExternalIpAddressHistory = new ArrayList<>();
    private String ourExternalIpAddress = null;
    private int ourExternalPort = Settings.getInstance().getListenPort();

    private volatile boolean isShuttingDown = false;

    // Constructors

    private NetworkData() {
        maxMessageSize = 4 + 1 + 4 + BlockChain.getInstance().getMaxBlockSize();

        minOutboundPeers = Settings.getInstance().getMinOutboundPeers();
        maxPeers = Settings.getInstance().getMaxPeers();

        // We'll use a cached thread pool but with more aggressive timeout.
        ExecutorService networkExecutor = new ThreadPoolExecutor(2,
                Settings.getInstance().getMaxNetworkThreadPoolSize(),
                NETWORK_EPC_KEEPALIVE, TimeUnit.SECONDS,  // 5 Seconds
                new SynchronousQueue<Runnable>(),
                new NamedThreadFactory("NetworkData-EPC", Settings.getInstance().getNetworkThreadPriority()));
        networkEPC = new NetworkDataProcessor(networkExecutor);
    }

    public void start() throws IOException, DataException {
        LOGGER.trace("Running start()");
        // Grab P2P port from settings
        int listenPort = Settings.getInstance().getQDNListenPort();

        // Grab P2P bind addresses from settings
        List<String> bindAddresses = new ArrayList<>();
        if (Settings.getInstance().getBindAddress() != null) {
            bindAddresses.add(Settings.getInstance().getBindAddress());
        }
        if (Settings.getInstance().getBindAddressFallback() != null) {
            bindAddresses.add(Settings.getInstance().getBindAddressFallback());
        }

        for (int i=0; i<bindAddresses.size(); i++) {
            try {
                String bindAddress = bindAddresses.get(i);
                InetAddress bindAddr = InetAddress.getByName(bindAddress);
                InetSocketAddress endpoint = new InetSocketAddress(bindAddr, listenPort);

                channelSelector = Selector.open();

                // Set up listen socket
                serverChannel = ServerSocketChannel.open();
                serverChannel.configureBlocking(false);
                serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
                serverChannel.bind(endpoint, LISTEN_BACKLOG);
                serverSelectionKey = serverChannel.register(channelSelector, SelectionKey.OP_ACCEPT);

                this.bindAddress = bindAddress; // Store the selected address, so that it can be used by other parts of the app
                LOGGER.trace("Success - Bound to interface: {}:{}", this.bindAddress,listenPort);
                break; // We don't want to bind to more than one address
            } catch (UnknownHostException | UnsupportedAddressTypeException e) {
                LOGGER.error("Can't bind listen socket to address {}", Settings.getInstance().getBindAddress());
                if (i == bindAddresses.size()-1) { // Only throw an exception if all addresses have been tried
                    throw new IOException("Can't bind listen socket to address", e);
                }
            } catch (IOException e) {
                LOGGER.error("Can't create listen socket: {}", e.getMessage());
                if (i == bindAddresses.size()-1) { // Only throw an exception if all addresses have been tried
                    throw new IOException("Can't create listen socket", e);
                }
            }
        }

        // Attempt to set up UPnP. All errors are ignored.
        if (Settings.getInstance().isUPnPEnabled()) {
            UPnP.openPortTCP(Settings.getInstance().getListenPort());
        }
        else {
            UPnP.closePortTCP(Settings.getInstance().getListenPort());
        }

        // Start up first networking thread
        networkEPC.start();
    }

    // Getters / setters

    private static class SingletonContainer {
        private static final NetworkData INSTANCE = new NetworkData();
    }

    // @ToDo : does this return all MessageTypes?
    public Map<MessageType, Integer> getThreadsPerMessageType() {
        return this.threadsPerMessageType;
    }

    public int getTotalThreadCount() {
        synchronized (this) {
            return this.totalThreadCount;
        }
    }

    public static NetworkData getInstance() {
        return SingletonContainer.INSTANCE;
    }

    public String getBindAddress() {
        return this.bindAddress;
    }

    public int getMaxPeers() {
        return this.maxPeers;
    }

    public byte[] getMessageMagic() {
        return Settings.getInstance().isTestNet() ? TESTNET_MESSAGE_MAGIC : MAINNET_MESSAGE_MAGIC;
    }

    public String getOurNodeId() {
        return this.ourNodeId;
    }

    protected byte[] getOurPublicKey() {
        return this.edPublicKeyParams.getEncoded();
    }

    /**
     * Maximum message size (bytes). Needs to be at least maximum block size + MAGIC + message type, etc.
     */
    protected int getMaxMessageSize() {
        return this.maxMessageSize;
    }

    public StatsSnapshot getStatsSnapshot() {
        return this.networkEPC.getStatsSnapshot();
    }

    // Peer lists
//    public void updatePeerList(List<PeerData> networkPeerList) {
//        synchronized (this.allKnownPeers) {
//
//            LOGGER.info("Our Connected Peer Count is: {}", connectedPeers.size());
//            LOGGER.info("Updating Peer List from Network");
//
//            // Because we need to set values from Network to baseline(0), only merge in new address, also skip already existing ones.
//            networkPeerList.removeIf(allKnownPeers::contains);
//            for(PeerData passedPeer : networkPeerList) {
//                passedPeer.setLastAttempted(0L);
//                passedPeer.setLastConnected(0L);
//                passedPeer.setLastMisbehaved(0L);
//                passedPeer.setFailedSyncCount(0);
//                allKnownPeers.add(passedPeer);              // Add to the list of peers
//            }
//
//            // Shows New Peers we are adding to the DataNetwork
//            for (PeerData p : networkPeerList ){
//                LOGGER.info("This is a peer passed into the list: {}", p.getAddress().toString());
//            }
//
//            // Shows all peers in the DataNetwork
//            for (PeerData p : allKnownPeers ){
//                LOGGER.info("This is the complete peer list: {}", p.getAddress().toString());
//            }
//        }
//    }

    public List<PeerData> getAllKnownPeers() {
        synchronized (this.allKnownPeers) {
            return new ArrayList<>(this.allKnownPeers);
        }
    }

    public PeerList getImmutableConnectedPeers() {
        return new PeerList(this.connectedPeers);
    }
//    public List<Peer> getImmutableConnectedPeers() {
//        return this.immutableConnectedPeers;
//    }

//    public List<Peer> getImmutableConnectedDataPeers() {
//        return this.getImmutableConnectedPeers().stream()
//                .filter(p -> p.isDataPeer())
//                .collect(Collectors.toList());
//    }

    public void addConnectedPeer(Peer peer) {
        this.connectedPeers.add(peer); // thread safe thanks to synchronized list
        //this.immutableConnectedPeers = List.copyOf(this.connectedPeers); // also thread safe thanks to synchronized collection's toArray() being fed to List.of(array)
    }

    public void removeConnectedPeer(Peer peer) {
        // Firstly remove from handshaked peers
        this.removeHandshakedPeer(peer);
        this.connectedPeers.remove(peer); // thread safe thanks to synchronized list
        //this.immutableConnectedPeers = List.copyOf(this.connectedPeers); // also thread safe thanks to synchronized collection's toArray() being fed to List.of(array)
    }

    public List<PeerAddress> getSelfPeers() {
        synchronized (this.selfPeers) {
            return new ArrayList<>(this.selfPeers);
        }
    }

    // Return a Peer Object by the Hostname/IP address only, no port reference
    // host can be ipv4 address or fqdn
    // If it accidentally contains the whole string "ip:port" strip the :port portion

    // Shouldnt need this anymore because we can use PeerList classe and methods

//    public Peer getPeerByHostName(String host) {
//        // Get the snapshot PeerList
//        PeerList handshakedPeers = this.getImmutableHandshakedPeers();
//
//        // Create a dummy PeerAddress object using the host part for lookup
//        // The PeerList.get(PeerAddress pa) method automatically extracts the host/IP
//        // and performs a fast map lookup.
//        PeerAddress lookupAddress = PeerAddress.fromString(host);
//
//        return handshakedPeers.get(lookupAddress);
//    }

//    public Peer getPeerByHostName(String host) {
//        String finalHost = host.split(":", 2)[0];               // If it contains :port at the end we need to strip that off
//        return this.immutableHandshakedPeers.stream()
//                .filter(p -> p.getHostName().equals(finalHost))
//                .findFirst()
//                .orElse(null);
//    }

    public Peer getPeerByPeerData(PeerData pd) {
        PeerList handshakedSnapshot = this.getImmutableHandshakedPeers();
        return handshakedSnapshot.get(pd);
    }

    public Peer getPeerByPeerAddress(PeerAddress pa) {
        PeerList handshakedSnapshot = this.getImmutableHandshakedPeers();
        return handshakedSnapshot.get(pa);
    }

    public boolean requestDataFromPeer(String peerAddressString, byte[] signature) {
        if (peerAddressString != null) {
            PeerAddress peerAddress = PeerAddress.fromString(peerAddressString);
            PeerData peerData = null;

            LOGGER.info("Requesting data using NetworkData from {}", peerAddressString);
            // Reuse an existing PeerData instance if it's already in the known peers list
            synchronized (this.allKnownPeers) {
                peerData = this.allKnownPeers.stream()
                        .filter(knownPeerData -> knownPeerData.getAddress().equals(peerAddress))
                        .findFirst()
                        .orElse(null);
            }

            // When should we get a peer we don't know about?  We get our peers from the main NetWork
            if (peerData == null) {
                // Not a known peer, so we need to create one
                Long addedWhen =  NTP.getTime();
                String addedBy = "requestDataFromPeer";
                peerData = new PeerData(peerAddress, addedWhen, addedBy);
            }

//            if (peerData == null) {
//                LOGGER.info("PeerData is null when trying to request data from peer {}", peerAddressString);
//                return false;
//            }

            // Check if we're already connected to and handshaked with this peer
//            Peer connectedPeer = this.getImmutableConnectedPeers().stream()
//                        .filter(p -> p.getPeerData().getAddress().equals(peerAddress))
//                        .findFirst()
//                        .orElse(null);

            PeerList connectedSnapshot = this.getImmutableConnectedPeers();
            Peer connectedPeer = connectedSnapshot.get(peerAddress);

            boolean isConnected = (connectedPeer != null);

            //boolean isHandshaked = this.getImmutableHandshakedPeers().stream()
            //        .anyMatch(p -> p.getPeerData().getAddress().equals(peerAddress));

            boolean isHandshaked = this.getImmutableHandshakedPeers().contains(peerAddress);

            if (isConnected && isHandshaked) {
                // Already connected
                return this.requestDataFromConnectedPeer(connectedPeer, signature);
            }
            else {
                // We need to connect to this peer before we can request data
                try {
                    if (!isConnected) {
                        // Add this signature to the list of pending requests for this peer
                        LOGGER.debug("Making connection to peer {} to request files for signature {}...", peerAddressString, Base58.encode(signature));
                        Peer peer = new Peer(peerData, Peer.NETWORKDATA);
                        peer.setIsDataPeer(true);   // This is set when we make a connection
                        peer.addPendingSignatureRequest(signature);
                        return this.connectPeer(peer);
                        // If connection (and handshake) is successful, data will automatically be requested
                    }
                    else if (!isHandshaked) {
                        LOGGER.info("Peer {} is connected but not handshaked. Not attempting a new connection.", peerAddress);
                        return false;
                    }

                } catch (InterruptedException e) {
                    LOGGER.info("Interrupted when connecting to peer {}", peerAddress);
                    return false;
                }
            }
        }
        return false;
    }

    private boolean requestDataFromConnectedPeer(Peer connectedPeer, byte[] signature) {
        if (signature == null) {  // Nothing to do
            return false;
        }
        return ArbitraryDataFileListManager.getInstance().fetchArbitraryDataFileList(connectedPeer, signature);
    }

    /**
     * Returns list of connected peers that have completed handshaking.
     */
    public PeerList getImmutableHandshakedPeers() {
        // A new PeerList is created as a snapshot every time this is called
        return new PeerList(this.handshakedPeers);
    }
    //public List<Peer> getImmutableHandshakedPeers() {
    //    return this.immutableHandshakedPeers;
    //}

    public void addHandshakedPeer(Peer peer) {
        this.handshakedPeers.add(peer); // thread safe thanks to synchronized list
        //this.immutableHandshakedPeers = List.copyOf(this.handshakedPeers); // also thread safe thanks to synchronized collection's toArray() being fed to List.of(array)

        // Also add to outbound handshaked peers cache
        if (peer.isOutbound()) {
            this.addOutboundHandshakedPeer(peer);
        }
    }

    public void removeHandshakedPeer(Peer peer) {
        this.handshakedPeers.remove(peer); // thread safe thanks to synchronized list
        //this.immutableHandshakedPeers = List.copyOf(this.handshakedPeers); // also thread safe thanks to synchronized collection's toArray() being fed to List.of(array)

        // Also remove from outbound handshaked peers cache
        if (peer.isOutbound()) {
            this.removeOutboundHandshakedPeer(peer);
        }
    }

    /**
     * Returns list of peers we connected to that have completed handshaking.
     */
    public PeerList getImmutableOutboundHandshakedPeers() {
        // A new PeerList is created as a snapshot every time this is called
        return new PeerList(this.outboundHandshakedPeers);
    }
//    public List<Peer> getImmutableOutboundHandshakedPeers() {
//        return this.immutableOutboundHandshakedPeers;
//    }

    public void addOutboundHandshakedPeer(Peer peer) {
        if (!peer.isOutbound()) {
            return;
        }
        this.outboundHandshakedPeers.add(peer); // thread safe thanks to synchronized list
        //this.immutableOutboundHandshakedPeers = List.copyOf(this.outboundHandshakedPeers); // also thread safe thanks to synchronized collection's toArray() being fed to List.of(array)
    }

    public void removeOutboundHandshakedPeer(Peer peer) {
        if (!peer.isOutbound()) {
            return;
        }
        this.outboundHandshakedPeers.remove(peer); // thread safe thanks to synchronized list
        //this.immutableOutboundHandshakedPeers = List.copyOf(this.outboundHandshakedPeers); // also thread safe thanks to synchronized collection's toArray() being fed to List.of(array)
    }

    /**
     * Returns first peer that has completed handshaking and has matching public key.
     */
    public Peer getHandshakedPeerWithPublicKey(byte[] publicKey) {
        return this.getImmutableConnectedPeers().stream()
                .filter(peer -> peer.getHandshakeStatus() == Handshake.COMPLETED
                        && Arrays.equals(peer.getPeersPublicKey(), publicKey))
                .findFirst().orElse(null);
    }

    // Peer list filters

    /**
     * Must be inside <tt>synchronized (this.selfPeers) {...}</tt>
     */
    private final Predicate<PeerData> isSelfPeer = peerData -> {
        PeerAddress peerAddress = peerData.getAddress();
        return this.selfPeers.stream().anyMatch(selfPeer -> selfPeer.equals(peerAddress));
    };

    private final Predicate<PeerData> isConnectedPeer = peerData -> {
        PeerAddress peerAddress = peerData.getAddress();
        return this.getImmutableConnectedPeers().stream().anyMatch(peer -> peer.getPeerData().getAddress().equals(peerAddress));
    };

    private final Predicate<PeerData> isResolvedAsConnectedPeer = peerData -> {
        try {
            InetSocketAddress resolvedSocketAddress = peerData.getAddress().toSocketAddress();
            return this.getImmutableConnectedPeers().stream()
                    .anyMatch(peer -> peer.getResolvedAddress().equals(resolvedSocketAddress));
        } catch (UnknownHostException e) {
            // Can't resolve - no point even trying to connect
            return true;
        }
    };

    // Main thread

    class NetworkDataProcessor extends ExecuteProduceConsume {

        private final Logger LOGGER = LogManager.getLogger(NetworkDataProcessor.class);

        private final AtomicLong nextConnectTaskTimestamp = new AtomicLong(0L); // ms - try first connect once NTP syncs
       // private final AtomicLong nextBroadcastTimestamp = new AtomicLong(0L); // ms - try first broadcast once NTP syncs

        private Iterator<SelectionKey> channelIterator = null;

        NetworkDataProcessor(ExecutorService executor) {
            super(executor);
        }

        @Override
        protected void onSpawnFailure() {
            // For debugging:
            // ExecutorDumper.dump(this.executor, 3, ExecuteProduceConsume.class);
        }

        @Override
        protected Task produceTask(boolean canBlock) throws InterruptedException {
            Task task;

            task = maybeProducePeerMessageTask();
            if (task != null) {
                return task;
            }

            final Long now = NTP.getTime();

            // If it's a new peer we need to connect it on the data port
            task = maybeProduceConnectPeerTask(now);
            if (task != null) {
                LOGGER.info("Attempting Connect Peer Task");
                return task;
            }

            // Only this method can block to reduce CPU spin
            return maybeProduceChannelTask(canBlock);
        }

        private Task maybeProducePeerMessageTask() {
            return getImmutableConnectedPeers().stream()
                    .map(peer -> peer.getMessageTask(Peer.NETWORKDATA))
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }

        private Task maybeProduceConnectPeerTask(Long now) throws InterruptedException {
            if(now == null) {
                LOGGER.info("Now is null :(");
                return null;
            }
            if (now < nextConnectTaskTimestamp.get()) {
                //LOGGER.info("Not going to try and connect, {} < {}", now, nextConnectTaskTimestamp.get());
                return null;
            }

            if (getImmutableOutboundHandshakedPeers().size() >= minOutboundPeers) {
                LOGGER.info("Not going to try to connect, .size() >= {}", minOutboundPeers);
                return null;
            }

            nextConnectTaskTimestamp.set(now + 3000L); // change from 1s to 3s, don't need to get data peers so aggressively

            Peer targetPeer = getConnectablePeer(now);
            if (targetPeer == null) {
                return null;
            }
            targetPeer.setPeerType(Peer.NETWORKDATA);      // Make sure we set this to a NetworkData Type

            LOGGER.info("Time to connect a Peer");
            // Create connection task
            return new PeerConnectTask(targetPeer);
        }

        private Task maybeProduceChannelTask(boolean canBlock) throws InterruptedException {
            // Synchronization here to enforce thread-safety on channelIterator
            synchronized (channelSelector) {
                // anything to do?
                if (channelIterator == null) {
                    try {
                        if (canBlock) {
                            channelSelector.select(1000L);
                        } else {
                            channelSelector.selectNow();
                        }
                    } catch (IOException e) {
                        LOGGER.warn("Channel selection threw IOException: {}", e.getMessage());
                        return null;
                    }

                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException();
                    }

                    channelIterator = channelSelector.selectedKeys().iterator();
                    LOGGER.trace("Thread {}, after {} select, channelIterator now {}",
                            Thread.currentThread().getId(),
                            canBlock ? "blocking": "non-blocking",
                            channelIterator);
                }

                if (!channelIterator.hasNext()) {
                    channelIterator = null; // Nothing to do so reset iterator to cause new select

                    LOGGER.trace("Thread {}, channelIterator now null", Thread.currentThread().getId());
                    return null;
                }

                final SelectionKey nextSelectionKey = channelIterator.next();
                channelIterator.remove();

                // Just in case underlying socket channel already closed elsewhere, etc.
                if (!nextSelectionKey.isValid())
                    return null;

                LOGGER.trace("Thread {}, nextSelectionKey {}", Thread.currentThread().getId(), nextSelectionKey);

                SelectableChannel socketChannel = nextSelectionKey.channel();

                try {
                    if (nextSelectionKey.isReadable()) {
                        clearInterestOps(nextSelectionKey, SelectionKey.OP_READ);
                        //LOGGER.info("Selector is Read");
                        Peer peer = getPeerFromChannel((SocketChannel) socketChannel);
                        if (peer == null)
                            return null;
                        return new ChannelReadTask((SocketChannel) socketChannel, peer);
                    }

                    if (nextSelectionKey.isWritable()) {
                        clearInterestOps(nextSelectionKey, SelectionKey.OP_WRITE);

                        Peer peer = getPeerFromChannel((SocketChannel) socketChannel);
                        // Next two lines might be an options

                        //Peer peer = getPeerFromIP((SocketChannel) socketChannel);
                        //socketChannel = peer.getSocketChannel();

                        if (peer == null)
                            return null;

                        // Any thread that queues a message to send can set OP_WRITE,
                        // but we only allow one pending/active ChannelWriteTask per Peer
                        if (!channelsPendingWrite.add(socketChannel))
                            return null;

                        return new ChannelWriteTask((SocketChannel) socketChannel, peer);
                    }

                    if (nextSelectionKey.isAcceptable()) {
                        clearInterestOps(nextSelectionKey, SelectionKey.OP_ACCEPT);
                        if (getImmutableConnectedPeers().size() >= getMaxPeers())
                                return null;

                        // Need to pass in a reference to the network type
                        return new ChannelAcceptTask((ServerSocketChannel) socketChannel, Peer.NETWORKDATA);
                    }
                } catch (CancelledKeyException e) {
                    /*
                     * Sometimes nextSelectionKey is cancelled / becomes invalid between the isValid() test at line 586
                     * and later calls to isReadable() / isWritable() / isAcceptable() which themselves call isValid()!
                     * Those isXXXable() calls could throw CancelledKeyException, so we catch it here and return null.
                     */
                    return null;
                }
            }

            return null;
        }
    }

    private Peer getConnectablePeer(final Long now) throws InterruptedException {

            if(getAllKnownPeers().isEmpty()) {
                return null;
            }
        LOGGER.info("ConnectedPeers: {}, Handshaked Peers: {} ", getImmutableConnectedPeers().size(), getImmutableHandshakedPeers().size());
        LOGGER.info("Out External IP is: {}", Network.getInstance().getOurExternalIpAddress());
        // Find an address to connect to
            List<PeerData> peers = this.getAllKnownPeers();

            // Don't consider peers with recent connection failures
            final long lastAttemptedThreshold = now - CONNECT_FAILURE_BACKOFF;

            // @ToDo: What does this filter parse out?
        // ( ) || ( )
//            peers.removeIf(peerData -> peerData.getLastAttempted() != null
//                    && (peerData.getLastConnected() == null
//                    || peerData.getLastConnected() < peerData.getLastAttempted())
//                    && peerData.getLastAttempted() > lastAttemptedThreshold);
            // 1st Part of filter commented out
        peers.removeIf(peerData -> peerData.getLastAttempted() != null
                && (peerData.getLastConnected() == null ));

        //LOGGER.info("Size is: {} after first filter", peers.size());

        // 2nd Part of filter commented block
        //for (PeerData pd : peers ) {
            //LOGGER.info(" {} < {} : true?", pd.getLastConnected(), pd.getLastAttempted());
            //LOGGER.info(" {} > {} : true?", pd.getLastAttempted(), lastAttemptedThreshold);
        //}
        // Ah this might be because we pasesd the data from Network, and it does not have initial values!

        peers.removeIf(peerData ->
                peerData.getLastConnected() < peerData.getLastAttempted()
                && peerData.getLastAttempted() > lastAttemptedThreshold);


            // Don't consider peers that we know loop back to self
            synchronized (this.selfPeers) {
                peers.removeIf(isSelfPeer);
            }

            // Don't consider already connected peers (simple address match)
            peers.removeIf(isConnectedPeer);

            // Don't consider already connected peers (resolved address match)
            // Disabled because this might be too slow if we end up waiting a long time for hostnames to resolve via DNS
            // Which is ok because duplicate connections to the same peer are handled during handshaking
            // peers.removeIf(isResolvedAsConnectedPeer);

            this.checkLongestConnection(now);

            // Any left?
            if (peers.isEmpty()) {
                //LOGGER.info("Peers List is empty after filters!");
                return null;
            }

            // Pick random peer
            int peerIndex = new Random().nextInt(peers.size());

            // Pick candidate
            PeerData peerData = peers.get(peerIndex);
            Peer newPeer = new Peer(peerData, Peer.NETWORKDATA);
            newPeer.setIsDataPeer(true);

            // Update connection attempt info
            peerData.setLastAttempted(now);
            return newPeer;
    }

    public boolean connectPeer(Peer newPeer) throws InterruptedException {
        // Also checked before creating PeerConnectTask
        if (getImmutableOutboundHandshakedPeers().size() >= minOutboundPeers) {
            LOGGER.info("To Many Peers size() > {} ", minOutboundPeers);
            return false;
        }

        SocketChannel socketChannel = newPeer.connect(Peer.NETWORKDATA);
        if (socketChannel == null) {
            LOGGER.info("socketChannel == null");
            return false;
        }

        if (Thread.currentThread().isInterrupted()) {
            LOGGER.info("Thread is interuppted");
            return false;
        }

        this.addConnectedPeer(newPeer);
        this.onPeerReady(newPeer);

        return true;
    }

    /* Same as connectPeer except it ignores the max peer count */
    public boolean forceConnectPeer(Peer newPeer) {

        SocketChannel socketChannel = newPeer.connect(Peer.NETWORKDATA);
        if (socketChannel == null) {
            LOGGER.info("socketChannel == null");
            return false;
        }

        if (Thread.currentThread().isInterrupted()) {
            LOGGER.info("Thread is interuppted");
            return false;
        }

        this.addConnectedPeer(newPeer);
        this.onPeerReady(newPeer);

        return true;
    }


    public Peer getPeerFromChannel(SocketChannel socketChannel) {
        //LOGGER.info("Passed SocketChannel is: {} ", socketChannel.toString());
        for (Peer peer : this.getImmutableConnectedPeers()) {
            if (peer.getSocketChannel() == socketChannel) {
                return peer;
            }
        }
        // This is failing because its matching the wrong port!!!, not looking for DataNetwork some how....
        LOGGER.info("Failed to find peer from socket: {}", socketChannel.toString());
        return null;
    }

//    public Peer getPeerFromIP(SocketChannel socketChannel)  {
//
//        try {
//            SocketAddress remoteAddress = socketChannel.getRemoteAddress();
//            InetSocketAddress inet = (InetSocketAddress) remoteAddress;
//            String ip = inet.getAddress().getHostAddress();
//
//            for (Peer peer : this.getImmutableConnectedPeers()) {
//                SocketAddress pSocketAddress = peer.getSocketChannel().getRemoteAddress();
//                InetSocketAddress pint = (InetSocketAddress) pSocketAddress;
//                String pip = pint.getAddress().getHostAddress();
//                if (pip.equals(ip)) {
//                    return peer;
//                }
//            }
//        } catch (IOException e) {
//            LOGGER.warn("FAILED to get peer from IP address");
//            return null;
//        }
//        return null;
//    }

    private void checkLongestConnection(Long now) {
        if (now == null || now < nextDisconnectionCheck) {
            return;
        }

        // Find peers that have reached their maximum connection age, and disconnect them
        List<Peer> peersToDisconnect = this.getImmutableConnectedPeers().stream()
                .filter(peer -> !peer.isSyncInProgress())
                .filter(peer -> peer.hasReachedMaxConnectionAge())
                .collect(Collectors.toList());

        if (peersToDisconnect != null && !peersToDisconnect.isEmpty()) {
            for (Peer peer : peersToDisconnect) {
                LOGGER.debug("Forcing disconnection of peer {} because connection age ({} ms) " +
                        "has reached the maximum ({} ms)", peer, peer.getConnectionAge(), peer.getMaxConnectionAge());
                peer.disconnect("Connection age too old");
            }
        }

        // Check again after a minimum fixed interval
        nextDisconnectionCheck = now + DISCONNECTION_CHECK_INTERVAL;
    }

    // SocketChannel interest-ops manipulations

    private static final String[] OP_NAMES = new String[SelectionKey.OP_ACCEPT * 2];
    static {
        for (int i = 0; i < OP_NAMES.length; i++) {
            StringJoiner joiner = new StringJoiner(",");

            if ((i & SelectionKey.OP_READ) != 0) joiner.add("OP_READ");
            if ((i & SelectionKey.OP_WRITE) != 0) joiner.add("OP_WRITE");
            if ((i & SelectionKey.OP_CONNECT) != 0) joiner.add("OP_CONNECT");
            if ((i & SelectionKey.OP_ACCEPT) != 0) joiner.add("OP_ACCEPT");

            OP_NAMES[i] = joiner.toString();
        }
    }

    public void clearInterestOps(SelectableChannel socketChannel, int interestOps) {
        SelectionKey selectionKey = socketChannel.keyFor(channelSelector);
        if (selectionKey == null)
            return;

        clearInterestOps(selectionKey, interestOps);
    }

    private void clearInterestOps(SelectionKey selectionKey, int interestOps) {
        if (!selectionKey.channel().isOpen())
            return;

        LOGGER.trace("Thread {} clearing {} interest-ops on channel: {}",
                Thread.currentThread().getId(),
                OP_NAMES[interestOps],
                selectionKey.channel());

        selectionKey.interestOpsAnd(~interestOps);
    }

    public void setInterestOps(SelectableChannel socketChannel, int interestOps) {
        SelectionKey selectionKey = socketChannel.keyFor(channelSelector);
        if (selectionKey == null) {
            try {
                selectionKey = socketChannel.register(this.channelSelector, interestOps);
            } catch (ClosedChannelException e) {
                // Channel already closed so ignore
                return;
            }
            // Fall-through to allow logging
        }

        setInterestOps(selectionKey, interestOps);
    }

    private void setInterestOps(SelectionKey selectionKey, int interestOps) {
        if (!selectionKey.channel().isOpen())
            return;

        LOGGER.trace("Thread {} setting {} interest-ops on channel: {}",
                Thread.currentThread().getId(),
                OP_NAMES[interestOps],
                selectionKey.channel());

        selectionKey.interestOpsOr(interestOps);
    }

    // Peer / Task callbacks

    public void notifyChannelNotWriting(SelectableChannel socketChannel) {
        this.channelsPendingWrite.remove(socketChannel);
    }

    protected void wakeupChannelSelector() {
        this.channelSelector.wakeup();
    }

    protected boolean verify(byte[] signature, byte[] message) {
        return Crypto.verify(this.edPublicKeyParams.getEncoded(), signature, message);
    }

    protected byte[] sign(byte[] message) {
        return Crypto.sign(this.edPrivateKeyParams, message);
    }

    protected byte[] getSharedSecret(byte[] publicKey) {
        return Crypto.getSharedSecret(this.edPrivateKeyParams.getEncoded(), publicKey);
    }

    /**
     * Called when Peer's thread has setup and is ready to process messages
     */
    public void onPeerReady(Peer peer) {
        onHandshakingMessage(peer, null, Handshake.STARTED);

    }

    public void onDisconnect(Peer peer) {
        if (peer.getConnectionEstablishedTime() > 0L) {
            LOGGER.debug("[{}] Disconnected from peer {}", peer.getPeerConnectionId(), peer);
        } else {
            LOGGER.debug("[{}] Failed to connect to peer {}", peer.getPeerConnectionId(), peer);
        }

        this.removeConnectedPeer(peer);
        this.channelsPendingWrite.remove(peer.getSocketChannel());

        if (this.isShuttingDown)
            // No need to do any further processing, like re-enabling listen socket or notifying Controller
            return;

        if (getImmutableConnectedPeers().size() < maxPeers - 1
                && serverSelectionKey.isValid()
                && (serverSelectionKey.interestOps() & SelectionKey.OP_ACCEPT) == 0) {
            try {
                LOGGER.debug("Re-enabling accepting incoming connections because the server is no longer full");
                setInterestOps(serverSelectionKey, SelectionKey.OP_ACCEPT);
            } catch (CancelledKeyException e) {
                LOGGER.error("Failed to re-enable accepting of incoming connections: {}", e.getMessage());
            }
        }

        // Notify Controller
        Controller.getInstance().onPeerDisconnect(peer);
    }

    public void peerMisbehaved(Peer peer) {
        PeerData peerData = peer.getPeerData();
        peerData.setLastMisbehaved(NTP.getTime());

        // Only update repository if outbound peer
        if (peer.isOutbound()) {
            try (Repository repository = RepositoryManager.getRepository()) {
                synchronized (this.allKnownPeers) {
                    repository.getNetworkRepository().save(peerData);
                    repository.saveChanges();
                }
            } catch (DataException e) {
                LOGGER.warn("Repository issue while updating peer synchronization info", e);
            }
        }
    }

    /**
     * Called when a new message arrives for a peer. message can be null if called after connection
     */
    public void onMessage(Peer peer, Message message) {
        if (message != null) {
            LOGGER.trace("[{}} Processing {} message with ID {} from peer {}", peer.getPeerConnectionId(),
                    message.getType().name(), message.getId(), peer);
        }

        Handshake handshakeStatus = peer.getHandshakeStatus();
        if (handshakeStatus != Handshake.COMPLETED) {
            LOGGER.trace("Calling onHandShakingMessage : {} : on {}", handshakeStatus.toString(), peer.getPeerType());
            onHandshakingMessage(peer, message, handshakeStatus);
            return;
        }

        // Should be non-handshaking messages from now on

        // Limit threads per message type and discard if there are already too many
        Integer maxThreadsForMessageType = Settings.getInstance().getMaxThreadsForMessageType(message.getType());
        if (maxThreadsForMessageType != null) {
            Integer threadCount = threadsPerMessageType.get(message.getType());
            if (threadCount != null && threadCount >= maxThreadsForMessageType) {
                LOGGER.warn("WOULD HAVE Discarding {} message as there are already {} active threads", message.getType().name(), threadCount);
                //return;  //@ToDo : Hack around to bypass thread counting
            }
        }

        // Warn if necessary
        if (threadCountPerMessageTypeWarningThreshold != null) {
            Integer threadCount = threadsPerMessageType.get(message.getType());
            if (threadCount != null && threadCount > threadCountPerMessageTypeWarningThreshold) {
                LOGGER.info("Warning: high thread count for {} message type: {}", message.getType().name(), threadCount);
            }
        }

        // Add to per-message thread count (first initializing to 0 if not already present)
        threadsPerMessageType.computeIfAbsent(message.getType(), key -> 0);
        threadsPerMessageType.computeIfPresent(message.getType(), (key, value) -> value + 1);
        
        // Add to total thread count
        synchronized (this) {
            totalThreadCount++;

            if (totalThreadCount >= threadCountWarningThreshold) {
                LOGGER.info("Warning: high total thread count: {} / {}", totalThreadCount, Settings.getInstance().getMaxNetworkThreadPoolSize());
            }
        }

        // Ordered by message type value
        switch (message.getType()) {

            case HELLO:
            case CHALLENGE:
            case RESPONSE:
                LOGGER.debug("[{}] Unexpected handshaking message {} from peer {}", peer.getPeerConnectionId(),
                        message.getType().name(), peer);
                peer.disconnect("unexpected handshaking message");
                return;

            case PEER_RELAY_DATA:
                // This is a peer we are passed that has files we want from another peer
                PeerRelayDataMessage prdm = (PeerRelayDataMessage) message;
                PeerAddress pa = prdm.getPeerAddress();
                byte[] hash = prdm.getHash();
                requestDataFromPeer(pa.toString(), hash);
                break;
            case ARBITRARY_DATA_FILE:
                LOGGER.info("Processing ArbitraryDataFile Message");
                ArbitraryDataFileMessage adfm = (ArbitraryDataFileMessage) message;
                int msgId = adfm.getId();
                ArbitraryDataFile adf = adfm.getArbitraryDataFile();

                // Peer has the replyQueue
                if (peer.isExpectingMessage(msgId)) { // If we knew this was coming in
                    LOGGER.info("We were expecting: {}", msgId);
                }

                // @ToDo: See if we can move this up into the if above
                ArbitraryDataFileManager.getInstance().receivedArbitraryDataFile(peer, adf);
                break;
            default:
                // Bump up to controller for possible action
                Controller.getInstance().onNetworkMessage(peer, message);
                break;
        }

        // Remove from per-message thread count (first initializing to 0 if not already present)
        threadsPerMessageType.computeIfAbsent(message.getType(), key -> 0);
        threadsPerMessageType.computeIfPresent(message.getType(), (key, value) -> value - 1);

        // Remove from total thread count
        synchronized (this) {
            totalThreadCount--;
        }
    }

    private void onHandshakingMessage(Peer peer, Message message, Handshake handshakeStatus) {
        try {
            // Still handshaking
            LOGGER.trace("[NetworkData: {}] Handshake status {}, message {} from peer {}", peer.getPeerConnectionId(),
                    handshakeStatus.name(), (message != null ? message.getType().name() : "null"), peer);

            // Check message type is as expected
            if (handshakeStatus.expectedMessageType != null
                    && message.getType() != handshakeStatus.expectedMessageType) {
                LOGGER.warn("[{}] Unexpected {} message from {}, expected {}", peer.getPeerConnectionId(),
                        message.getType().name(), peer, handshakeStatus.expectedMessageType);
                peer.disconnect("unexpected message");
                return;
            }

            Handshake newHandshakeStatus = handshakeStatus.onMessage(peer, message);

            if (newHandshakeStatus == null) {
                // Handshake failure
                LOGGER.warn("[{}] Handshake failure with peer {} message {}", peer.getPeerConnectionId(), peer,
                        message.getType().name());
                peer.disconnect("handshake failure");
                return;
            }

            peer.setPeerType(Peer.NETWORKDATA); // <-- This should already have been set
            if (peer.isOutbound()) {
                // If we made outbound connection then we need to act first
                newHandshakeStatus.action(peer);
            } else {
                // We have inbound connection so we need to respond in kind with what we just received
                handshakeStatus.action(peer);
            }
            peer.setHandshakeStatus(newHandshakeStatus);

            if (newHandshakeStatus == Handshake.COMPLETED) {
                this.onHandshakeCompleted(peer);
            }
        } finally {
            peer.resetHandshakeMessagePending();
        }
    }

        // This is a peer list message in the v2 format.
    // Is this is a list of peers being sent to us?
    // Merge Peers is a list merging tool
//    private void onPeersV2Message(Peer peer, Message message) {
//        PeersV2Message peersV2Message = (PeersV2Message) message;
//
//        List<PeerAddress> peerV2Addresses = peersV2Message.getPeerAddresses();
//
//        // First entry contains remote peer's listen port but empty address.
//        int peerPort = peerV2Addresses.get(0).getPort();
//        peerV2Addresses.remove(0);
//
//        // If inbound peer, use listen port and socket address to recreate first entry
//        if (!peer.isOutbound()) {
//            String host = peer.getPeerData().getAddress().getHost();
//            PeerAddress sendingPeerAddress = PeerAddress.fromString(host + ":" + peerPort);
//            LOGGER.trace("PEERS_V2 sending peer's listen address: {}", sendingPeerAddress.toString());
//            peerV2Addresses.add(0, sendingPeerAddress);
//        }
//
//        opportunisticMergePeers(peer.toString(), peerV2Addresses);
//    }

    protected void onHandshakeCompleted(Peer peer) {
        LOGGER.debug("[{}] Handshake completed with peer {} on {}", peer.getPeerConnectionId(), peer,
                peer.getPeersVersionString());

        // Are we already connected to this peer?
        Peer existingPeer = getHandshakedPeerWithPublicKey(peer.getPeersPublicKey());
        // NOTE: actual object reference compare, not Peer.equals()
        if (existingPeer != peer) {
            LOGGER.info("[{}] We already have a connection with peer {} - discarding",
                    peer.getPeerConnectionId(), peer);
            peer.disconnect("existing connection");
            return;
        }

        // Add to handshaked peers cache
        this.addHandshakedPeer(peer);

        // Make a note that we've successfully completed handshake (and when)
        peer.getPeerData().setLastConnected(NTP.getTime());

        // @ToDo : Need to understand what this is, what are pending signatures?
        //   Should this be part of the other thread?
        // Process any pending signature requests, as this peer may have been connected for this purpose only
        List<byte[]> pendingSignatureRequests = new ArrayList<>(peer.getPendingSignatureRequests());
        if (pendingSignatureRequests != null && !pendingSignatureRequests.isEmpty()) {
            for (byte[] signature : pendingSignatureRequests) {
                this.requestDataFromConnectedPeer(peer, signature);
                peer.removePendingSignatureRequest(signature);
            }
        }

        // FUTURE: we may want to disconnect from this peer if we've finished requesting data from it


        // Only the outbound side needs to send anything (after we've received handshake-completing response).
        // (If inbound sent anything here, it's possible it could be processed out-of-order with handshake message).

        //if (peer.isOutbound()) {
//            if (!Settings.getInstance().isLite()) {
//                // Send our height / chain tip info
                //Message message = this.buildHeightOrChainTipInfo(peer);

//                if (message == null) {
//                    peer.disconnect("Couldn't build our chain tip info");
//                    return;
//                }
//
//                if (!peer.sendMessage(message)) {
//                    peer.disconnect("failed to send height / chain tip info");
//                    return;
//                }
//            }

            // Send our peers list
            // We dont need to exchange Peer Lists in NetworkData
//            Message peersMessage = this.buildPeersMessage(peer);
//            if (!peer.sendMessage(peersMessage)) {
//                peer.disconnect("failed to send peers list");
//            }
//
//            // Request their peers list
//            Message getPeersMessage = new GetPeersMessage();
//            if (!peer.sendMessage(getPeersMessage)) {
//                peer.disconnect("failed to request peers list");
//            }
        //}

        LOGGER.trace("Handshake has been completed");
        // Ask Controller if they want to do anything
        Controller.getInstance().onPeerHandshakeCompleted(peer);
    }

    // Message-building calls

    // This is not for NetworkData - Sends a list of all Peers we know about in v2 format
//    public Message buildPeersMessage(Peer peer) {
//        List<PeerData> knownPeers = this.getAllKnownPeers();
//
//        // Filter out peers that we've not connected to ever or within X milliseconds
//        Long ntpTime = NTP.getTime();
//        if (ntpTime != null) {
//        final long connectionThreshold = NTP.getTime() - RECENT_CONNECTION_THRESHOLD;
//        Predicate<PeerData> notRecentlyConnected = peerData -> {
//            final Long lastAttempted = peerData.getLastAttempted();
//            final Long lastConnected = peerData.getLastConnected();
//
//            if (lastAttempted == null || lastConnected == null) {
//                return true;
//            }
//
//            if (lastConnected < lastAttempted) {
//                return true;
//            }
//
//            return lastConnected < connectionThreshold;
//        };
//        knownPeers.removeIf(notRecentlyConnected);
//
//        List<PeerAddress> peerAddresses = new ArrayList<>();
//
//        for (PeerData peerData : knownPeers) {
//            try {
//                InetAddress address = InetAddress.getByName(peerData.getAddress().getHost());
//
//                // Don't send 'local' addresses if peer is not 'local'.
//                // e.g. don't send localhost:9084 to node4.qortal.org
//                if (!peer.isLocal() && Peer.isAddressLocal(address)) {
//                    continue;
//                }
//
//                peerAddresses.add(peerData.getAddress());
//            } catch (UnknownHostException e) {
//                // Couldn't resolve hostname to IP address so discard
//            }
//        }
//
//        // New format PEERS_V2 message that supports hostnames, IPv6 and ports
//        return new PeersV2Message(peerAddresses);
//    }


    // This is for Main only
//    public Message buildHeightOrChainTipInfo(Peer peer) {
//        if (peer.getPeersVersion() >= BlockSummariesV2Message.MINIMUM_PEER_VERSION) {
//            int latestHeight = Controller.getInstance().getChainHeight();
//
//            try (final Repository repository = RepositoryManager.getRepository()) {
//                List<BlockSummaryData> latestBlockSummaries = repository.getBlockRepository().getBlockSummaries(latestHeight - BROADCAST_CHAIN_TIP_DEPTH, latestHeight);
//                return new BlockSummariesV2Message(latestBlockSummaries);
//            } catch (DataException e) {
//                return null;
//            }
//        } else {
//            // For older peers
//            BlockData latestBlockData = Controller.getInstance().getChainTip();
//            return new HeightV2Message(latestBlockData.getHeight(), latestBlockData.getSignature(),
//                    latestBlockData.getTimestamp(), latestBlockData.getMinterPublicKey());
//        }
//    }

    // This is for main Network only
//    public void broadcastOurChain() {
//        BlockData latestBlockData = Controller.getInstance().getChainTip();
//        int latestHeight = latestBlockData.getHeight();
//
//        try (final Repository repository = RepositoryManager.getRepository()) {
//            List<BlockSummaryData> latestBlockSummaries = repository.getBlockRepository().getBlockSummaries(latestHeight - BROADCAST_CHAIN_TIP_DEPTH, latestHeight);
//            Message latestBlockSummariesMessage = new BlockSummariesV2Message(latestBlockSummaries);
//
//            // For older peers
//            Message heightMessage = new HeightV2Message(latestBlockData.getHeight(), latestBlockData.getSignature(),
//                    latestBlockData.getTimestamp(), latestBlockData.getMinterPublicKey());
//
//            NetworkData.getInstance().broadcast(broadcastPeer -> broadcastPeer.getPeersVersion() >= BlockSummariesV2Message.MINIMUM_PEER_VERSION
//                    ? latestBlockSummariesMessage
//                    : heightMessage
//            );
//        } catch (DataException e) {
//            LOGGER.warn("Couldn't broadcast our chain tip info", e);
//        }
//    }

//    public Message buildNewTransactionMessage(Peer peer, TransactionData transactionData) {
//        // In V2 we send out transaction signature only and peers can decide whether to request the full transaction
//        return new TransactionSignaturesMessage(Collections.singletonList(transactionData.getSignature()));
//    }
//
//    public Message buildGetUnconfirmedTransactionsMessage(Peer peer) {
//        return new GetUnconfirmedTransactionsMessage();
//    }


    // External IP / peerAddress tracking

    public void ourPeerAddressUpdated(String peerAddress) {
        if (peerAddress == null || peerAddress.isEmpty()) {
            return;
        }

        // Validate IP address
        String[] parts = peerAddress.split(":");
        if (parts.length != 2) {
            return;
        }
        String host = parts[0];
        
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isAnyLocalAddress() || addr.isSiteLocalAddress()) {
                // Ignore local addresses
                return;
            }
        } catch (UnknownHostException e) {
            return;
        }

        // Keep track of the port
        this.ourExternalPort = Integer.parseInt(parts[1]);

        // Add to the list
        this.ourExternalIpAddressHistory.add(host);

        // Limit to 25 entries
        while (this.ourExternalIpAddressHistory.size() > 25) {
            this.ourExternalIpAddressHistory.remove(0);
        }

        // Now take a copy of the IP address history so it can be safely iterated
        // Without this, another thread could remove an element, resulting in an exception
        List<String> ipAddressHistory = new ArrayList<>(this.ourExternalIpAddressHistory);

        // If we've had 10 consecutive matching addresses, and they're different from
        // our stored IP address value, treat it as updated.
        int consecutiveReadingsRequired = 10;
        int size = ipAddressHistory.size();
        if (size < consecutiveReadingsRequired) {
            // Need at least 10 readings
            return;
        }

        // Count the number of consecutive IP address readings
        String lastReading = null;
        int consecutiveReadings = 0;
        for (int i = size-1; i >= 0; i--) {
            String reading = ipAddressHistory.get(i);
            if (lastReading != null) {
                 if (Objects.equals(reading, lastReading)) {
                    consecutiveReadings++;
                 }
                 else {
                     consecutiveReadings = 0;
                 }
            }
            lastReading = reading;
        }

        if (consecutiveReadings >= consecutiveReadingsRequired) {
            // Last 10 readings were the same - i.e. more than one peer agreed on the new IP address...
            String ip = ipAddressHistory.get(size - 1);
            if (ip != null && !Objects.equals(ip, "null")) {
                if (!Objects.equals(ip, this.ourExternalIpAddress)) {
                    // ... and the readings were different to our current recorded value, so
                    // update our external IP address value
                    this.ourExternalIpAddress = ip;
                }
            }
        }
    }

    public String getOurExternalIpAddress() {
        // FUTURE: replace port if UPnP is active, as it will be more accurate
        return this.ourExternalIpAddress;
    }

    public String getOurExternalIpAddressAndPort() {
        String ipAddress = this.getOurExternalIpAddress();
        if (ipAddress == null) {
            return null;
        }
        return String.format("%s:%d", ipAddress, this.ourExternalPort);
    }


    // Peer-management calls

    public void noteToSelf(Peer peer) {
        LOGGER.info("[{}] No longer considering peer address {} as it connects to self",
                peer.getPeerConnectionId(), peer);

        synchronized (this.selfPeers) {
            this.selfPeers.add(peer.getPeerData().getAddress());
        }
    }

    public boolean forgetPeer(PeerAddress peerAddress) throws DataException {
//        int numDeleted;

        synchronized (this.allKnownPeers) {
            this.allKnownPeers.removeIf(peerData -> peerData.getAddress().equals(peerAddress));

//            try (Repository repository = RepositoryManager.getRepository()) {
//                numDeleted = repository.getNetworkRepository().delete(peerAddress);
//                repository.saveChanges();
//            }
        }

        disconnectPeer(peerAddress);

//        return numDeleted != 0;
        return true;
    }

    public int forgetAllPeers() throws DataException {
        int numDeleted;

        synchronized (this.allKnownPeers) {
            this.allKnownPeers.clear();

            try (Repository repository = RepositoryManager.getRepository()) {
                numDeleted = repository.getNetworkRepository().deleteAllPeers();
                repository.saveChanges();
            }
        }

        for (Peer peer : this.getImmutableConnectedPeers()) {
            peer.disconnect("to be forgotten");
        }

        return numDeleted;
    }

    private void disconnectPeer(PeerAddress peerAddress) {
        // Disconnect peer
        try {
            InetSocketAddress knownAddress = peerAddress.toSocketAddress();

            List<Peer> peers = this.getImmutableConnectedPeers().stream()
                    .filter(peer -> Peer.addressEquals(knownAddress, peer.getResolvedAddress()))
                    .collect(Collectors.toList());

            for (Peer peer : peers) {
                peer.disconnect("to be forgotten");
            }
        } catch (UnknownHostException e) {
            // Unknown host isn't going to match any of our connected peers so ignore
        }
    }

    // Network-wide calls
    public void addPeer(Peer p) {
        LOGGER.trace("Passed a newly connected peer from Network : {}", p);

        // We need the ip address only
        String remoteHost = p.getPeerData().getAddress().getHost();
        int remoteHostQDNPort = (int) p.getPeerCapability("QDN");
        // if All Known Peers  already has this host. return;
        boolean alreadyKnown = allKnownPeers.stream()
                .anyMatch(pd -> pd.getAddress().getHost().equals(remoteHost));
        if (alreadyKnown)
            return;

        // Clean out values that were passed in
        String target = remoteHost + ":" + remoteHostQDNPort;

        PeerAddress pa = PeerAddress.fromString(target);
        PeerData pd = new PeerData(
                pa,
                0L,
                0L,
                0L,
                System.currentTimeMillis(),
                "INIT");
        allKnownPeers.add(pd);
    }

    public void prunePeers() throws DataException {
        final Long now = NTP.getTime();
        if (now == null) {
            return;
        }

        // Disconnect peers that are stuck during handshake
        // Get the PeerList snapshot and create a mutable copy (ArrayList) of its contents.
        // We use .stream().collect(Collectors.toList()) to create the mutable List<Peer>.
        List<Peer> handshakePeers = this.getImmutableConnectedPeers().stream()
                .collect(Collectors.toList());

        // Disregard peers that have completed handshake or only connected recently
        handshakePeers.removeIf(peer -> peer.getHandshakeStatus() == Handshake.COMPLETED
                || peer.getConnectionTimestamp() == null || peer.getConnectionTimestamp() > now - HANDSHAKE_TIMEOUT);

        for (Peer peer : handshakePeers) {
            peer.disconnect(String.format("handshake timeout at %s", peer.getHandshakeStatus().name()));
        }

        // Prune 'old' peers from if we are over the count
        // getImmutableHandshakedPeers().size() works fine as PeerList has a size() method.
        int overCount = this.getImmutableHandshakedPeers().size() - Settings.getInstance().getMaxDataPeers();
        if (overCount > 0) { // Too Many peers we need to trim some out
            List<Peer> listDisconnectPeers = findOldPeers(overCount);
            for (Peer disconnectPeer : listDisconnectPeers) {
                disconnectPeer.disconnect("Over Max and Old");
            }
        }
    }

    /**
     * Returns the N peers with the lowest getLastQDNUse() values.
     *
     * @param num number of peers to return
     * @return list of peers with lowest getLastQDNUse()
     */
     List<Peer> findOldPeers(int num) {
        return this.getImmutableHandshakedPeers().stream()
                .sorted(Comparator.comparingLong(Peer::getLastQDNUse))
                .limit(num)
                .collect(Collectors.toList());
     }

    public void broadcast(Function<Peer, Message> peerMessageBuilder) {
        for (Peer peer : getImmutableHandshakedPeers()) {
            if (this.isShuttingDown)
                return;

            Message message = peerMessageBuilder.apply(peer);

            if (message == null) {
                continue;
            }

            LOGGER.trace("Broadcasting Message {} : {} to {} on NETWORKDATA", message.getType(), message.toString(), peer);

            if (!peer.sendMessage(message)) {
                peer.disconnect("failed to broadcast message");
            }
        }
    }

    // Shutdown

    public void shutdown() {
        this.isShuttingDown = true;

        // Close listen socket to prevent more incoming connections
        if (this.serverChannel != null && this.serverChannel.isOpen()) {
            try {
                this.serverChannel.close();
            } catch (IOException e) {
                // Not important
            }
        }

        // Stop processing threads
        try {
            if (!this.networkEPC.shutdown(5000)) {
                LOGGER.warn("Network threads failed to terminate");
            }
        } catch (InterruptedException e) {
            LOGGER.warn("Interrupted while waiting for networking threads to terminate");
        }

        // Close all peer connections
        for (Peer peer : this.getImmutableConnectedPeers()) {
            peer.shutdown();
        }
    }

}
