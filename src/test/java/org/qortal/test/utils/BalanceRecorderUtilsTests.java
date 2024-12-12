package org.qortal.test.utils;

import org.junit.Assert;
import org.junit.Test;
import org.qortal.data.account.AccountBalanceData;
import org.qortal.data.account.AddressAmountData;
import org.qortal.data.account.BlockHeightRange;
import org.qortal.data.account.BlockHeightRangeAddressAmounts;
import org.qortal.utils.BalanceRecorderUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

public class BalanceRecorderUtilsTests {

    @Test
    public void testNotZeroForZero() {
        boolean test = BalanceRecorderUtils.ADDRESS_AMOUNT_DATA_NOT_ZERO.test( new AddressAmountData("", 0));

        Assert.assertFalse(test);
    }

    @Test
    public void testNotZeroForPositive() {
        boolean test = BalanceRecorderUtils.ADDRESS_AMOUNT_DATA_NOT_ZERO.test(new AddressAmountData("", 1));

        Assert.assertTrue(test);
    }

    @Test
    public void testNotZeroForNegative() {
        boolean test = BalanceRecorderUtils.ADDRESS_AMOUNT_DATA_NOT_ZERO.test( new AddressAmountData("", -10));

        Assert.assertTrue(test);
    }

    @Test
    public void testAddressAmountComparatorReverseOrder() {

        BlockHeightRangeAddressAmounts addressAmounts1 = new BlockHeightRangeAddressAmounts(new BlockHeightRange(2, 3), new ArrayList<>(0));
        BlockHeightRangeAddressAmounts addressAmounts2 = new BlockHeightRangeAddressAmounts(new BlockHeightRange(1, 2), new ArrayList<>(0));

        int compare = BalanceRecorderUtils.BLOCK_HEIGHT_RANGE_ADDRESS_AMOUNTS_COMPARATOR.compare(addressAmounts1, addressAmounts2);

        Assert.assertTrue( compare > 0);
    }

    @Test
    public void testAddressAmountComparatorForwardOrder() {

        BlockHeightRangeAddressAmounts addressAmounts1 = new BlockHeightRangeAddressAmounts(new BlockHeightRange(1, 2), new ArrayList<>(0));
        BlockHeightRangeAddressAmounts addressAmounts2 = new BlockHeightRangeAddressAmounts(new BlockHeightRange(2, 3), new ArrayList<>(0));

        int compare = BalanceRecorderUtils.BLOCK_HEIGHT_RANGE_ADDRESS_AMOUNTS_COMPARATOR.compare(addressAmounts1, addressAmounts2);

        Assert.assertTrue( compare < 0 );
    }

    @Test
    public void testAddressAmountDataComparator() {

        AddressAmountData addressAmount1 = new AddressAmountData("a", 10);
        AddressAmountData addressAmount2 = new AddressAmountData("b", 20);

        int compare = BalanceRecorderUtils.ADDRESS_AMOUNT_DATA_COMPARATOR.compare(addressAmount1, addressAmount2);

        Assert.assertTrue( compare < 0);
    }

    @Test
    public void testRemoveRecordingsBelowHeightNoBalances() {

        int currentHeight = 5;
        ConcurrentHashMap<Integer, List<AccountBalanceData>> balancesByHeight = new ConcurrentHashMap<>();

        BalanceRecorderUtils.removeRecordingsBelowHeight(currentHeight, balancesByHeight);

        Assert.assertEquals(0, balancesByHeight.size());
    }

    @Test
    public void testRemoveRecordingsBelowHeightOneBalanceBelow() {
        int currentHeight = 5;

        ConcurrentHashMap<Integer, List<AccountBalanceData>> balancesByHeight = new ConcurrentHashMap<>(1);

        balancesByHeight.put(1, new ArrayList<>(0));

        Assert.assertEquals(1, balancesByHeight.size());

        BalanceRecorderUtils.removeRecordingsBelowHeight(currentHeight, balancesByHeight);

        Assert.assertEquals(0, balancesByHeight.size());
    }

    @Test
    public void testRemoveRecordingsBelowHeightOneBalanceAbove() {
        int currentHeight = 5;

        ConcurrentHashMap<Integer, List<AccountBalanceData>> balancesByHeight = new ConcurrentHashMap<>(1);

        balancesByHeight.put(10, new ArrayList<>(0));

        Assert.assertEquals(1, balancesByHeight.size());

        BalanceRecorderUtils.removeRecordingsBelowHeight(currentHeight, balancesByHeight);

        Assert.assertEquals(1, balancesByHeight.size());
    }

    @Test
    public void testBuildBalanceDynamicsOneAccountOneChange() {

        String address = "a";

        List<AccountBalanceData> balances = new ArrayList<>(1);
        balances.add(new AccountBalanceData(address, 0, 2));

        List<AccountBalanceData> priorBalances = new ArrayList<>(1);
        priorBalances.add(new AccountBalanceData(address, 0, 1));

        List<AddressAmountData> dynamics = BalanceRecorderUtils.buildBalanceDynamics(balances, priorBalances, 0);

        Assert.assertNotNull(dynamics);
        Assert.assertEquals(1, dynamics.size());

        AddressAmountData addressAmountData = dynamics.get(0);
        Assert.assertNotNull(addressAmountData);
        Assert.assertEquals(address, addressAmountData.getAddress());
        Assert.assertEquals(1, addressAmountData.getAmount());
    }

    @Test
    public void testBuildBalanceDynamicsOneAccountNoPrior() {

        String address = "a";

        List<AccountBalanceData> balances = new ArrayList<>(1);
        balances.add(new AccountBalanceData(address, 0, 2));

        List<AccountBalanceData> priorBalances = new ArrayList<>(0);

        List<AddressAmountData> dynamics = BalanceRecorderUtils.buildBalanceDynamics(balances, priorBalances, 0);

        Assert.assertNotNull(dynamics);
        Assert.assertEquals(1, dynamics.size());

        AddressAmountData addressAmountData = dynamics.get(0);
        Assert.assertNotNull(addressAmountData);
        Assert.assertEquals(address, addressAmountData.getAddress());
        Assert.assertEquals(2, addressAmountData.getAmount());
    }

    @Test
    public void testBuildBalanceDynamicsTwoAccountsNegativeValues() {

        String address1 = "a";
        String address2 = "b";

        List<AccountBalanceData> balances = new ArrayList<>(2);
        balances.add(new AccountBalanceData(address1, 0, 10_000));
        balances.add(new AccountBalanceData(address2, 0, 100));

        List<AccountBalanceData> priorBalances = new ArrayList<>(2);
        priorBalances.add(new AccountBalanceData(address2, 0, 200));
        priorBalances.add(new AccountBalanceData(address1, 0, 5000));

        List<AddressAmountData> dynamics = BalanceRecorderUtils.buildBalanceDynamics(balances, priorBalances, -100L);

        Assert.assertNotNull(dynamics);
        Assert.assertEquals(2, dynamics.size());

        Map<String, Long> amountByAddress
            = dynamics.stream()
                .collect(Collectors.toMap(dynamic -> dynamic.getAddress(), dynamic -> dynamic.getAmount()));

        Assert.assertTrue(amountByAddress.containsKey(address1));

        long amount1 = amountByAddress.get(address1);

        Assert.assertNotNull(amount1);
        Assert.assertEquals(5000L, amount1 );

        Assert.assertTrue(amountByAddress.containsKey(address2));

        long amount2 = amountByAddress.get(address2);

        Assert.assertNotNull(amount2);
        Assert.assertEquals(-100L, amount2);
    }

    @Test
    public void testBuildBalanceDynamicsForAccountNoPriorAnyAccount() {
        List<AccountBalanceData> priorBalances = new ArrayList<>(0);
        AccountBalanceData accountBalance = new AccountBalanceData("a", 0, 10);

        AddressAmountData dynamic = BalanceRecorderUtils.buildBalanceDynamicsForAccount(priorBalances, accountBalance);

        Assert.assertNotNull(dynamic);
        Assert.assertEquals(10, dynamic.getAmount());
        Assert.assertEquals("a", dynamic.getAddress());
    }

    @Test
    public void testBuildBalanceDynamicsForAccountNoPriorThisAccount() {
        List<AccountBalanceData> priorBalances = new ArrayList<>(2);
        priorBalances.add(new AccountBalanceData("b", 0, 100));

        AccountBalanceData accountBalanceData = new AccountBalanceData("a", 0, 10);

        AddressAmountData dynamic = BalanceRecorderUtils.buildBalanceDynamicsForAccount(priorBalances, accountBalanceData);

        Assert.assertNotNull(dynamic);
        Assert.assertEquals(10, dynamic.getAmount());
        Assert.assertEquals("a", dynamic.getAddress());
    }

    @Test
    public void testBuildBalanceDynamicsForAccountPriorForThisAndOthers() {
        List<AccountBalanceData> priorBalances = new ArrayList<>(2);
        priorBalances.add(new AccountBalanceData("a", 0, 100));
        priorBalances.add(new AccountBalanceData("b", 0, 200));
        priorBalances.add(new AccountBalanceData("c", 0, 300));

        AccountBalanceData accountBalance = new AccountBalanceData("b", 0, 1000);

        AddressAmountData dynamic = BalanceRecorderUtils.buildBalanceDynamicsForAccount(priorBalances, accountBalance);

        Assert.assertNotNull(dynamic);
        Assert.assertEquals(800, dynamic.getAmount());
        Assert.assertEquals("b", dynamic.getAddress());
    }

    @Test
    public void testRemoveRecordingAboveHeightOneOfTwo() {

        int currentHeight = 10;
        ConcurrentHashMap<Integer, List<AccountBalanceData>> balancesByHeight = new ConcurrentHashMap<>();

        balancesByHeight.put(3, new ArrayList<>());
        balancesByHeight.put(20, new ArrayList<>());

        Assert.assertEquals(2, balancesByHeight.size());

        BalanceRecorderUtils.removeRecordingsAboveHeight(currentHeight, balancesByHeight);

        Assert.assertEquals(1, balancesByHeight.size());
        Assert.assertTrue( balancesByHeight.containsKey(3));
    }

    @Test
    public void testPriorHeightBeforeAfter() {

        int currentHeight = 10;
        ConcurrentHashMap<Integer, List<AccountBalanceData>> balancesByHeight = new ConcurrentHashMap<>();
        balancesByHeight.put( 2, new ArrayList<>());
        balancesByHeight.put(7, new ArrayList<>());
        balancesByHeight.put(12, new ArrayList<>());

        Optional<Integer> priorHeight = BalanceRecorderUtils.getPriorHeight(currentHeight, balancesByHeight);

        Assert.assertNotNull(priorHeight);
        Assert.assertTrue(priorHeight.isPresent());
        Assert.assertEquals( 7, priorHeight.get().intValue());
    }

    @Test
    public void testPriorHeightNoPriorAfterOnly() {

        int currentHeight = 10;
        ConcurrentHashMap<Integer, List<AccountBalanceData>> balancesByHeight = new ConcurrentHashMap<>();
        balancesByHeight.put(12, new ArrayList<>());

        Optional<Integer> priorHeight = BalanceRecorderUtils.getPriorHeight(currentHeight, balancesByHeight);

        Assert.assertNotNull(priorHeight);
        Assert.assertTrue(priorHeight.isEmpty());
    }

    @Test
    public void testPriorHeightPriorOnly() {

        int currentHeight = 10;

        ConcurrentHashMap<Integer, List<AccountBalanceData>> balancesByHeight = new ConcurrentHashMap<>();
        balancesByHeight.put(7, new ArrayList<>());

        Optional<Integer> priorHeight = BalanceRecorderUtils.getPriorHeight(currentHeight, balancesByHeight);

        Assert.assertNotNull(priorHeight);
        Assert.assertTrue(priorHeight.isPresent());
        Assert.assertEquals(7, priorHeight.get().intValue());
    }

    @Test
    public void testRemoveDynamicsOnOrAboveHeightOneAbove() {

        int currentHeight = 10;

        CopyOnWriteArrayList<BlockHeightRangeAddressAmounts> dynamics = new CopyOnWriteArrayList<>();

        BlockHeightRange range1 = new BlockHeightRange(10, 20);
        dynamics.add(new BlockHeightRangeAddressAmounts(range1, new ArrayList<>()));

        BlockHeightRange range2 = new BlockHeightRange(1, 4);
        dynamics.add(new BlockHeightRangeAddressAmounts(range2, new ArrayList<>()));

        Assert.assertEquals(2, dynamics.size());
        BalanceRecorderUtils.removeDynamicsOnOrAboveHeight(currentHeight, dynamics);

        Assert.assertEquals(1, dynamics.size());
        Assert.assertEquals(range2, dynamics.get(0).getRange());
    }

    @Test
    public void testRemoveDynamicsOnOrAboveOneOnOneAbove() {
        int currentHeight = 11;

        CopyOnWriteArrayList<BlockHeightRangeAddressAmounts> dynamics = new CopyOnWriteArrayList<>();

        BlockHeightRange range1 = new BlockHeightRange(1,5);
        dynamics.add(new BlockHeightRangeAddressAmounts(range1, new ArrayList<>()));

        BlockHeightRange range2 = new BlockHeightRange(6, 11);
        dynamics.add((new BlockHeightRangeAddressAmounts(range2, new ArrayList<>())));

        BlockHeightRange range3 = new BlockHeightRange(22, 16);
        dynamics.add(new BlockHeightRangeAddressAmounts(range3, new ArrayList<>()));

        Assert.assertEquals(3, dynamics.size());

        BalanceRecorderUtils.removeDynamicsOnOrAboveHeight(currentHeight, dynamics);

        Assert.assertEquals(1, dynamics.size());
        Assert.assertTrue( dynamics.get(0).getRange().equals(range1));
    }

    @Test
    public void testRemoveOldestDynamicsTwice() {
        CopyOnWriteArrayList<BlockHeightRangeAddressAmounts> dynamics = new CopyOnWriteArrayList<>();

        dynamics.add(new BlockHeightRangeAddressAmounts(new BlockHeightRange(1, 5), new ArrayList<>()));
        dynamics.add(new BlockHeightRangeAddressAmounts(new BlockHeightRange(5, 9), new ArrayList<>()));

        Assert.assertEquals(2, dynamics.size());

        BalanceRecorderUtils.removeOldestDynamics(dynamics);

        Assert.assertEquals(1, dynamics.size());
        Assert.assertTrue(dynamics.get(0).getRange().equals(new BlockHeightRange(5, 9)));

        BalanceRecorderUtils.removeOldestDynamics(dynamics);

        Assert.assertEquals(0, dynamics.size());
    }
}