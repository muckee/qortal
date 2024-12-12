package org.qortal.data.account;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.Objects;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
public class BlockHeightRange {

    private int begin;

    private int end;

    public BlockHeightRange() {
    }

    public BlockHeightRange(int begin, int end) {
        this.begin = begin;
        this.end = end;
    }

    public int getBegin() {
        return begin;
    }

    public int getEnd() {
        return end;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BlockHeightRange that = (BlockHeightRange) o;
        return begin == that.begin && end == that.end;
    }

    @Override
    public int hashCode() {
        return Objects.hash(begin, end);
    }

    @Override
    public String toString() {
        return "BlockHeightRange{" +
                "begin=" + begin +
                ", end=" + end +
                '}';
    }
}
