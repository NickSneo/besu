package org.hyperledger.besu.ethereum.api.jsonrpc.internal.privacy.methods.priv;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.JsonRpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.privacy.methods.PrivacyIdProvider;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.processor.privateProcessor.PrivateBlockTrace;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.processor.privateProcessor.PrivateBlockTracer;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.processor.privateProcessor.PrivateTracer;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.processor.privateProcessor.PrivateTransactionTrace;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.tracing.flat.FlatTrace;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.tracing.flat.FlatTraceGenerator;
import org.hyperledger.besu.ethereum.api.query.BlockchainQueries;
import org.hyperledger.besu.ethereum.api.query.PrivacyQueries;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.PrivacyParameters;
import org.hyperledger.besu.ethereum.debug.TraceOptions;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.privacy.ExecutedPrivateTransaction;
import org.hyperledger.besu.ethereum.privacy.MultiTenancyPrivacyController;
import org.hyperledger.besu.ethereum.privacy.PrivacyController;
import org.hyperledger.besu.ethereum.privacy.storage.PrivateBlockMetadata;
import org.hyperledger.besu.ethereum.vm.DebugOperationTracer;

import java.util.Collections;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

public abstract class PrivateAbstractTraceByHash implements JsonRpcMethod {

  protected final Supplier<PrivateBlockTracer> blockTracerSupplier;
  protected final BlockchainQueries blockchainQueries;
  protected final PrivacyQueries privacyQueries;
  protected final ProtocolSchedule protocolSchedule;
  protected final PrivacyController privacyController;
  protected final PrivacyParameters privacyParameters;
  protected final PrivacyIdProvider privacyIdProvider;

  protected PrivateAbstractTraceByHash(
      final Supplier<PrivateBlockTracer> blockTracerSupplier,
      final BlockchainQueries blockchainQueries,
      final PrivacyQueries privacyQueries,
      final ProtocolSchedule protocolSchedule,
      final PrivacyController privacyController,
      final PrivacyParameters privacyParameters,
      final PrivacyIdProvider privacyIdProvider) {
    this.blockTracerSupplier = blockTracerSupplier;
    this.blockchainQueries = blockchainQueries;
    this.privacyQueries = privacyQueries;
    this.protocolSchedule = protocolSchedule;
    this.privacyController = privacyController;
    this.privacyParameters = privacyParameters;
    this.privacyIdProvider = privacyIdProvider;
  }

  public Stream<FlatTrace> resultByTransactionHash(
      final Hash transactionHash, final JsonRpcRequestContext requestContext) {

    final String enclaveKey = privacyIdProvider.getPrivacyUserId(requestContext.getUser());
    final String privacyGroupId = requestContext.getRequiredParameter(0, String.class);
    if (privacyController instanceof MultiTenancyPrivacyController) {
      checkIfPrivacyGroupMatchesAuthenticatedEnclaveKey(
          requestContext, privacyGroupId, Optional.empty());
    }

    return privacyController
        .findPrivateTransactionByPmtHash(transactionHash, enclaveKey)
        .map(ExecutedPrivateTransaction::getBlockNumber)
        .flatMap(blockNumber -> blockchainQueries.getBlockchain().getBlockHashByNumber(blockNumber))
        .map(blockHash -> getTraceBlock(blockHash, transactionHash, enclaveKey, privacyGroupId))
        .orElse(Stream.empty());
  }

  private Stream<FlatTrace> getTraceBlock(
      final Hash blockHash,
      final Hash transactionHash,
      final String enclaveKey,
      final String privacyGroupId) {
    Block block = blockchainQueries.getBlockchain().getBlockByHash(blockHash).orElse(null);
    PrivateBlockMetadata privateBlockMetadata =
        privacyQueries.getPrivateBlockMetaData(privacyGroupId, blockHash).orElse(null);

    if (privateBlockMetadata == null || block == null) {
      return Stream.empty();
    }
    return PrivateTracer.processTracing(
            blockchainQueries,
            Optional.of(block.getHeader()),
            privacyGroupId,
            enclaveKey,
            privacyParameters,
            privacyController,
            mutableWorldState -> {
              final PrivateTransactionTrace privateTransactionTrace =
                  getTransactionTrace(
                      block, transactionHash, enclaveKey, privateBlockMetadata, privacyGroupId);
              return Optional.ofNullable(getTraceStream(privateTransactionTrace, block));
            })
        .orElse(Stream.empty());
  }

  private PrivateTransactionTrace getTransactionTrace(
      final Block block,
      final Hash transactionHash,
      final String enclaveKey,
      final PrivateBlockMetadata privateBlockMetadata,
      final String privacyGroupId) {
    return PrivateTracer.processTracing(
            blockchainQueries,
            Optional.of(block.getHeader()),
            privacyGroupId,
            enclaveKey,
            privacyParameters,
            privacyController,
            mutableWorldState ->
                blockTracerSupplier
                    .get()
                    .trace(
                        mutableWorldState,
                        block,
                        new DebugOperationTracer(new TraceOptions(false, false, true)),
                        enclaveKey,
                        privacyGroupId,
                        privateBlockMetadata)
                    .map(PrivateBlockTrace::getTransactionTraces)
                    .orElse(Collections.emptyList())
                    .stream()
                    .filter(
                        trxTrace ->
                            trxTrace.getPrivateTransaction().getPmtHash().equals(transactionHash))
                    .findFirst())
        .orElseThrow();
  }

  private Stream<FlatTrace> getTraceStream(
      final PrivateTransactionTrace transactionTrace, final Block block) {
    transactionTrace.getTraceFrames();
    return FlatTraceGenerator.generateFromTransactionTraceAndBlock(
            this.protocolSchedule, null, block)
        .map(FlatTrace.class::cast);
  }

  protected JsonNode arrayNodeFromTraceStream(final Stream<FlatTrace> traceStream) {
    final ObjectMapper mapper = new ObjectMapper();
    final ArrayNode resultArrayNode = mapper.createArrayNode();
    traceStream.forEachOrdered(resultArrayNode::addPOJO);
    return resultArrayNode;
  }

  private void checkIfPrivacyGroupMatchesAuthenticatedEnclaveKey(
      final JsonRpcRequestContext request,
      final String privacyGroupId,
      final Optional<Long> toBlock) {
    final String privacyUserId = privacyIdProvider.getPrivacyUserId(request.getUser());
    privacyController.verifyPrivacyGroupContainsPrivacyUserId(
        privacyGroupId, privacyUserId, toBlock);
  }
}
