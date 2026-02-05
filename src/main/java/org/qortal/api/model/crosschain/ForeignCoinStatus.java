package org.qortal.api.model.crosschain;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Wallet and network status")
public class ForeignCoinStatus {

    @Schema(description = "Whether support is enabled")
    public boolean enabled;

    @Schema(description = "Number of connected ElectrumX servers")
    public int connectedServers;

    @Schema(description = "Number of known ElectrumX servers")
    public int knownServers;

    @Schema(description = "Current chain height")
    public int height;
    
    public ForeignCoinStatus(boolean enabled, int connectedServers, int knownservers, int height) {
        this.enabled = enabled;
        this.connectedServers = connectedServers;
        this.knownServers = knownServers;
        this.height = height;
    }
}
