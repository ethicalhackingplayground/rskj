package co.rsk.peg;

import co.rsk.bitcoinj.core.*;
import co.rsk.bitcoinj.params.RegTestParams;
import co.rsk.bitcoinj.script.Script;
import co.rsk.bitcoinj.script.ScriptBuilder;
import co.rsk.bitcoinj.store.BlockStoreException;
import co.rsk.blockchain.utils.BlockGenerator;
import co.rsk.config.BridgeConstants;
import co.rsk.config.BridgeRegTestConstants;
import co.rsk.core.BlockDifficulty;
import co.rsk.core.RskAddress;
import co.rsk.db.MutableTrieCache;
import co.rsk.db.MutableTrieImpl;
import co.rsk.peg.simples.SimpleRskTransaction;
import co.rsk.peg.utils.BridgeEventLogger;
import co.rsk.peg.utils.BridgeEventLoggerImpl;
import co.rsk.peg.whitelist.LockWhitelist;
import co.rsk.test.builders.BlockChainBuilder;
import co.rsk.trie.Trie;
import org.bouncycastle.util.encoders.Hex;
import org.ethereum.config.Constants;
import org.ethereum.config.blockchain.upgrades.ActivationConfig;
import org.ethereum.config.blockchain.upgrades.ActivationConfigsForTest;
import org.ethereum.config.blockchain.upgrades.ConsensusRule;
import org.ethereum.core.Block;
import org.ethereum.core.Repository;
import org.ethereum.core.Transaction;
import org.ethereum.crypto.ECKey;
import org.ethereum.crypto.HashUtil;
import org.ethereum.crypto.Keccak256Helper;
import org.ethereum.db.MutableRepository;
import org.ethereum.util.ByteUtil;
import org.ethereum.vm.LogInfo;
import org.ethereum.vm.PrecompiledContracts;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class BridgeSupportTest {

    public static final BlockDifficulty TEST_DIFFICULTY = new BlockDifficulty(BigInteger.ONE);

    private static final String TO_ADDRESS = "0000000000000000000000000000000000000006";
    private static final BigInteger DUST_AMOUNT = new BigInteger("1");
    private static final BigInteger NONCE = new BigInteger("0");
    private static final BigInteger GAS_PRICE = new BigInteger("100");
    private static final BigInteger GAS_LIMIT = new BigInteger("1000");
    private static final String DATA = "80af2871";
    private static final co.rsk.core.Coin LIMIT_MONETARY_BASE = new co.rsk.core.Coin(new BigInteger("21000000000000000000000000"));
    private static final RskAddress contractAddress = PrecompiledContracts.BRIDGE_ADDR;

    private BridgeConstants bridgeConstants;
    private NetworkParameters btcParams;
    private ActivationConfig.ForBlock activationsBeforeForks;

    @Before
    public void setUpOnEachTest() {
        bridgeConstants = BridgeRegTestConstants.getInstance();
        btcParams = bridgeConstants.getBtcParams();
        activationsBeforeForks = ActivationConfigsForTest.genesis().forBlock(0);
    }

    @Test
    public void activations_is_set() {
        Block block = mock(Block.class);
        BridgeConstants constants = mock(BridgeConstants.class);
        BridgeStorageProvider provider = mock(BridgeStorageProvider.class);

        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP124)).thenReturn(true);

        BridgeSupport bridgeSupport = new BridgeSupport(
                mock(BridgeConstants.class),
                provider,
                mock(BridgeEventLogger.class),
                mock(Repository.class),
                block,
                new Context(constants.getBtcParams()),
                new FederationSupport(constants, provider, block),
                mock(BtcBlockStoreWithCache.Factory.class),
                activations
        );

        Assert.assertTrue(bridgeSupport.getActivations().isActive(ConsensusRule.RSKIP124));
    }

    @Test(expected = NullPointerException.class)
    public void voteFeePerKbChange_nullFeeThrows() {
        Block block = mock(Block.class);
        BridgeStorageProvider provider = mock(BridgeStorageProvider.class);
        Transaction tx = mock(Transaction.class);
        BridgeConstants constants = mock(BridgeConstants.class);
        AddressBasedAuthorizer authorizer = mock(AddressBasedAuthorizer.class);

        when(provider.getFeePerKbElection(any()))
                .thenReturn(new ABICallElection(null));
        when(tx.getSender())
                .thenReturn(new RskAddress(ByteUtil.leftPadBytes(new byte[]{0x43}, 20)));
        when(constants.getFeePerKbChangeAuthorizer())
                .thenReturn(authorizer);
        when(authorizer.isAuthorized(tx))
                .thenReturn(true);

        BridgeSupport bridgeSupport = new BridgeSupport(
                constants,
                provider,
                mock(BridgeEventLogger.class),
                mock(Repository.class),
                block,
                new Context(constants.getBtcParams()),
                new FederationSupport(constants, provider, block),
                mock(BtcBlockStoreWithCache.Factory.class),
                mock(ActivationConfig.ForBlock.class)
        );

        bridgeSupport.voteFeePerKbChange(tx, null);
        verify(provider, never()).setFeePerKb(any());
    }

    @Test
    public void voteFeePerKbChange_unsuccessfulVote_unauthorized() {
        Block block = mock(Block.class);
        BridgeStorageProvider provider = mock(BridgeStorageProvider.class);
        Transaction tx = mock(Transaction.class);
        BridgeConstants constants = mock(BridgeConstants.class);
        AddressBasedAuthorizer authorizer = mock(AddressBasedAuthorizer.class);
        byte[] senderBytes = ByteUtil.leftPadBytes(new byte[]{0x43}, 20);

        when(provider.getFeePerKbElection(any()))
                .thenReturn(new ABICallElection(authorizer));
        when(tx.getSender())
                .thenReturn(new RskAddress(senderBytes));
        when(constants.getFeePerKbChangeAuthorizer())
                .thenReturn(authorizer);
        when(authorizer.isAuthorized(tx))
                .thenReturn(false);

        BridgeSupport bridgeSupport = new BridgeSupport(
                constants,
                provider,
                mock(BridgeEventLogger.class),
                mock(Repository.class),
                block,
                new Context(constants.getBtcParams()),
                new FederationSupport(constants, provider, block),
                mock(BtcBlockStoreWithCache.Factory.class),
                mock(ActivationConfig.ForBlock.class)
        );

        assertThat(bridgeSupport.voteFeePerKbChange(tx, Coin.CENT), is(-10));
        verify(provider, never()).setFeePerKb(any());
    }

    @Test
    public void voteFeePerKbChange_unsuccessfulVote_negativeFeePerKb() {
        Block block = mock(Block.class);
        BridgeStorageProvider provider = mock(BridgeStorageProvider.class);
        Transaction tx = mock(Transaction.class);
        BridgeConstants constants = mock(BridgeConstants.class);
        AddressBasedAuthorizer authorizer = mock(AddressBasedAuthorizer.class);
        byte[] senderBytes = ByteUtil.leftPadBytes(new byte[]{0x43}, 20);

        when(provider.getFeePerKbElection(any()))
                .thenReturn(new ABICallElection(authorizer));
        when(tx.getSender())
                .thenReturn(new RskAddress(senderBytes));
        when(constants.getFeePerKbChangeAuthorizer())
                .thenReturn(authorizer);
        when(authorizer.isAuthorized(tx))
                .thenReturn(true);
        when(authorizer.isAuthorized(tx.getSender()))
                .thenReturn(true);
        when(authorizer.getRequiredAuthorizedKeys())
                .thenReturn(2);

        BridgeSupport bridgeSupport = new BridgeSupport(
                constants,
                provider,
                mock(BridgeEventLogger.class),
                mock(Repository.class),
                block,
                new Context(constants.getBtcParams()),
                new FederationSupport(constants, provider, block),
                mock(BtcBlockStoreWithCache.Factory.class),
                mock(ActivationConfig.ForBlock.class)
        );

        assertThat(bridgeSupport.voteFeePerKbChange(tx, Coin.NEGATIVE_SATOSHI), is(-1));
        assertThat(bridgeSupport.voteFeePerKbChange(tx, Coin.ZERO), is(-1));
        verify(provider, never()).setFeePerKb(any());
    }

    @Test
    public void voteFeePerKbChange_unsuccessfulVote_excessiveFeePerKb() {
        final long MAX_FEE_PER_KB = 5_000_000L;
        Block block = mock(Block.class);
        BridgeStorageProvider provider = mock(BridgeStorageProvider.class);
        Transaction tx = mock(Transaction.class);
        BridgeConstants constants = mock(BridgeConstants.class);
        AddressBasedAuthorizer authorizer = mock(AddressBasedAuthorizer.class);
        byte[] senderBytes = ByteUtil.leftPadBytes(new byte[]{0x43}, 20);

        when(provider.getFeePerKbElection(any()))
                .thenReturn(new ABICallElection(authorizer));
        when(tx.getSender())
                .thenReturn(new RskAddress(senderBytes));
        when(constants.getFeePerKbChangeAuthorizer())
                .thenReturn(authorizer);
        when(authorizer.isAuthorized(tx))
                .thenReturn(true);
        when(authorizer.isAuthorized(tx.getSender()))
                .thenReturn(true);
        when(authorizer.getRequiredAuthorizedKeys())
                .thenReturn(2);
        when(constants.getMaxFeePerKb())
                .thenReturn(Coin.valueOf(MAX_FEE_PER_KB));

        BridgeSupport bridgeSupport = new BridgeSupport(
                constants,
                provider,
                mock(BridgeEventLogger.class),
                mock(Repository.class),
                block,
                new Context(constants.getBtcParams()),
                new FederationSupport(constants, provider, block),
                mock(BtcBlockStoreWithCache.Factory.class),
                mock(ActivationConfig.ForBlock.class)
        );

        assertThat(bridgeSupport.voteFeePerKbChange(tx, Coin.valueOf(MAX_FEE_PER_KB)), is(1));
        assertThat(bridgeSupport.voteFeePerKbChange(tx, Coin.valueOf(MAX_FEE_PER_KB + 1)), is(-2));
        verify(provider, never()).setFeePerKb(any());
    }

    @Test
    public void voteFeePerKbChange_successfulVote() {
        final long MAX_FEE_PER_KB = 5_000_000L;
        Block block = mock(Block.class);
        BridgeStorageProvider provider = mock(BridgeStorageProvider.class);
        Transaction tx = mock(Transaction.class);
        BridgeConstants constants = mock(BridgeConstants.class);
        AddressBasedAuthorizer authorizer = mock(AddressBasedAuthorizer.class);
        byte[] senderBytes = ByteUtil.leftPadBytes(new byte[]{0x43}, 20);

        when(provider.getFeePerKbElection(any()))
                .thenReturn(new ABICallElection(authorizer));
        when(tx.getSender())
                .thenReturn(new RskAddress(senderBytes));
        when(constants.getFeePerKbChangeAuthorizer())
                .thenReturn(authorizer);
        when(authorizer.isAuthorized(tx))
                .thenReturn(true);
        when(authorizer.isAuthorized(tx.getSender()))
                .thenReturn(true);
        when(authorizer.getRequiredAuthorizedKeys())
                .thenReturn(2);
        when(constants.getMaxFeePerKb())
                .thenReturn(Coin.valueOf(MAX_FEE_PER_KB));

        BridgeSupport bridgeSupport = new BridgeSupport(
                constants,
                provider,
                mock(BridgeEventLogger.class),
                mock(Repository.class),
                block,
                new Context(constants.getBtcParams()),
                new FederationSupport(constants, provider, block),
                mock(BtcBlockStoreWithCache.Factory.class),
                mock(ActivationConfig.ForBlock.class)
        );

        assertThat(bridgeSupport.voteFeePerKbChange(tx, Coin.CENT), is(1));
        verify(provider, never()).setFeePerKb(any());
    }

    @Test
    public void voteFeePerKbChange_successfulVoteWithFeeChange() {
        final long MAX_FEE_PER_KB = 5_000_000L;
        Block block = mock(Block.class);
        BridgeStorageProvider provider = mock(BridgeStorageProvider.class);
        Transaction tx = mock(Transaction.class);
        BridgeConstants constants = mock(BridgeConstants.class);
        AddressBasedAuthorizer authorizer = mock(AddressBasedAuthorizer.class);
        byte[] senderBytes = ByteUtil.leftPadBytes(new byte[]{0x43}, 20);

        when(provider.getFeePerKbElection(any()))
                .thenReturn(new ABICallElection(authorizer));
        when(tx.getSender())
                .thenReturn(new RskAddress(senderBytes));
        when(constants.getFeePerKbChangeAuthorizer())
                .thenReturn(authorizer);
        when(authorizer.isAuthorized(tx))
                .thenReturn(true);
        when(authorizer.isAuthorized(tx.getSender()))
                .thenReturn(true);
        when(authorizer.getRequiredAuthorizedKeys())
                .thenReturn(1);
        when(constants.getMaxFeePerKb())
                .thenReturn(Coin.valueOf(MAX_FEE_PER_KB));

        BridgeSupport bridgeSupport = new BridgeSupport(
                constants,
                provider,
                mock(BridgeEventLogger.class),
                mock(Repository.class),
                block,
                new Context(constants.getBtcParams()),
                new FederationSupport(constants, provider, block),
                mock(BtcBlockStoreWithCache.Factory.class),
                mock(ActivationConfig.ForBlock.class)
        );

        assertThat(bridgeSupport.voteFeePerKbChange(tx, Coin.CENT), is(1));
        verify(provider).setFeePerKb(Coin.CENT);
    }

    @Test
    public void eventLoggerLogLockBtc_before_rskip_146_activation() throws Exception {
        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP146)).thenReturn(false);

        BridgeEventLogger mockedEventLogger = mock(BridgeEventLogger.class);

        BridgeStorageProvider mockBridgeStorageProvider = mock(BridgeStorageProvider.class);
        when(mockBridgeStorageProvider.getBtcTxHashesAlreadyProcessed()).thenReturn(new HashMap<>());

        LockWhitelist lockWhitelist = mock(LockWhitelist.class);
        when(lockWhitelist.isWhitelistedFor(any(Address.class), any(Coin.class), any(int.class))).thenReturn(true);
        when(mockBridgeStorageProvider.getLockWhitelist()).thenReturn(lockWhitelist);
        when(mockBridgeStorageProvider.getNewFederation()).thenReturn(bridgeConstants.getGenesisFederation());

        Block executionBlock = mock(Block.class);
        NetworkParameters params = RegTestParams.get();
        Context btcContext = new Context(params);
        FederationSupport federationSupport = new FederationSupport(bridgeConstants, mockBridgeStorageProvider, executionBlock);
        BtcBlockStoreWithCache.Factory btcBlockStoreFactory = mock(BtcBlockStoreWithCache.Factory.class);

        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);
        when(btcBlockStoreFactory.newInstance(any(Repository.class))).thenReturn(btcBlockStore);

        // Create transaction
        Coin lockValue = Coin.COIN;
        BtcTransaction tx = new BtcTransaction(bridgeConstants.getBtcParams());
        tx.addOutput(lockValue, mockBridgeStorageProvider.getNewFederation().getAddress());
        BtcECKey srcKey = new BtcECKey();
        tx.addInput(PegTestUtils.createHash(1), 0, ScriptBuilder.createInputScript(null, srcKey));

        // Create header and PMT
        byte[] bits = new byte[1];
        bits[0] = 0x3f;
        List<Sha256Hash> hashes = new ArrayList<>();
        hashes.add(tx.getHash());
        PartialMerkleTree pmt = new PartialMerkleTree(bridgeConstants.getBtcParams(), bits, hashes, 1);
        Sha256Hash merkleRoot = pmt.getTxnHashAndMerkleRoot(new ArrayList<>());
        co.rsk.bitcoinj.core.BtcBlock btcBlock =
                new co.rsk.bitcoinj.core.BtcBlock(bridgeConstants.getBtcParams(), 1, PegTestUtils.createHash(), merkleRoot,
                        1, 1, 1, new ArrayList<>());

        int height = 1;

        mockChainOfStoredBlocks(btcBlockStore, btcBlock, height + bridgeConstants.getBtc2RskMinimumAcceptableConfirmations(), height);

        BridgeSupport bridgeSupport = new BridgeSupport(
                bridgeConstants,
                mockBridgeStorageProvider,
                mockedEventLogger,
                mock(Repository.class),
                executionBlock,
                btcContext,
                federationSupport,
                btcBlockStoreFactory,
                activations
        );

        bridgeSupport.registerBtcTransaction(mock(Transaction.class), tx.bitcoinSerialize(), height, pmt.bitcoinSerialize());

        verify(mockedEventLogger, never()).logLockBtc(any(RskAddress.class), any(BtcTransaction.class), any(Address.class), any(Coin.class));
    }

    @Test
    public void eventLoggerLogLockBtc_after_rskip_146_activation() throws Exception {
        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP146)).thenReturn(true);

        BridgeEventLogger mockedEventLogger = mock(BridgeEventLogger.class);

        BridgeStorageProvider mockBridgeStorageProvider = mock(BridgeStorageProvider.class);
        when(mockBridgeStorageProvider.getBtcTxHashesAlreadyProcessed()).thenReturn(new HashMap<>());

        LockWhitelist lockWhitelist = mock(LockWhitelist.class);
        when(lockWhitelist.isWhitelistedFor(any(Address.class), any(Coin.class), any(int.class))).thenReturn(true);
        when(mockBridgeStorageProvider.getLockWhitelist()).thenReturn(lockWhitelist);
        when(mockBridgeStorageProvider.getNewFederation()).thenReturn(bridgeConstants.getGenesisFederation());

        Block executionBlock = mock(Block.class);
        NetworkParameters params = RegTestParams.get();
        Context btcContext = new Context(params);
        FederationSupport federationSupport = new FederationSupport(bridgeConstants, mockBridgeStorageProvider, executionBlock);
        BtcBlockStoreWithCache.Factory btcBlockStoreFactory = mock(BtcBlockStoreWithCache.Factory.class);

        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);
        when(btcBlockStoreFactory.newInstance(any(Repository.class))).thenReturn(btcBlockStore);

        // Create transaction
        Coin lockValue = Coin.COIN;
        BtcTransaction tx = new BtcTransaction(bridgeConstants.getBtcParams());
        tx.addOutput(lockValue, mockBridgeStorageProvider.getNewFederation().getAddress());
        BtcECKey srcKey = new BtcECKey();
        tx.addInput(PegTestUtils.createHash(1), 0, ScriptBuilder.createInputScript(null, srcKey));

        // Create header and PMT
        byte[] bits = new byte[1];
        bits[0] = 0x3f;
        List<Sha256Hash> hashes = new ArrayList<>();
        hashes.add(tx.getHash());
        PartialMerkleTree pmt = new PartialMerkleTree(bridgeConstants.getBtcParams(), bits, hashes, 1);
        Sha256Hash merkleRoot = pmt.getTxnHashAndMerkleRoot(new ArrayList<>());
        co.rsk.bitcoinj.core.BtcBlock btcBlock =
                new co.rsk.bitcoinj.core.BtcBlock(bridgeConstants.getBtcParams(), 1, PegTestUtils.createHash(), merkleRoot,
                        1, 1, 1, new ArrayList<>());

        int height = 1;

        mockChainOfStoredBlocks(btcBlockStore, btcBlock, height + bridgeConstants.getBtc2RskMinimumAcceptableConfirmations(), height);

        BridgeSupport bridgeSupport = new BridgeSupport(
                bridgeConstants,
                mockBridgeStorageProvider,
                mockedEventLogger,
                mock(Repository.class),
                executionBlock,
                btcContext,
                federationSupport,
                btcBlockStoreFactory,
                activations
        );

        bridgeSupport.registerBtcTransaction(mock(Transaction.class), tx.bitcoinSerialize(), height, pmt.bitcoinSerialize());
        verify(mockedEventLogger, atLeastOnce()).logLockBtc(any(RskAddress.class), any(BtcTransaction.class), any(Address.class), any(Coin.class));
    }

    @Test
    public void eventLoggerLogReleaseBtcRequested_before_rskip_146_activation() throws IOException {
        BridgeEventLogger mockedEventLogger = mock(BridgeEventLogger.class);

        Repository repository = createRepository();

        Federation activeFederation = new Federation(
                FederationTestUtils.getFederationMembers(3),
                Instant.ofEpochMilli(1000),
                0L,
                NetworkParameters.fromID(NetworkParameters.ID_REGTEST)
        );

        BridgeStorageProvider provider = new BridgeStorageProvider(repository, PrecompiledContracts.BRIDGE_ADDR, bridgeConstants, activationsBeforeForks);
        UTXO utxo = new UTXO(Sha256Hash.wrap(HashUtil.randomHash()),0, Coin.COIN.multiply(2), 1, false, activeFederation.getP2SHScript());
        provider.getNewFederationBtcUTXOs().add(utxo);
        provider.setNewFederation(activeFederation);

        BridgeSupport bridgeSupport = getBridgeSupport(
                bridgeConstants, provider, repository, mockedEventLogger, mock(Block.class), null, activationsBeforeForks);

        Transaction releaseTx = new Transaction(PrecompiledContracts.BRIDGE_ADDR.toString(),
                co.rsk.core.Coin.fromBitcoin(Coin.COIN).asBigInteger(), NONCE, GAS_PRICE, GAS_LIMIT, DATA, Constants.REGTEST_CHAIN_ID);
        releaseTx.sign(new ECKey().getPrivKeyBytes());
        bridgeSupport.releaseBtc(releaseTx);

        Transaction rskTx = new Transaction(TO_ADDRESS, DUST_AMOUNT, NONCE, GAS_PRICE, GAS_LIMIT, DATA, Constants.REGTEST_CHAIN_ID);
        bridgeSupport.updateCollections(rskTx);

        verify(mockedEventLogger, never()).logReleaseBtcRequested(any(byte[].class), any(BtcTransaction.class), any(Coin.class));
    }

    @Test
    public void eventLoggerLogReleaseBtcRequested_after_rskip_146_activation() throws IOException {
        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP146)).thenReturn(true);

        BridgeEventLogger mockedEventLogger = mock(BridgeEventLogger.class);

        Repository repository = createRepository();

        Federation activeFederation = new Federation(
                FederationTestUtils.getFederationMembers(3),
                Instant.ofEpochMilli(1000),
                0L,
                NetworkParameters.fromID(NetworkParameters.ID_REGTEST)
        );

        BridgeStorageProvider provider = new BridgeStorageProvider(repository, PrecompiledContracts.BRIDGE_ADDR, bridgeConstants, activations);
        UTXO utxo = new UTXO(Sha256Hash.wrap(HashUtil.randomHash()),0, Coin.COIN.multiply(2), 1, false, activeFederation.getP2SHScript());
        provider.getNewFederationBtcUTXOs().add(utxo);
        provider.setNewFederation(activeFederation);

        BridgeSupport bridgeSupport = getBridgeSupport(
                bridgeConstants, provider, repository, mockedEventLogger, mock(Block.class), null, activations);

        Transaction releaseTx = new Transaction(PrecompiledContracts.BRIDGE_ADDR.toString(),
                co.rsk.core.Coin.fromBitcoin(Coin.COIN).asBigInteger(), NONCE, GAS_PRICE, GAS_LIMIT, DATA, Constants.REGTEST_CHAIN_ID);
        releaseTx.sign(new ECKey().getPrivKeyBytes());
        bridgeSupport.releaseBtc(releaseTx);

        Transaction rskTx = new Transaction(TO_ADDRESS, DUST_AMOUNT, NONCE, GAS_PRICE, GAS_LIMIT, DATA, Constants.REGTEST_CHAIN_ID);
        bridgeSupport.updateCollections(rskTx);

        verify(mockedEventLogger, atLeastOnce()).logReleaseBtcRequested(any(byte[].class), any(BtcTransaction.class), any(Coin.class));
    }

    @Test
    public void handmade_release_before_rskip_146_activation() throws IOException {
        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP146)).thenReturn(false);

        BridgeEventLogger eventLogger = mock(BridgeEventLogger.class);

        Repository repository = createRepository();

        Federation activeFederation = new Federation(
                FederationTestUtils.getFederationMembers(3),
                Instant.ofEpochMilli(1000),
                0L,
                NetworkParameters.fromID(NetworkParameters.ID_REGTEST)
        );

        BridgeStorageProvider provider = new BridgeStorageProvider(repository, PrecompiledContracts.BRIDGE_ADDR, bridgeConstants, activations);
        UTXO utxo = new UTXO(Sha256Hash.wrap(HashUtil.randomHash()),0, Coin.COIN.multiply(2), 1, false, activeFederation.getP2SHScript());
        provider.getNewFederationBtcUTXOs().add(utxo);
        provider.setNewFederation(activeFederation);

        BridgeSupport bridgeSupport = getBridgeSupport(
                bridgeConstants, provider, repository, eventLogger, mock(Block.class), null, activations);

        Transaction releaseTx = new Transaction(PrecompiledContracts.BRIDGE_ADDR.toString(),
                co.rsk.core.Coin.fromBitcoin(Coin.COIN).asBigInteger(), NONCE, GAS_PRICE, GAS_LIMIT, DATA, Constants.REGTEST_CHAIN_ID);
        releaseTx.sign(new ECKey().getPrivKeyBytes());
        bridgeSupport.releaseBtc(releaseTx);

        Transaction rskTx = new Transaction(TO_ADDRESS, DUST_AMOUNT, NONCE, GAS_PRICE, GAS_LIMIT, DATA, Constants.REGTEST_CHAIN_ID);
        rskTx.sign(new ECKey().getPrivKeyBytes());
        bridgeSupport.updateCollections(rskTx);

        Assert.assertEquals(1, provider.getReleaseTransactionSet().getEntries().size());
        Assert.assertEquals(0, provider.getReleaseRequestQueue().getEntries().size());

        verify(eventLogger, never()).logReleaseBtcRequested(any(byte[].class), any(BtcTransaction.class), any(Coin.class));
    }

    @Test
    public void handmade_release_after_rskip_146_activation() throws IOException {
        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP146)).thenReturn(true);

        List<LogInfo> logInfo = new ArrayList<>();
        BridgeEventLoggerImpl eventLogger = new BridgeEventLoggerImpl(bridgeConstants, activations, logInfo);

        Repository repository = createRepository();

        Federation activeFederation = new Federation(
                FederationTestUtils.getFederationMembers(3),
                Instant.ofEpochMilli(1000),
                0L,
                NetworkParameters.fromID(NetworkParameters.ID_REGTEST)
        );

        BridgeStorageProvider provider = new BridgeStorageProvider(repository, PrecompiledContracts.BRIDGE_ADDR, bridgeConstants, activations);
        UTXO utxo = new UTXO(Sha256Hash.wrap(HashUtil.randomHash()),0, Coin.COIN.multiply(2), 1, false, activeFederation.getP2SHScript());
        provider.getNewFederationBtcUTXOs().add(utxo);
        provider.setNewFederation(activeFederation);

        BridgeSupport bridgeSupport = getBridgeSupport(
                bridgeConstants, provider, repository, eventLogger, mock(Block.class), null, activations);

        Transaction releaseTx = new Transaction(PrecompiledContracts.BRIDGE_ADDR.toString(),
                co.rsk.core.Coin.fromBitcoin(Coin.COIN).asBigInteger(), NONCE, GAS_PRICE, GAS_LIMIT, DATA, Constants.REGTEST_CHAIN_ID);
        releaseTx.sign(new ECKey().getPrivKeyBytes());
        bridgeSupport.releaseBtc(releaseTx);

        Transaction rskTx = new Transaction(TO_ADDRESS, DUST_AMOUNT, NONCE, GAS_PRICE, GAS_LIMIT, DATA, Constants.REGTEST_CHAIN_ID);
        rskTx.sign(new ECKey().getPrivKeyBytes());
        bridgeSupport.updateCollections(rskTx);

        Assert.assertEquals(1, provider.getReleaseTransactionSet().getEntries().size());
        Assert.assertEquals(0, provider.getReleaseRequestQueue().getEntries().size());
        ReleaseTransactionSet.Entry entry = (ReleaseTransactionSet.Entry)provider.getReleaseTransactionSet().getEntries().toArray()[0];
        Assert.assertEquals(LogInfo.byteArrayToList(
                BridgeEvents.RELEASE_REQUESTED.getEvent().encodeEventTopics(releaseTx.getHash().getBytes(), entry.getTransaction().getHash().getBytes())),
                logInfo.get(1).getTopics());
    }

    @Test
    public void registerBtcTransactionLockTxNotWhitelisted_before_rskip_146_activation() throws BlockStoreException, AddressFormatException, IOException {
        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP146)).thenReturn(false);

        BridgeEventLogger bridgeEventLogger = mock(BridgeEventLogger.class);

        List<BtcECKey> federation1Keys = Arrays.asList(
                BtcECKey.fromPrivate(Hex.decode("fa01")),
                BtcECKey.fromPrivate(Hex.decode("fa02")));
        federation1Keys.sort(BtcECKey.PUBKEY_COMPARATOR);

        Federation federation1 = new Federation(FederationTestUtils.getFederationMembersWithBtcKeys(federation1Keys), Instant.ofEpochMilli(1000L), 0L, btcParams);

        List<BtcECKey> federation2Keys = Arrays.asList(
                BtcECKey.fromPrivate(Hex.decode("fb01")),
                BtcECKey.fromPrivate(Hex.decode("fb02")),
                BtcECKey.fromPrivate(Hex.decode("fb03")));
        federation2Keys.sort(BtcECKey.PUBKEY_COMPARATOR);

        Federation federation2 = new Federation(FederationTestUtils.getFederationMembersWithBtcKeys(federation2Keys), Instant.ofEpochMilli(2000L), 0L, btcParams);

        Repository repository = createRepository();
        repository.addBalance(PrecompiledContracts.BRIDGE_ADDR, LIMIT_MONETARY_BASE);
        Block executionBlock = mock(Block.class);
        when(executionBlock.getNumber()).thenReturn(10L);

        Repository track = repository.startTracking();

        // First transaction goes only to the first federation
        BtcTransaction tx1 = new BtcTransaction(btcParams);
        tx1.addOutput(Coin.COIN.multiply(5), federation1.getAddress());
        BtcECKey srcKey1 = new BtcECKey();
        tx1.addInput(PegTestUtils.createHash(), 0, ScriptBuilder.createInputScript(null, srcKey1));

        // Second transaction goes only to the second federation
        BtcTransaction tx2 = new BtcTransaction(btcParams);
        tx2.addOutput(Coin.COIN.multiply(10), federation2.getAddress());
        BtcECKey srcKey2 = new BtcECKey();
        tx2.addInput(PegTestUtils.createHash(), 0, ScriptBuilder.createInputScript(null, srcKey2));

        // Third transaction has one output to each federation
        // Lock is expected to be done accordingly and utxos assigned accordingly as well
        BtcTransaction tx3 = new BtcTransaction(btcParams);
        tx3.addOutput(Coin.COIN.multiply(3), federation1.getAddress());
        tx3.addOutput(Coin.COIN.multiply(4), federation2.getAddress());
        BtcECKey srcKey3 = new BtcECKey();
        tx3.addInput(PegTestUtils.createHash(), 0, ScriptBuilder.createInputScript(null, srcKey3));

        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);

        BridgeStorageProvider provider = new BridgeStorageProvider(track, contractAddress, bridgeConstants, activations);
        provider.setNewFederation(federation1);
        provider.setOldFederation(federation2);


        BtcBlockStoreWithCache.Factory mockFactory = mock(BtcBlockStoreWithCache.Factory.class);
        when(mockFactory.newInstance(track)).thenReturn(btcBlockStore);

        BridgeSupport bridgeSupport = getBridgeSupport(
                bridgeConstants, provider, track, bridgeEventLogger, executionBlock, mockFactory
        );
        byte[] bits = new byte[1];
        bits[0] = 0x3f;

        List<Sha256Hash> hashes = new ArrayList<>();
        hashes.add(tx1.getHash());
        hashes.add(tx2.getHash());
        hashes.add(tx3.getHash());
        PartialMerkleTree pmt = new PartialMerkleTree(btcParams, bits, hashes, 3);
        List<Sha256Hash> hashlist = new ArrayList<>();
        Sha256Hash merkleRoot = pmt.getTxnHashAndMerkleRoot(hashlist);

        co.rsk.bitcoinj.core.BtcBlock registerHeader = new co.rsk.bitcoinj.core.BtcBlock(btcParams, 1, PegTestUtils.createHash(), merkleRoot, 1, 1, 1, new ArrayList<BtcTransaction>());

        int height = 30;
        mockChainOfStoredBlocks(btcBlockStore, registerHeader, 35, height);

        Transaction rskTx1 = getMockedRskTxWithHash("aa");
        Transaction rskTx2 = getMockedRskTxWithHash("bb");
        Transaction rskTx3 = getMockedRskTxWithHash("cc");

        bridgeSupport.registerBtcTransaction(rskTx1, tx1.bitcoinSerialize(), height, pmt.bitcoinSerialize());
        bridgeSupport.registerBtcTransaction(rskTx2, tx2.bitcoinSerialize(), height, pmt.bitcoinSerialize());
        bridgeSupport.registerBtcTransaction(rskTx3, tx3.bitcoinSerialize(), height, pmt.bitcoinSerialize());
        bridgeSupport.save();

        track.commit();

        RskAddress srcKey1RskAddress = new RskAddress(org.ethereum.crypto.ECKey.fromPrivate(srcKey1.getPrivKey()).getAddress());
        RskAddress srcKey2RskAddress = new RskAddress(org.ethereum.crypto.ECKey.fromPrivate(srcKey2.getPrivKey()).getAddress());
        RskAddress srcKey3RskAddress = new RskAddress(org.ethereum.crypto.ECKey.fromPrivate(srcKey3.getPrivKey()).getAddress());

        Assert.assertEquals(0, repository.getBalance(srcKey1RskAddress).asBigInteger().intValue());
        Assert.assertEquals(0, repository.getBalance(srcKey2RskAddress).asBigInteger().intValue());
        Assert.assertEquals(0, repository.getBalance(srcKey3RskAddress).asBigInteger().intValue());
        Assert.assertEquals(LIMIT_MONETARY_BASE, repository.getBalance(PrecompiledContracts.BRIDGE_ADDR));

        BridgeStorageProvider provider2 = new BridgeStorageProvider(repository, PrecompiledContracts.BRIDGE_ADDR, bridgeConstants, activations);

        Assert.assertEquals(0, provider2.getNewFederationBtcUTXOs().size());
        Assert.assertEquals(0, provider2.getOldFederationBtcUTXOs().size());

        Assert.assertEquals(0, provider2.getReleaseRequestQueue().getEntries().size());
        Assert.assertEquals(3, provider2.getReleaseTransactionSet().getEntriesWithoutHash().size());
        Assert.assertEquals(0, provider2.getReleaseTransactionSet().getEntriesWithHash().size());

        List<BtcTransaction> releaseTxs = provider2.getReleaseTransactionSet().getEntries()
                .stream()
                .map(ReleaseTransactionSet.Entry::getTransaction)
                .sorted(Comparator.comparing(BtcTransaction::getOutputSum))
                .collect(Collectors.toList());

        // First release tx should correspond to the 5 BTC lock tx
        BtcTransaction releaseTx = releaseTxs.get(0);
        Assert.assertEquals(1, releaseTx.getOutputs().size());
        Assert.assertThat(Coin.COIN.multiply(5).subtract(releaseTx.getOutput(0).getValue()), is(lessThanOrEqualTo(Coin.MILLICOIN)));
        Assert.assertEquals(srcKey1.toAddress(btcParams), releaseTx.getOutput(0).getAddressFromP2PKHScript(btcParams));
        Assert.assertEquals(1, releaseTx.getInputs().size());
        Assert.assertEquals(tx1.getHash(), releaseTx.getInput(0).getOutpoint().getHash());
        Assert.assertEquals(0, releaseTx.getInput(0).getOutpoint().getIndex());

        // Second release tx should correspond to the 7 (3+4) BTC lock tx
        releaseTx = releaseTxs.get(1);
        Assert.assertEquals(1, releaseTx.getOutputs().size());
        Assert.assertThat(Coin.COIN.multiply(7).subtract(releaseTx.getOutput(0).getValue()), is(lessThanOrEqualTo(Coin.MILLICOIN)));
        Assert.assertEquals(srcKey3.toAddress(btcParams), releaseTx.getOutput(0).getAddressFromP2PKHScript(btcParams));
        Assert.assertEquals(2, releaseTx.getInputs().size());
        List<TransactionOutPoint> releaseOutpoints = releaseTx.getInputs().stream().map(TransactionInput::getOutpoint).sorted(Comparator.comparing(TransactionOutPoint::getIndex)).collect(Collectors.toList());
        Assert.assertEquals(tx3.getHash(), releaseOutpoints.get(0).getHash());
        Assert.assertEquals(tx3.getHash(), releaseOutpoints.get(1).getHash());
        Assert.assertEquals(0, releaseOutpoints.get(0).getIndex());
        Assert.assertEquals(1, releaseOutpoints.get(1).getIndex());

        // Third release tx should correspond to the 10 BTC lock tx
        releaseTx = releaseTxs.get(2);
        Assert.assertEquals(1, releaseTx.getOutputs().size());
        Assert.assertThat(Coin.COIN.multiply(10).subtract(releaseTx.getOutput(0).getValue()), is(lessThanOrEqualTo(Coin.MILLICOIN)));
        Assert.assertEquals(srcKey2.toAddress(btcParams), releaseTx.getOutput(0).getAddressFromP2PKHScript(btcParams));
        Assert.assertEquals(1, releaseTx.getInputs().size());
        Assert.assertEquals(tx2.getHash(), releaseTx.getInput(0).getOutpoint().getHash());
        Assert.assertEquals(0, releaseTx.getInput(0).getOutpoint().getIndex());

        Assert.assertTrue(provider2.getRskTxsWaitingForSignatures().isEmpty());
        Assert.assertEquals(3, provider2.getBtcTxHashesAlreadyProcessed().size());

        verify(bridgeEventLogger, never()).logReleaseBtcRequested(any(byte[].class), any(BtcTransaction.class), any(Coin.class));
    }

    @Test
    public void registerBtcTransactionLockTxNotWhitelisted_after_rskip_146_activation() throws BlockStoreException, AddressFormatException, IOException {
        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP146)).thenReturn(true);

        BridgeEventLogger bridgeEventLogger = mock(BridgeEventLogger.class);

        List<BtcECKey> federation1Keys = Arrays.asList(
                BtcECKey.fromPrivate(Hex.decode("fa01")),
                BtcECKey.fromPrivate(Hex.decode("fa02")));
        federation1Keys.sort(BtcECKey.PUBKEY_COMPARATOR);

        Federation federation1 = new Federation(FederationTestUtils.getFederationMembersWithBtcKeys(federation1Keys), Instant.ofEpochMilli(1000L), 0L, btcParams);

        List<BtcECKey> federation2Keys = Arrays.asList(
                BtcECKey.fromPrivate(Hex.decode("fb01")),
                BtcECKey.fromPrivate(Hex.decode("fb02")),
                BtcECKey.fromPrivate(Hex.decode("fb03")));
        federation2Keys.sort(BtcECKey.PUBKEY_COMPARATOR);

        Federation federation2 = new Federation(FederationTestUtils.getFederationMembersWithBtcKeys(federation2Keys), Instant.ofEpochMilli(2000L), 0L, btcParams);

        Repository repository = createRepository();
        repository.addBalance(PrecompiledContracts.BRIDGE_ADDR, LIMIT_MONETARY_BASE);
        Block executionBlock = mock(Block.class);
        when(executionBlock.getNumber()).thenReturn(10L);

        Repository track = repository.startTracking();

        // First transaction goes only to the first federation
        BtcTransaction tx1 = new BtcTransaction(btcParams);
        tx1.addOutput(Coin.COIN.multiply(5), federation1.getAddress());
        BtcECKey srcKey1 = new BtcECKey();
        tx1.addInput(PegTestUtils.createHash(), 0, ScriptBuilder.createInputScript(null, srcKey1));

        // Second transaction goes only to the second federation
        BtcTransaction tx2 = new BtcTransaction(btcParams);
        tx2.addOutput(Coin.COIN.multiply(10), federation2.getAddress());
        BtcECKey srcKey2 = new BtcECKey();
        tx2.addInput(PegTestUtils.createHash(), 0, ScriptBuilder.createInputScript(null, srcKey2));

        // Third transaction has one output to each federation
        // Lock is expected to be done accordingly and utxos assigned accordingly as well
        BtcTransaction tx3 = new BtcTransaction(btcParams);
        tx3.addOutput(Coin.COIN.multiply(3), federation1.getAddress());
        tx3.addOutput(Coin.COIN.multiply(4), federation2.getAddress());
        BtcECKey srcKey3 = new BtcECKey();
        tx3.addInput(PegTestUtils.createHash(), 0, ScriptBuilder.createInputScript(null, srcKey3));

        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);

        BridgeStorageProvider provider = new BridgeStorageProvider(track, contractAddress, bridgeConstants, activations);
        provider.setNewFederation(federation1);
        provider.setOldFederation(federation2);

        BtcBlockStoreWithCache.Factory mockFactory = mock(BtcBlockStoreWithCache.Factory.class);
        when(mockFactory.newInstance(track)).thenReturn(btcBlockStore);

        BridgeSupport bridgeSupport = getBridgeSupport(
                bridgeConstants, provider, track, bridgeEventLogger, executionBlock, mockFactory, activations
        );
        byte[] bits = new byte[1];
        bits[0] = 0x3f;

        List<Sha256Hash> hashes = new ArrayList<>();
        hashes.add(tx1.getHash());
        hashes.add(tx2.getHash());
        hashes.add(tx3.getHash());
        PartialMerkleTree pmt = new PartialMerkleTree(btcParams, bits, hashes, 3);
        List<Sha256Hash> hashlist = new ArrayList<>();
        Sha256Hash merkleRoot = pmt.getTxnHashAndMerkleRoot(hashlist);

        co.rsk.bitcoinj.core.BtcBlock registerHeader = new co.rsk.bitcoinj.core.BtcBlock(btcParams, 1, PegTestUtils.createHash(), merkleRoot, 1, 1, 1, new ArrayList<BtcTransaction>());

        int height = 30;
        mockChainOfStoredBlocks(btcBlockStore, registerHeader, 35, height);

        Transaction rskTx1 = getMockedRskTxWithHash("aa");
        Transaction rskTx2 = getMockedRskTxWithHash("bb");
        Transaction rskTx3 = getMockedRskTxWithHash("cc");

        bridgeSupport.registerBtcTransaction(rskTx1, tx1.bitcoinSerialize(), height, pmt.bitcoinSerialize());
        bridgeSupport.registerBtcTransaction(rskTx2, tx2.bitcoinSerialize(), height, pmt.bitcoinSerialize());
        bridgeSupport.registerBtcTransaction(rskTx3, tx3.bitcoinSerialize(), height, pmt.bitcoinSerialize());
        bridgeSupport.save();

        track.commit();

        RskAddress srcKey1RskAddress = new RskAddress(org.ethereum.crypto.ECKey.fromPrivate(srcKey1.getPrivKey()).getAddress());
        RskAddress srcKey2RskAddress = new RskAddress(org.ethereum.crypto.ECKey.fromPrivate(srcKey2.getPrivKey()).getAddress());
        RskAddress srcKey3RskAddress = new RskAddress(org.ethereum.crypto.ECKey.fromPrivate(srcKey3.getPrivKey()).getAddress());

        Assert.assertEquals(0, repository.getBalance(srcKey1RskAddress).asBigInteger().intValue());
        Assert.assertEquals(0, repository.getBalance(srcKey2RskAddress).asBigInteger().intValue());
        Assert.assertEquals(0, repository.getBalance(srcKey3RskAddress).asBigInteger().intValue());
        Assert.assertEquals(LIMIT_MONETARY_BASE, repository.getBalance(PrecompiledContracts.BRIDGE_ADDR));

        BridgeStorageProvider provider2 = new BridgeStorageProvider(repository, PrecompiledContracts.BRIDGE_ADDR, bridgeConstants, activations);

        Assert.assertEquals(0, provider2.getNewFederationBtcUTXOs().size());
        Assert.assertEquals(0, provider2.getOldFederationBtcUTXOs().size());

        Assert.assertEquals(0, provider2.getReleaseRequestQueue().getEntries().size());
        Assert.assertEquals(0, provider2.getReleaseTransactionSet().getEntriesWithoutHash().size());
        Assert.assertEquals(3, provider2.getReleaseTransactionSet().getEntriesWithHash().size());

        List<BtcTransaction> releaseTxs = provider2.getReleaseTransactionSet().getEntries()
                .stream()
                .map(ReleaseTransactionSet.Entry::getTransaction)
                .sorted(Comparator.comparing(BtcTransaction::getOutputSum))
                .collect(Collectors.toList());

        // First release tx should correspond to the 5 BTC lock tx
        BtcTransaction releaseTx = releaseTxs.get(0);
        Assert.assertEquals(1, releaseTx.getOutputs().size());
        Assert.assertThat(Coin.COIN.multiply(5).subtract(releaseTx.getOutput(0).getValue()), is(lessThanOrEqualTo(Coin.MILLICOIN)));
        Assert.assertEquals(srcKey1.toAddress(btcParams), releaseTx.getOutput(0).getAddressFromP2PKHScript(btcParams));
        Assert.assertEquals(1, releaseTx.getInputs().size());
        Assert.assertEquals(tx1.getHash(), releaseTx.getInput(0).getOutpoint().getHash());
        Assert.assertEquals(0, releaseTx.getInput(0).getOutpoint().getIndex());
        // First Rsk tx corresponds to this release
        verify(bridgeEventLogger, times(1)).logReleaseBtcRequested(rskTx1.getHash().getBytes(), releaseTx, Coin.COIN.multiply(5));

        // Second release tx should correspond to the 7 (3+4) BTC lock tx
        releaseTx = releaseTxs.get(1);
        Assert.assertEquals(1, releaseTx.getOutputs().size());
        Assert.assertThat(Coin.COIN.multiply(7).subtract(releaseTx.getOutput(0).getValue()), is(lessThanOrEqualTo(Coin.MILLICOIN)));
        Assert.assertEquals(srcKey3.toAddress(btcParams), releaseTx.getOutput(0).getAddressFromP2PKHScript(btcParams));
        Assert.assertEquals(2, releaseTx.getInputs().size());
        List<TransactionOutPoint> releaseOutpoints = releaseTx.getInputs().stream().map(TransactionInput::getOutpoint).sorted(Comparator.comparing(TransactionOutPoint::getIndex)).collect(Collectors.toList());
        Assert.assertEquals(tx3.getHash(), releaseOutpoints.get(0).getHash());
        Assert.assertEquals(tx3.getHash(), releaseOutpoints.get(1).getHash());
        Assert.assertEquals(0, releaseOutpoints.get(0).getIndex());
        Assert.assertEquals(1, releaseOutpoints.get(1).getIndex());
        // third Rsk tx corresponds to this release
        verify(bridgeEventLogger, times(1)).logReleaseBtcRequested(rskTx3.getHash().getBytes(), releaseTx, Coin.COIN.multiply(7));

        // Third release tx should correspond to the 10 BTC lock tx
        releaseTx = releaseTxs.get(2);
        Assert.assertEquals(1, releaseTx.getOutputs().size());
        Assert.assertThat(Coin.COIN.multiply(10).subtract(releaseTx.getOutput(0).getValue()), is(lessThanOrEqualTo(Coin.MILLICOIN)));
        Assert.assertEquals(srcKey2.toAddress(btcParams), releaseTx.getOutput(0).getAddressFromP2PKHScript(btcParams));
        Assert.assertEquals(1, releaseTx.getInputs().size());
        Assert.assertEquals(tx2.getHash(), releaseTx.getInput(0).getOutpoint().getHash());
        Assert.assertEquals(0, releaseTx.getInput(0).getOutpoint().getIndex());
        // Second Rsk tx corresponds to this release
        verify(bridgeEventLogger, times(1)).logReleaseBtcRequested(rskTx2.getHash().getBytes(), releaseTx, Coin.COIN.multiply(10));

        Assert.assertTrue(provider2.getRskTxsWaitingForSignatures().isEmpty());
        Assert.assertEquals(3, provider2.getBtcTxHashesAlreadyProcessed().size());
    }

    @Test
    public void callProcessFundsMigration_is_migrating_before_rskip_146_activation() throws IOException {
        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP146)).thenReturn(false);

        BridgeEventLogger bridgeEventLogger = mock(BridgeEventLogger.class);

        Federation oldFederation = bridgeConstants.getGenesisFederation();
        BtcECKey key = new BtcECKey(new SecureRandom());
        FederationMember member = new FederationMember(key, new ECKey(), new ECKey());
        Federation newFederation = new Federation(
                Collections.singletonList(member),
                Instant.EPOCH,
                5L,
                bridgeConstants.getBtcParams()
        );

        BridgeStorageProvider provider = mock(BridgeStorageProvider.class);
        when(provider.getFeePerKb())
                .thenReturn(Coin.MILLICOIN);
        when(provider.getReleaseRequestQueue())
                .thenReturn(new ReleaseRequestQueue(Collections.emptyList()));
        when(provider.getReleaseTransactionSet())
                .thenReturn(new ReleaseTransactionSet(Collections.emptySet()));
        when(provider.getOldFederation())
                .thenReturn(oldFederation);
        when(provider.getNewFederation())
                .thenReturn(newFederation);

        BlockGenerator blockGenerator = new BlockGenerator();
        // Old federation will be in migration age at block 35
        org.ethereum.core.Block rskCurrentBlock = blockGenerator.createBlock(35, 1);
        Transaction tx = new Transaction(TO_ADDRESS, DUST_AMOUNT, NONCE, GAS_PRICE, GAS_LIMIT, DATA, Constants.REGTEST_CHAIN_ID);

        Repository repository = createRepository();
        Repository track = repository.startTracking();
        BridgeSupport bridgeSupport = getBridgeSupport(
                bridgeConstants, provider, track, bridgeEventLogger, rskCurrentBlock, null, activations
        );

        List<UTXO> sufficientUTXOsForMigration1 = new ArrayList<>();
        sufficientUTXOsForMigration1.add(createUTXO(Coin.COIN, oldFederation.getAddress()));
        when(provider.getOldFederationBtcUTXOs())
                .thenReturn(sufficientUTXOsForMigration1);

        bridgeSupport.updateCollections(tx);

        Assert.assertEquals(1, provider.getReleaseTransactionSet().getEntriesWithoutHash().size());
        Assert.assertEquals(0, provider.getReleaseTransactionSet().getEntriesWithHash().size());

        verify(bridgeEventLogger, never()).logReleaseBtcRequested(any(byte[].class), any(BtcTransaction.class), any(Coin.class));
    }

    @Test
    public void callProcessFundsMigration_is_migrating_after_rskip_146_activation() throws IOException {
        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP146)).thenReturn(true);

        BridgeEventLogger bridgeEventLogger = mock(BridgeEventLogger.class);

        Federation oldFederation = bridgeConstants.getGenesisFederation();
        BtcECKey key = new BtcECKey(new SecureRandom());
        FederationMember member = new FederationMember(key, new ECKey(), new ECKey());
        Federation newFederation = new Federation(
                Collections.singletonList(member),
                Instant.EPOCH,
                5L,
                bridgeConstants.getBtcParams()
        );

        BridgeStorageProvider provider = mock(BridgeStorageProvider.class);
        when(provider.getFeePerKb())
                .thenReturn(Coin.MILLICOIN);
        when(provider.getReleaseRequestQueue())
                .thenReturn(new ReleaseRequestQueue(Collections.emptyList()));
        when(provider.getReleaseTransactionSet())
                .thenReturn(new ReleaseTransactionSet(Collections.emptySet()));
        when(provider.getOldFederation())
                .thenReturn(oldFederation);
        when(provider.getNewFederation())
                .thenReturn(newFederation);

        BlockGenerator blockGenerator = new BlockGenerator();
        // Old federation will be in migration age at block 35
        org.ethereum.core.Block rskCurrentBlock = blockGenerator.createBlock(35, 1);
        Transaction tx = new Transaction(TO_ADDRESS, DUST_AMOUNT, NONCE, GAS_PRICE, GAS_LIMIT, DATA, Constants.REGTEST_CHAIN_ID);
        tx.sign(new ECKey().getPrivKeyBytes());

        Repository repository = createRepository();
        Repository track = repository.startTracking();
        BridgeSupport bridgeSupport = getBridgeSupport(
                bridgeConstants, provider, track, bridgeEventLogger, rskCurrentBlock, null, activations
        );

        List<UTXO> sufficientUTXOsForMigration1 = new ArrayList<>();
        sufficientUTXOsForMigration1.add(createUTXO(Coin.COIN, oldFederation.getAddress()));
        when(provider.getOldFederationBtcUTXOs())
                .thenReturn(sufficientUTXOsForMigration1);

        bridgeSupport.updateCollections(tx);

        Assert.assertEquals(0, provider.getReleaseTransactionSet().getEntriesWithoutHash().size());
        Assert.assertEquals(1, provider.getReleaseTransactionSet().getEntriesWithHash().size());
        ReleaseTransactionSet.Entry entry = (ReleaseTransactionSet.Entry)provider.getReleaseTransactionSet().getEntriesWithHash().toArray()[0];
        // Should have been logged with the migrated UTXO
        verify(bridgeEventLogger, times(1)).logReleaseBtcRequested(tx.getHash().getBytes(), entry.getTransaction(), Coin.COIN);
    }

    @Test
    public void callProcessFundsMigration_is_migrated_before_rskip_146_activation() throws IOException {
        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP146)).thenReturn(false);

        BridgeEventLogger bridgeEventLogger = mock(BridgeEventLogger.class);

        Federation oldFederation = bridgeConstants.getGenesisFederation();
        BtcECKey key = new BtcECKey(new SecureRandom());
        FederationMember member = new FederationMember(key, new ECKey(), new ECKey());
        Federation newFederation = new Federation(
                Collections.singletonList(member),
                Instant.EPOCH,
                5L,
                bridgeConstants.getBtcParams()
        );

        BridgeStorageProvider provider = mock(BridgeStorageProvider.class);
        when(provider.getFeePerKb())
                .thenReturn(Coin.MILLICOIN);
        when(provider.getReleaseRequestQueue())
                .thenReturn(new ReleaseRequestQueue(Collections.emptyList()));
        when(provider.getReleaseTransactionSet())
                .thenReturn(new ReleaseTransactionSet(Collections.emptySet()));
        when(provider.getOldFederation())
                .thenReturn(oldFederation);
        when(provider.getNewFederation())
                .thenReturn(newFederation);

        BlockGenerator blockGenerator = new BlockGenerator();
        // Old federation will be in migration age at block 35
        org.ethereum.core.Block rskCurrentBlock = blockGenerator.createBlock(180, 1);
        Transaction tx = new Transaction(TO_ADDRESS, DUST_AMOUNT, NONCE, GAS_PRICE, GAS_LIMIT, DATA, Constants.REGTEST_CHAIN_ID);

        Repository repository = createRepository();
        Repository track = repository.startTracking();
        BridgeSupport bridgeSupport = getBridgeSupport(
                bridgeConstants, provider, track, bridgeEventLogger, rskCurrentBlock, null, activations
        );

        List<UTXO> sufficientUTXOsForMigration1 = new ArrayList<>();
        sufficientUTXOsForMigration1.add(createUTXO(Coin.COIN, oldFederation.getAddress()));
        when(provider.getOldFederationBtcUTXOs())
                .thenReturn(sufficientUTXOsForMigration1);

        bridgeSupport.updateCollections(tx);

        Assert.assertEquals(1, provider.getReleaseTransactionSet().getEntriesWithoutHash().size());
        Assert.assertEquals(0, provider.getReleaseTransactionSet().getEntriesWithHash().size());

        verify(bridgeEventLogger, never()).logReleaseBtcRequested(any(byte[].class), any(BtcTransaction.class), any(Coin.class));
    }

    @Test
    public void callProcessFundsMigration_is_migrated_after_rskip_146_activation() throws IOException {
        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP146)).thenReturn(true);

        BridgeEventLogger bridgeEventLogger = mock(BridgeEventLogger.class);

        Federation oldFederation = bridgeConstants.getGenesisFederation();
        BtcECKey key = new BtcECKey(new SecureRandom());
        FederationMember member = new FederationMember(key, new ECKey(), new ECKey());
        Federation newFederation = new Federation(
                Collections.singletonList(member),
                Instant.EPOCH,
                5L,
                bridgeConstants.getBtcParams()
        );

        BridgeStorageProvider provider = mock(BridgeStorageProvider.class);
        when(provider.getFeePerKb())
                .thenReturn(Coin.MILLICOIN);
        when(provider.getReleaseRequestQueue())
                .thenReturn(new ReleaseRequestQueue(Collections.emptyList()));
        when(provider.getReleaseTransactionSet())
                .thenReturn(new ReleaseTransactionSet(Collections.emptySet()));
        when(provider.getOldFederation())
                .thenReturn(oldFederation);
        when(provider.getNewFederation())
                .thenReturn(newFederation);

        BlockGenerator blockGenerator = new BlockGenerator();
        // Old federation will be in migration age at block 35
        org.ethereum.core.Block rskCurrentBlock = blockGenerator.createBlock(180, 1);
        Transaction tx = new Transaction(TO_ADDRESS, DUST_AMOUNT, NONCE, GAS_PRICE, GAS_LIMIT, DATA, Constants.REGTEST_CHAIN_ID);

        Repository repository = createRepository();
        Repository track = repository.startTracking();
        BridgeSupport bridgeSupport = getBridgeSupport(
                bridgeConstants, provider, track, bridgeEventLogger, rskCurrentBlock, null, activations
        );

        List<UTXO> sufficientUTXOsForMigration1 = new ArrayList<>();
        sufficientUTXOsForMigration1.add(createUTXO(Coin.COIN, oldFederation.getAddress()));
        when(provider.getOldFederationBtcUTXOs())
                .thenReturn(sufficientUTXOsForMigration1);

        bridgeSupport.updateCollections(tx);

        Assert.assertEquals(0, provider.getReleaseTransactionSet().getEntriesWithoutHash().size());
        Assert.assertEquals(1, provider.getReleaseTransactionSet().getEntriesWithHash().size());
        ReleaseTransactionSet.Entry entry = (ReleaseTransactionSet.Entry)provider.getReleaseTransactionSet().getEntriesWithHash().toArray()[0];
        // Should have been logged with the migrated UTXO
        verify(bridgeEventLogger, times(1)).logReleaseBtcRequested(tx.getHash().getBytes(), entry.getTransaction(), Coin.COIN);
    }

    private BridgeSupport getBridgeSupport(BridgeConstants constants, BridgeStorageProvider provider, Repository track, BtcBlockStoreWithCache.Factory blockStoreFactory) {
        return getBridgeSupport(constants, provider, track, null, null, blockStoreFactory);
    }

    private BridgeSupport getBridgeSupport(BridgeConstants constants, BridgeStorageProvider provider, Repository track,
                                           BridgeEventLogger eventLogger, Block executionBlock,
                                           BtcBlockStoreWithCache.Factory blockStoreFactory) {
        return getBridgeSupport(
                constants, provider, track, eventLogger, executionBlock,
                blockStoreFactory, mock(ActivationConfig.ForBlock.class)
        );
    }

    private BridgeSupport getBridgeSupport(BridgeConstants constants, BridgeStorageProvider provider, Repository track,
                                           BridgeEventLogger eventLogger, Block executionBlock,
                                           BtcBlockStoreWithCache.Factory blockStoreFactory,
                                           ActivationConfig.ForBlock activations) {
        if (eventLogger == null) {
            eventLogger = mock(BridgeEventLogger.class);
        }
        if (blockStoreFactory == null) {
            blockStoreFactory = mock(BtcBlockStoreWithCache.Factory.class);
        }
        return new BridgeSupport(
                constants, provider, eventLogger, track, executionBlock,
                new Context(constants.getBtcParams()),
                new FederationSupport(constants, provider, executionBlock),
                blockStoreFactory, activations
        );
    }

    private void mockChainOfStoredBlocks(BtcBlockStoreWithCache btcBlockStore, BtcBlock targetHeader, int headHeight, int targetHeight) throws BlockStoreException {
        // Simulate that the block is in there by mocking the getter by height,
        // and then simulate that the txs have enough confirmations by setting a high head.
        when(btcBlockStore.getStoredBlockAtMainChainHeight(targetHeight)).thenReturn(new StoredBlock(targetHeader, BigInteger.ONE, targetHeight));
        // Mock current pointer's header
        StoredBlock currentStored = mock(StoredBlock.class);
        BtcBlock currentBlock = mock(BtcBlock.class);
        doReturn(Sha256Hash.of(Hex.decode("aa"))).when(currentBlock).getHash();
        doReturn(currentBlock).when(currentStored).getHeader();
        when(currentStored.getHeader()).thenReturn(currentBlock);
        when(btcBlockStore.getChainHead()).thenReturn(currentStored);
        when(currentStored.getHeight()).thenReturn(headHeight);
    }

    public static Repository createRepository() {
        return new MutableRepository(new MutableTrieCache(new MutableTrieImpl(null, new Trie())));
    }

    private UTXO createUTXO(Coin value, Address address) {
        return new UTXO(
                PegTestUtils.createHash(),
                1,
                value,
                0,
                false,
                ScriptBuilder.createOutputScript(address));
    }

    private Transaction getMockedRskTxWithHash(String s) {
        byte[] hash = Keccak256Helper.keccak256(s);
        return new SimpleRskTransaction(hash);
    }
}
