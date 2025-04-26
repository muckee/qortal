package org.qortal.event;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class FeeWaitingEvent implements Event{
    private long timestamp;
    private String address;

    public FeeWaitingEvent() {
    }

    public FeeWaitingEvent(long timestamp, String address) {
        this.timestamp = timestamp;
        this.address = address;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getAddress() {
        return address;
    }
}
