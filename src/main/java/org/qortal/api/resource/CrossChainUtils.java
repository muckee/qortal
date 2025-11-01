package org.qortal.api.resource;

import com.google.common.primitives.Bytes;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;

import org.bouncycastle.util.Strings;
import org.json.simple.JSONObject;
import org.qortal.api.model.crosschain.BitcoinyTBDRequest;
import org.qortal.crosschain.*;
import org.qortal.data.at.ATData;
import org.qortal.data.crosschain.*;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.utils.BitTwiddling;

import java.util.*;
import java.util.stream.Collectors;


public class CrossChainUtils {
    private static final Logger LOGGER = LogManager.getLogger(CrossChainUtils.class);
    public static final String CORE_API_CALL = "Core API Call";

    public static ServerConfigurationInfo buildServerConfigurationInfo(Bitcoiny blockchain) {

        BitcoinyBlockchainProvider blockchainProvider = blockchain.getBlockchainProvider();

        // the only reason this is called is to ensure the current server is set on the blockchain provider,
        // if there is an exception, then ignore it
        try {
            blockchainProvider.getCurrentHeight();
        } catch (ForeignBlockchainException e) {
            LOGGER.warn("Problems getting block height before building server configuration infos");
        }

        ChainableServer currentServer = blockchainProvider.getCurrentServer();

        return new ServerConfigurationInfo(
                buildInfos(blockchainProvider.getServers(), currentServer).stream()
                        .sorted(Comparator.comparing(ServerInfo::isCurrent).reversed())
                        .collect(Collectors.toList()),
                buildInfos(blockchainProvider.getRemainingServers(), currentServer),
                buildInfos(blockchainProvider.getUselessServers(), currentServer)
            );
    }

    public static ServerInfo buildInfo(ChainableServer server, boolean isCurrent) {
        return new ServerInfo(
                server.averageResponseTime(),
                server.getHostName(),
                server.getPort(),
                server.getConnectionType().toString(),
                isCurrent);

    }

    public static List<ServerInfo> buildInfos(Collection<ChainableServer> servers, ChainableServer currentServer) {

        List<ServerInfo> infos = new ArrayList<>( servers.size() );

        for( ChainableServer server : servers )
        {
            infos.add(buildInfo(server, server.equals(currentServer)));
        }

        return infos;
    }

    /**
     * Set Fee Per Kb
     *
     * @param bitcoiny the blockchain support
     * @param fee the fee in satoshis
     *
     * @return the fee if valid
     *
     * @throws IllegalArgumentException if invalid
     */
    public static String setFeePerKb(Bitcoiny bitcoiny, String fee) throws IllegalArgumentException {

        long satoshis = Long.parseLong(fee);
        if( satoshis < 0 ) throw new IllegalArgumentException("can't set fee to negative number");

        bitcoiny.setFeePerKb(Coin.valueOf(satoshis) );

        return String.valueOf(bitcoiny.getFeePerKb().value);
    }

    /**
     * Set Fee Ceiling
     *
     * @param bitcoiny the blockchain support
     * @param fee the fee in satoshis
     *
     * @return the fee if valid
     *
     * @throws IllegalArgumentException if invalid
     */
    public static String setFeeCeiling(Bitcoiny bitcoiny, String fee)  throws IllegalArgumentException{

        long satoshis = Long.parseLong(fee);
        if( satoshis < 0 ) throw new IllegalArgumentException("can't set fee to negative number");

        bitcoiny.setFeeCeiling( Long.parseLong(fee));

        return String.valueOf(bitcoiny.getFeeCeiling());
    }

    /**
     * Get P2Sh Address For AT
     *
     * @param atAddress the AT address
     * @param repository the repository
     * @param bitcoiny the blockchain data
     * @param crossChainTradeData the trade data
     *
     * @return the p2sh address for the trade, if there is one
     *
     * @throws DataException
     */
    public static Optional<String> getP2ShAddressForAT(
            String atAddress,
            Repository repository,
            Bitcoiny bitcoiny,
            CrossChainTradeData crossChainTradeData) throws DataException {

        // get the trade bot data for the AT address
        Optional<TradeBotData> tradeBotDataOptional
                = repository.getCrossChainRepository()
                .getAllTradeBotData().stream()
                .filter(data -> data.getAtAddress().equals(atAddress))
                .findFirst();

        if( tradeBotDataOptional.isEmpty() )
            return Optional.empty();

        TradeBotData tradeBotData = tradeBotDataOptional.get();

        // return the p2sh address from the trade bot
        return getP2ShFromTradeBot(bitcoiny, crossChainTradeData, tradeBotData);
    }

    /**
     * Get Foreign Trade Summaries
     *
     * @param foreignBlockchain the blockchain traded on
     * @param repository the repository
     * @param bitcoiny data for the blockchain trade on
     * @return
     * @throws DataException
     * @throws ForeignBlockchainException
     */
    public static List<TransactionSummary> getForeignTradeSummaries(
            SupportedBlockchain foreignBlockchain,
            Repository repository,
            Bitcoiny bitcoiny) throws DataException, ForeignBlockchainException {

        // get all the AT address for the given blockchain
        List<String> atAddresses
                = repository.getCrossChainRepository().getAllTradeBotData().stream()
                    .filter(data -> foreignBlockchain.name().toLowerCase().equals(data.getForeignBlockchain().toLowerCase()))
                    //.filter( data -> data.getForeignKey().equals( xpriv )) // TODO
                    .map(data -> data.getAtAddress())
                    .collect(Collectors.toList());

        List<TransactionSummary> summaries = new ArrayList<>( atAddresses.size() * 2 );

        // for each AT address, gather the data and get foreign trade summary
        for( String atAddress: atAddresses) {

            ATData atData = repository.getATRepository().fromATAddress(atAddress);

            CrossChainTradeData crossChainTradeData = foreignBlockchain.getLatestAcct().populateTradeData(repository, atData);

            Optional<String> address = getP2ShAddressForAT(atAddress,repository, bitcoiny, crossChainTradeData);

            if( address.isPresent()){
                summaries.add( getForeignTradeSummary( bitcoiny, address.get(), atAddress ) );
            }
        }

        return summaries;
    }

    /**
     * Add Server
     *
     * Add foreign blockchain server to list of candidates.
     *
     * @param bitcoiny the foreign blockchain
     * @param server the server
     *
     * @return true if the add was successful, otherwise false
     */
    public static boolean addServer(Bitcoiny bitcoiny, ChainableServer server) {

        return bitcoiny.getBlockchainProvider().addServer(server);
    }

    /**
     * Remove Server
     *
     * Remove foreign blockchain server from list of candidates.
     *
     * @param bitcoiny the foreign blockchain
     * @param server the server
     *
     * @return true if the removal was successful, otherwise false
     */
    public static boolean removeServer(Bitcoiny bitcoiny, ChainableServer server){

        return bitcoiny.getBlockchainProvider().removeServer(server);
    }

    /**
     * Set Current Server
     *
     * Set the server to use the intended foreign blockchain.
     *
     * @param bitcoiny the foreign blockchain
     * @param serverInfo the server configuration information
     *
     * @return the server connection information
     */
    public static ServerConnectionInfo setCurrentServer(Bitcoiny bitcoiny, ServerInfo serverInfo) throws ForeignBlockchainException {

        final BitcoinyBlockchainProvider blockchainProvider = bitcoiny.getBlockchainProvider();

        ChainableServer server = blockchainProvider.getServer(
                serverInfo.getHostName(),
                ChainableServer.ConnectionType.valueOf(serverInfo.getConnectionType()),
                serverInfo.getPort()
        );

        ChainableServerConnection connection = blockchainProvider.setCurrentServer(server, CORE_API_CALL).get();

        return new ServerConnectionInfo(
                new ServerInfo(
                        0,
                        serverInfo.getHostName(),
                        serverInfo.getPort(),
                        serverInfo.getConnectionType(),
                        connection.isSuccess()
                ),
                CORE_API_CALL,
                true,
                connection.isSuccess() ,
                System.currentTimeMillis(),
                connection.getNotes()
        );
    }

    /**
     * Get P2Sh From Trade Bot
     *
     * Get P2Sh address from the trade bot
     *
     * @param bitcoiny the blockchain for the trade
     * @param crossChainTradeData the cross cahin data for the trade
     * @param tradeBotData the data from the trade bot
     *
     * @return the address, original format
     */
    private static Optional<String> getP2ShFromTradeBot(
            Bitcoiny bitcoiny,
            CrossChainTradeData crossChainTradeData,
            TradeBotData tradeBotData) {

        // Pirate Chain does not support this
        if( SupportedBlockchain.PIRATECHAIN.name().equals(tradeBotData.getForeignBlockchain())) return Optional.empty();

        // need to get the trade PKH from the trade bot
        if( tradeBotData.getTradeForeignPublicKeyHash() == null ) return Optional.empty();

        // need to get the lock time from the trade bot
        if( tradeBotData.getLockTimeA() == null ) return Optional.empty();

        // need to get the creator PKH from the trade bot
        if( crossChainTradeData.creatorForeignPKH == null ) return  Optional.empty();

        // need to get the secret from the trade bot
        if( tradeBotData.getHashOfSecret() == null ) return Optional.empty();

        // if we have the necessary data from the trade bot,
        // then build the redeem script necessary to facilitate the trade
        byte[] redeemScriptBytes
                = BitcoinyHTLC.buildScript(
                    tradeBotData.getTradeForeignPublicKeyHash(),
                    tradeBotData.getLockTimeA(),
                    crossChainTradeData.creatorForeignPKH,
                    tradeBotData.getHashOfSecret()
            );


        String p2shAddress = bitcoiny.deriveP2shAddress(redeemScriptBytes);

        return Optional.of(p2shAddress);
    }

    /**
     * Get Foreign Trade Summary
     *
     * @param bitcoiny the blockchain the trade occurred on
     * @param p2shAddress the p2sh address
     * @param atAddress the AT address the p2sh address is derived from
     *
     * @return the summary
     *
     * @throws ForeignBlockchainException
     */
    public static TransactionSummary getForeignTradeSummary(Bitcoiny bitcoiny, String p2shAddress, String atAddress)
            throws ForeignBlockchainException {
        Script outputScript = ScriptBuilder.createOutputScript(
                Address.fromString(bitcoiny.getNetworkParameters(), p2shAddress));

        List<TransactionHash> hashes
                = bitcoiny.getAddressTransactions( outputScript.getProgram(), true);

        TransactionSummary summary;

        if(hashes.isEmpty()){
            summary
                    = new TransactionSummary(
                            atAddress,
                            p2shAddress,
                    "N/A",
                    "N/A",
                    0,
                    0,
                    0,
                    0,
                    "N/A",
                    0,
                    0,
                    0,
                    0);
        }
        else if( hashes.size() == 1) {
            AtomicTransactionData data = buildTransactionData(bitcoiny, hashes.get(0));
            summary = new TransactionSummary(
                    atAddress,
                    p2shAddress,
                    "N/A",
                    data.hash.txHash,
                    data.timestamp,
                    data.totalAmount,
                    getTotalInput(bitcoiny, data.inputs) - data.totalAmount,
                    data.size,
                    "N/A",
                    0,
                    0,
                    0,
                    0);
        }
        // otherwise assuming there is 2 and only 2 hashes
        else {
            List<AtomicTransactionData> atomicTransactionDataList = new ArrayList<>(2);

            // hashes -> data
            for( TransactionHash hash : hashes){
                atomicTransactionDataList.add(buildTransactionData(bitcoiny,hash));
            }

            // sort the transaction data by time
            List<AtomicTransactionData> sorted
                    = atomicTransactionDataList.stream()
                    .sorted((data1, data2) -> data1.timestamp.compareTo(data2.timestamp))
                    .collect(Collectors.toList());

            // build the summary using the first 2 transactions
            summary = buildForeignTradeSummary(atAddress, p2shAddress, sorted.get(0), sorted.get(1), bitcoiny);
        }
        return summary;
    }

    /**
     * Build Foreign Trade Summary
     *
     * @param p2shValue the p2sh address, original format
     * @param lockingTransaction the transaction lock the foreighn coin
     * @param unlockingTransaction the transaction to unlock the foreign coin
     * @param bitcoiny the blockchain the trade occurred on
     *
     * @return
     *
     * @throws ForeignBlockchainException
     */
    private static TransactionSummary buildForeignTradeSummary(
            String atAddress,
            String p2shValue,
            AtomicTransactionData lockingTransaction,
            AtomicTransactionData unlockingTransaction,
            Bitcoiny bitcoiny) throws ForeignBlockchainException {

        // get sum of the relevant inputs for each transaction
        long lockingTotalInput = getTotalInput(bitcoiny, lockingTransaction.inputs);
        long unlockingTotalInput = getTotalInput(bitcoiny, unlockingTransaction.inputs);

        // find the address that has output that matches the total input
        Optional<Map.Entry<List<String>, Long>> addressValue
                = lockingTransaction.valueByAddress.entrySet().stream()
                .filter(entry -> entry.getValue() == unlockingTotalInput).findFirst();

        // set that matching address, if found
        String p2shAddress;
        if( addressValue.isPresent() && addressValue.get().getKey().size() == 1 ){
            p2shAddress = addressValue.get().getKey().get(0);
        }
        else {
            p2shAddress = "N/A";
        }

        // build summaries with prepared values
        // the fees are the total amount subtracted by the total transaction input
        return new TransactionSummary(
                atAddress,
                p2shValue,
                p2shAddress,
                lockingTransaction.hash.txHash,
                lockingTransaction.timestamp,
                lockingTransaction.totalAmount,
                lockingTotalInput - lockingTransaction.totalAmount,
                lockingTransaction.size,
                unlockingTransaction.hash.txHash,
                unlockingTransaction.timestamp,
                unlockingTransaction.totalAmount,
                unlockingTotalInput - unlockingTransaction.totalAmount,
                unlockingTransaction.size
                );

    }

    /**
     * Build Transaction Data
     *
     * @param bitcoiny the coin for the transaction
     * @param hash the hash for the transaction
     *
     * @return the data for the transaction
     *
     * @throws ForeignBlockchainException
     */
    private static AtomicTransactionData  buildTransactionData( Bitcoiny bitcoiny, TransactionHash hash)
            throws ForeignBlockchainException {

            BitcoinyTransaction transaction = bitcoiny.getTransaction(hash.txHash);

            // destination address list -> value
            Map<List<String>, Long> valueByAddress = new HashMap<>();

            // for each output in the transaction, index by address list
            for( BitcoinyTransaction.Output output : transaction.outputs) {
                valueByAddress.put(output.addresses, output.value);
            }

            return new AtomicTransactionData(
                    hash,
                    transaction.timestamp,
                    transaction.inputs,
                    valueByAddress,
                    transaction.totalAmount,
                    transaction.size);
    }

    /**
     * Get Total Input
     *
     * Get the sum of all the inputs used in a list of inputs.
     *
     * @param bitcoiny the coin the inputs belong to
     * @param inputs the inputs
     *
     * @return the sum
     *
     * @throws ForeignBlockchainException
     */
    private static long getTotalInput(Bitcoiny bitcoiny, List<BitcoinyTransaction.Input> inputs)
            throws ForeignBlockchainException {

        long totalInputOut = 0;

        // for each input, add to total input,
        // get the indexed transaction output value and add to total value
        for( BitcoinyTransaction.Input input : inputs){

            BitcoinyTransaction inputOut = bitcoiny.getTransaction(input.outputTxHash);
            BitcoinyTransaction.Output output = inputOut.outputs.get(input.outputVout);
            totalInputOut += output.value;
        }
        return totalInputOut;
    }

    /**
     * Get Notes
     *
     * Build notes from an exception thrown.
     *
     * @param e the exception
     *
     * @return the exception message or the exception class name
     */
    public static String getNotes(Exception e) {
        return e.getMessage() + " (" + e.getClass().getSimpleName() + ")";
    }

    /**
     * Build Server Connection History
     *
     * @param bitcoiny the foreign blockchain
     *
     * @return the history of connections from latest to first
     */
    public static List<ServerConnectionInfo> buildServerConnectionHistory(Bitcoiny bitcoiny) {

        return bitcoiny.getBlockchainProvider().getServerConnections().stream()
                .sorted(Comparator.comparing(ChainableServerConnection::getCurrentTimeMillis).reversed())
                .map(
                    connection -> new ServerConnectionInfo(
                            serverToServerInfo( connection.getServer()),
                            connection.getRequestedBy(),
                            connection.isOpen(),
                            connection.isSuccess(),
                            connection.getCurrentTimeMillis(),
                            connection.getNotes()
                    )
                )
                .collect(Collectors.toList());
    }

    /**
     * Server To Server Info
     *
     * Make a server info object from a server object.
     *
     * @param server the server
     *
     * @return the server info
     */
    private static ServerInfo serverToServerInfo(ChainableServer server) {

        return new ServerInfo(
                0,
                server.getHostName(),
                server.getPort(),
                server.getConnectionType().toString(),
                false);
    }

    /**
     * Get Bitcoiny TBD (To Be Determined)
     *
     * @param bitcoinyTBDRequest the parameters for the Bitcoiny TBD
     * @return the Bitcoiny TBD
     * @throws DataException
     */
    public static BitcoinyTBD getBitcoinyTBD(BitcoinyTBDRequest bitcoinyTBDRequest) throws DataException {

        try {
            DeterminedNetworkParams networkParams = new DeterminedNetworkParams(bitcoinyTBDRequest);

            BitcoinyTBD bitcoinyTBD
                    = BitcoinyTBD.getInstance(bitcoinyTBDRequest.getCode())
                        .orElse(BitcoinyTBD.buildInstance(
                                bitcoinyTBDRequest,
                                networkParams)
                        );

            return bitcoinyTBD;
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        }

        return null;
    }

    /**
     * Get Version Decimal
     *
     * @param jsonObject the JSON object with the version attribute
     * @param attribute the attribute that hold the version value
     * @return the version as a decimal number, discarding
     * @throws NumberFormatException
     */
    public static double getVersionDecimal(JSONObject jsonObject, String attribute) throws NumberFormatException {
        String versionString = (String) jsonObject.get(attribute);
        return Double.parseDouble(reduceDelimeters(versionString, 1, '.'));
    }

    /**
     * Reduce Delimeters
     *
     * @param value the raw string
     * @param max the max number of the delimeter
     * @param delimeter the delimeter
     * @return the processed value with the max number of delimeters
     */
    public static String reduceDelimeters(String value, int max, char delimeter) {

        if( max < 1 ) return value;

        String[] splits = Strings.split(value, delimeter);

        StringBuffer buffer = new StringBuffer(splits[0]);

        int limit = Math.min(max + 1, splits.length);

        for( int index = 1; index < limit; index++) {
            buffer.append(delimeter);
            buffer.append(splits[index]);
        }

        return buffer.toString();
    }

    /** Returns


    /**
     * Build Offer Message
     *
     * @param partnerBitcoinPKH
     * @param hashOfSecretA
     * @param lockTimeA
     * @return  'offer' MESSAGE payload for trade partner to send to AT creator's trade address
     */
    public static byte[] buildOfferMessage(byte[] partnerBitcoinPKH, byte[] hashOfSecretA, int lockTimeA) {
        byte[] lockTimeABytes = BitTwiddling.toBEByteArray((long) lockTimeA);
        return Bytes.concat(partnerBitcoinPKH, hashOfSecretA, lockTimeABytes);
    }
}