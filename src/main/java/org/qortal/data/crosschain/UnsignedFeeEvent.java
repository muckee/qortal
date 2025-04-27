package org.qortal.data.crosschain;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class UnsignedFeeEvent {

    private boolean positive;

    public UnsignedFeeEvent() {
    }

    public UnsignedFeeEvent(boolean positive) {
        this.positive = positive;
    }

    public boolean isPositive() {
        return positive;
    }
}
