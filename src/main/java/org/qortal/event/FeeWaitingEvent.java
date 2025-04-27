package org.qortal.event;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class FeeWaitingEvent implements Event{

    private boolean positive;

    public FeeWaitingEvent() {
    }

    public FeeWaitingEvent(boolean positive) {

        this.positive = positive;

    }

    public boolean isPositive() {
        return positive;
    }
}
