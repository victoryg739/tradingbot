package ibkr.model;

import com.ib.client.ContractDetails;
import lombok.Builder;
import lombok.ToString;

@Builder
@ToString
public class ScanData {
    private int rank;
    private  ContractDetails contractDetails;
    private String distance;
    private String benchmark;

    public String getLegsStr() {
        return legsStr;
    }

    public void setLegsStr(String legsStr) {
        this.legsStr = legsStr;
    }

    public String getProjection() {
        return projection;
    }

    public void setProjection(String projection) {
        this.projection = projection;
    }

    public String getBenchmark() {
        return benchmark;
    }

    public void setBenchmark(String benchmark) {
        this.benchmark = benchmark;
    }

    public String getDistance() {
        return distance;
    }

    public void setDistance(String distance) {
        this.distance = distance;
    }

    public ContractDetails getContractDetails() {
        return contractDetails;
    }

    public void setContractDetails(ContractDetails contractDetails) {
        this.contractDetails = contractDetails;
    }

    public int getRank() {
        return rank;
    }

    public void setRank(int rank) {
        this.rank = rank;
    }

    private String projection;
    private String legsStr;


}
