package org.qortal.crosschain;

import org.bitcoinj.core.NetworkParameters;

import java.util.Collection;

public class NetTBD {

    private String name;
    private long feeCeiling;
    private NetworkParameters params;
    private Collection<ElectrumX.Server> servers;
    private String genesisHash;

    public NetTBD(String name, long feeCeiling, NetworkParameters params, Collection<ElectrumX.Server> servers, String genesisHash) {
        this.name = name;
        this.feeCeiling = feeCeiling;
        this.params = params;
        this.servers = servers;
        this.genesisHash = genesisHash;
    }

    public String getName() {

        return this.name;
    }

    public long getFeeCeiling() {

        return feeCeiling;
    }

    public void setFeeCeiling(long feeCeiling) {

        this.feeCeiling = feeCeiling;
    }

    public NetworkParameters getParams() {

        return this.params;
    }

    public Collection<ElectrumX.Server> getServers() {

        return this.servers;
    }

    public String getGenesisHash() {

        return this.genesisHash;
    }
}