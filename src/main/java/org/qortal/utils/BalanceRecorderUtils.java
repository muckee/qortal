package org.qortal.utils;

import org.qortal.data.account.AccountBalanceData;
import org.qortal.data.account.AddressAmountData;
import org.qortal.data.account.BlockHeightRange;
import org.qortal.data.account.BlockHeightRangeAddressAmounts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class BalanceRecorderUtils {

    public static final Predicate<AddressAmountData> ADDRESS_AMOUNT_DATA_NOT_ZERO = addressAmount -> addressAmount.getAmount() != 0;
    public static final Comparator<BlockHeightRangeAddressAmounts> BLOCK_HEIGHT_RANGE_ADDRESS_AMOUNTS_COMPARATOR = new Comparator<BlockHeightRangeAddressAmounts>() {
        @Override
        public int compare(BlockHeightRangeAddressAmounts amounts1, BlockHeightRangeAddressAmounts amounts2) {
            return amounts1.getRange().getEnd() - amounts2.getRange().getEnd();
        }
    };

    public static final Comparator<AddressAmountData> ADDRESS_AMOUNT_DATA_COMPARATOR = new Comparator<AddressAmountData>() {
        @Override
        public int compare(AddressAmountData addressAmountData, AddressAmountData t1) {
            if( addressAmountData.getAmount() > t1.getAmount() ) {
                return 1;
            }
            else if( addressAmountData.getAmount() < t1.getAmount() ) {
                return -1;
            }
            else {
                return 0;
            }
        }
    };

    public static final Comparator<BlockHeightRange> BLOCK_HEIGHT_RANGE_COMPARATOR = new Comparator<BlockHeightRange>() {
        @Override
        public int compare(BlockHeightRange range1, BlockHeightRange range2) {
            return range1.getEnd() - range2.getEnd();
        }
    };

    /**
     * Build Balance Dynmaics For Account
     *
     * @param priorBalances the balances prior to the current height, assuming only one balance per address
     * @param accountBalance the current balance
     *
     * @return the difference between the current balance and the prior balance for the current balance address
     */
    public static AddressAmountData buildBalanceDynamicsForAccount(List<AccountBalanceData> priorBalances, AccountBalanceData accountBalance) {
        Optional<AccountBalanceData> matchingAccountPriorBalance
            = priorBalances.stream()
                .filter(priorBalance -> accountBalance.getAddress().equals(priorBalance.getAddress()))
                .findFirst();
        if(matchingAccountPriorBalance.isPresent()) {
            return new AddressAmountData(accountBalance.getAddress(), accountBalance.getBalance() - matchingAccountPriorBalance.get().getBalance());
        }
        else {
            return new AddressAmountData(accountBalance.getAddress(), accountBalance.getBalance());
        }
    }

    public static List<AddressAmountData> buildBalanceDynamics(final List<AccountBalanceData> balances, final List<AccountBalanceData> priorBalances, long minimum) {

        List<AddressAmountData> addressAmounts = new ArrayList<>(balances.size());

        // prior balance
        addressAmounts.addAll(
            balances.stream()
                .map(balance -> buildBalanceDynamicsForAccount(priorBalances, balance))
                .filter(ADDRESS_AMOUNT_DATA_NOT_ZERO)
                .filter( data -> data.getAmount() >= minimum)
                .collect(Collectors.toList())
        );

        return addressAmounts;
    }

    public static void removeRecordingsAboveHeight(int currentHeight, ConcurrentHashMap<Integer, List<AccountBalanceData>> balancesByHeight) {
        balancesByHeight.entrySet().stream()
            .filter(heightWithBalances -> heightWithBalances.getKey() > currentHeight)
            .forEach(heightWithBalances -> balancesByHeight.remove(heightWithBalances.getKey()));
    }

    public static void removeRecordingsBelowHeight(int currentHeight, ConcurrentHashMap<Integer, List<AccountBalanceData>> balancesByHeight) {
        balancesByHeight.entrySet().stream()
            .filter(heightWithBalances -> heightWithBalances.getKey() < currentHeight)
            .forEach(heightWithBalances -> balancesByHeight.remove(heightWithBalances.getKey()));
    }

    public static void removeDynamicsOnOrAboveHeight(int currentHeight, CopyOnWriteArrayList<BlockHeightRangeAddressAmounts> balanceDynamics) {
        balanceDynamics.stream()
            .filter(addressAmounts -> addressAmounts.getRange().getEnd() >= currentHeight)
            .forEach(addressAmounts -> balanceDynamics.remove(addressAmounts));
    }

    public static BlockHeightRangeAddressAmounts removeOldestDynamics(CopyOnWriteArrayList<BlockHeightRangeAddressAmounts> balanceDynamics) {
        BlockHeightRangeAddressAmounts oldestDynamics
                = balanceDynamics.stream().sorted(BLOCK_HEIGHT_RANGE_ADDRESS_AMOUNTS_COMPARATOR).findFirst().get();

        balanceDynamics.remove(oldestDynamics);
        return oldestDynamics;
    }

    public static Optional<Integer> getPriorHeight(int currentHeight, ConcurrentHashMap<Integer, List<AccountBalanceData>> balancesByHeight) {
        Optional<Integer> priorHeight
            = balancesByHeight.keySet().stream()
                .filter(height -> height < currentHeight)
                .sorted(Comparator.reverseOrder()).findFirst();
        return priorHeight;
    }
}
