/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.cloud.api.collections;


import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.solr.client.solrj.cloud.DistribStateManager;
import org.apache.solr.client.solrj.cloud.SolrCloudManager;
import org.apache.solr.client.solrj.cloud.autoscaling.AlreadyExistsException;
import org.apache.solr.client.solrj.cloud.autoscaling.BadVersionException;
import org.apache.solr.client.solrj.cloud.autoscaling.DelegatingCloudManager;
import org.apache.solr.client.solrj.cloud.autoscaling.DelegatingClusterStateProvider;
import org.apache.solr.client.solrj.cloud.autoscaling.NotEmptyException;
import org.apache.solr.client.solrj.cloud.autoscaling.PolicyHelper;
import org.apache.solr.client.solrj.cloud.autoscaling.VersionedData;
import org.apache.solr.client.solrj.impl.ClusterStateProvider;
import org.apache.solr.cloud.Overseer;
import org.apache.solr.cloud.RefreshCollectionMessage;
import org.apache.solr.cloud.ZkController;
import org.apache.solr.cloud.api.collections.OverseerCollectionMessageHandler.ShardRequestTracker;
import org.apache.solr.cloud.overseer.ClusterStateMutator;
import org.apache.solr.cloud.overseer.SliceMutator;
import org.apache.solr.cloud.overseer.ZkWriteCommand;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.cloud.Aliases;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.DocRouter;
import org.apache.solr.common.cloud.ImplicitDocRouter;
import org.apache.solr.common.cloud.PerReplicaStates;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.ReplicaPosition;
import org.apache.solr.common.cloud.ZkConfigManager;
import org.apache.solr.common.cloud.ZkNodeProps;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.cloud.ZooKeeperException;
import org.apache.solr.common.params.CollectionAdminParams;
import org.apache.solr.common.params.CommonAdminParams;
import org.apache.solr.common.params.CoreAdminParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.common.util.TimeSource;
import org.apache.solr.common.util.Utils;
import org.apache.solr.handler.component.ShardHandler;
import org.apache.solr.handler.component.ShardRequest;
import org.apache.solr.util.TimeOut;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.solr.common.cloud.ZkStateReader.MAX_SHARDS_PER_NODE;
import static org.apache.solr.common.cloud.ZkStateReader.NRT_REPLICAS;
import static org.apache.solr.common.cloud.ZkStateReader.PULL_REPLICAS;
import static org.apache.solr.common.cloud.ZkStateReader.REPLICATION_FACTOR;
import static org.apache.solr.common.cloud.ZkStateReader.TLOG_REPLICAS;
import static org.apache.solr.common.params.CollectionAdminParams.ALIAS;
import static org.apache.solr.common.params.CollectionAdminParams.COLL_CONF;
import static org.apache.solr.common.params.CollectionAdminParams.COLOCATED_WITH;
import static org.apache.solr.common.params.CollectionParams.CollectionAction.ADDREPLICA;
import static org.apache.solr.common.params.CollectionParams.CollectionAction.MODIFYCOLLECTION;
import static org.apache.solr.common.params.CommonAdminParams.ASYNC;
import static org.apache.solr.common.params.CommonAdminParams.WAIT_FOR_FINAL_STATE;
import static org.apache.solr.common.params.CommonParams.NAME;
import static org.apache.solr.common.util.StrUtils.formatString;
import static org.apache.solr.handler.admin.ConfigSetsHandler.DEFAULT_CONFIGSET_NAME;
import static org.apache.solr.handler.admin.ConfigSetsHandler.getSuffixedNameForAutoGeneratedConfigSet;

public class CreateCollectionCmd implements OverseerCollectionMessageHandler.Cmd {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private final OverseerCollectionMessageHandler ocmh;
  private final TimeSource timeSource;
  private final DistribStateManager stateManager;

  public CreateCollectionCmd(OverseerCollectionMessageHandler ocmh) {
    this.ocmh = ocmh;
    this.stateManager = ocmh.cloudManager.getDistribStateManager();
    this.timeSource = ocmh.cloudManager.getTimeSource();
  }

  @Override
  @SuppressWarnings({"unchecked"})
  public void call(ClusterState clusterState, ZkNodeProps message, @SuppressWarnings({"rawtypes"})NamedList results) throws Exception {
    if (ocmh.zkStateReader.aliasesManager != null) { // not a mock ZkStateReader
      ocmh.zkStateReader.aliasesManager.update();
    }
    final Aliases aliases = ocmh.zkStateReader.getAliases();
    final String collectionName = message.getStr(NAME);
    final boolean waitForFinalState = message.getBool(WAIT_FOR_FINAL_STATE, false);
    final String alias = message.getStr(ALIAS, collectionName);
    log.info("Create collection {}", collectionName);
    final boolean isPRS = message.getBool(DocCollection.PER_REPLICA_STATE, false);
    if (clusterState.hasCollection(collectionName)) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "collection already exists: " + collectionName);
    }
    if (aliases.hasAlias(collectionName)) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "collection alias already exists: " + collectionName);
    }

    String withCollection = message.getStr(CollectionAdminParams.WITH_COLLECTION);
    String withCollectionShard = null;
    if (withCollection != null) {
      String realWithCollection = aliases.resolveSimpleAlias(withCollection);
      if (!clusterState.hasCollection(realWithCollection)) {
        throw new SolrException(ErrorCode.BAD_REQUEST, "The 'withCollection' does not exist: " + realWithCollection);
      } else  {
        DocCollection collection = clusterState.getCollection(realWithCollection);
        if (collection.getActiveSlices().size() > 1)  {
          throw new SolrException(ErrorCode.BAD_REQUEST, "The `withCollection` must have only one shard, found: " + collection.getActiveSlices().size());
        }
        withCollectionShard = collection.getActiveSlices().iterator().next().getName();
      }
    }

    String configName = getConfigName(collectionName, message);
    if (configName == null) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "No config set found to associate with the collection.");
    }

    ocmh.validateConfigOrThrowSolrException(configName);

    String router = message.getStr("router.name", DocRouter.DEFAULT_NAME);

    // fail fast if parameters are wrong or incomplete
    List<String> shardNames = populateShardNames(message, router);
    checkReplicaTypes(message);
    DocCollection newColl = null;
    final String collectionPath = ZkStateReader.getCollectionPath(collectionName);

    AtomicReference<PolicyHelper.SessionWrapper> sessionWrapper = new AtomicReference<>();

    try {

      final String async = message.getStr(ASYNC);

      ZkStateReader zkStateReader = ocmh.zkStateReader;
      boolean isLegacyCloud = Overseer.isLegacy(zkStateReader);

      OverseerCollectionMessageHandler.createConfNode(stateManager, configName, collectionName, isLegacyCloud);

      Map<String,String> collectionParams = new HashMap<>();
      Map<String,Object> collectionProps = message.getProperties();
      for (Map.Entry<String, Object> entry : collectionProps.entrySet()) {
        String propName = entry.getKey();
        if (propName.startsWith(ZkController.COLLECTION_PARAM_PREFIX)) {
          collectionParams.put(propName.substring(ZkController.COLLECTION_PARAM_PREFIX.length()), (String) entry.getValue());
        }
      }

      createCollectionZkNode(stateManager, collectionName, collectionParams);

      if (isPRS) {
        // In case of a PRS collection, create the collection structure directly instead of
        // resubmitting to the overseer queue.
        // TODO: Consider doing this for all collections, not just the PRS collections.
        // TODO comment above achieved by switching the cluster to distributed state updates

        // This code directly updates Zookeeper by creating the collection state.json. It is
        // compatible with both distributed cluster state updates and Overseer based cluster state
        // updates.

        // TODO: Consider doing this for all collections, not just the PRS collections.
        ZkWriteCommand command =
            new ClusterStateMutator(ocmh.overseer.getSolrCloudManager())
                .createCollection(clusterState, message);
        byte[] data = Utils.toJSON(Collections.singletonMap(collectionName, command.collection));
        ocmh.overseer.getZkStateReader()
            .getZkClient()
            .create(collectionPath, data, CreateMode.PERSISTENT, true);
        clusterState = clusterState.copyWith(collectionName, command.collection);
        newColl = command.collection;
        ocmh.overseer.submit(new RefreshCollectionMessage(collectionName));
      } else {
        ocmh.overseer.offerStateUpdate(Utils.toJSON(message));

        // wait for a while until we see the collection
        TimeOut waitUntil = new TimeOut(30, TimeUnit.SECONDS, timeSource);
        boolean created = false;
        while (!waitUntil.hasTimedOut()) {
          waitUntil.sleep(100);
          created = ocmh.cloudManager.getClusterStateProvider().getClusterState().hasCollection(collectionName);
          if (created) break;
        }
        if (!created) {
          throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Could not fully create collection: " + collectionName);
        }

        // refresh cluster state
        clusterState = ocmh.cloudManager.getClusterStateProvider().getClusterState();
        newColl = clusterState.getCollection(collectionName);

      }


      List<ReplicaPosition> replicaPositions = null;
      try {
        replicaPositions = buildReplicaPositions(ocmh.cloudManager, clusterState, newColl, message, shardNames, sessionWrapper);
      } catch (Assign.AssignmentException e) {
        ZkNodeProps deleteMessage = new ZkNodeProps("name", collectionName);
        new DeleteCollectionCmd(ocmh).call(clusterState, deleteMessage, results);
        // unwrap the exception
        throw new SolrException(ErrorCode.BAD_REQUEST, e.getMessage(), e.getCause());
      }

      if (replicaPositions.isEmpty()) {
        log.debug("Finished create command for collection: {}", collectionName);
        return;
      }

      final ShardRequestTracker shardRequestTracker = ocmh.asyncRequestTracker(async);
      if (log.isDebugEnabled()) {
        log.debug(formatString("Creating SolrCores for new collection {0}, shardNames {1} , message : {2}",
            collectionName, shardNames, message));
      }
      Map<String,ShardRequest> coresToCreate = new LinkedHashMap<>();
      ShardHandler shardHandler = ocmh.shardHandlerFactory.getShardHandler(ocmh.overseer.getCoreContainer().getUpdateShardHandler().getDefaultHttpClient());
      for (ReplicaPosition replicaPosition : replicaPositions) {
        String nodeName = replicaPosition.node;

        if (withCollection != null) {
          // check that we have a replica of `withCollection` on this node and if not, create one
          DocCollection collection = clusterState.getCollection(withCollection);
          List<Replica> replicas = collection.getReplicas(nodeName);
          if (replicas == null || replicas.isEmpty()) {
            ZkNodeProps props = new ZkNodeProps(
                Overseer.QUEUE_OPERATION, ADDREPLICA.toString(),
                ZkStateReader.COLLECTION_PROP, withCollection,
                ZkStateReader.SHARD_ID_PROP, withCollectionShard,
                "node", nodeName,
                CommonAdminParams.WAIT_FOR_FINAL_STATE, Boolean.TRUE.toString()); // set to true because we want `withCollection` to be ready after this collection is created
            new AddReplicaCmd(ocmh).call(clusterState, props, results);
            clusterState = zkStateReader.getClusterState(); // refresh
          }
        }

        String coreName = Assign.buildSolrCoreName(ocmh.cloudManager.getDistribStateManager(),
            newColl,
            replicaPosition.shard, replicaPosition.type, true);
        if (log.isDebugEnabled()) {
          log.debug(formatString("Creating core {0} as part of shard {1} of collection {2} on {3}"
              , coreName, replicaPosition.shard, collectionName, nodeName));
        }

        String baseUrl = zkStateReader.getBaseUrlForNodeName(nodeName);
        //in the new mode, create the replica in clusterstate prior to creating the core.
        // Otherwise the core creation fails
        if (!isLegacyCloud) {
          ZkNodeProps props = new ZkNodeProps(
              Overseer.QUEUE_OPERATION, ADDREPLICA.toString(),
              ZkStateReader.COLLECTION_PROP, collectionName,
              ZkStateReader.SHARD_ID_PROP, replicaPosition.shard,
              ZkStateReader.CORE_NAME_PROP, coreName,
              ZkStateReader.STATE_PROP, Replica.State.DOWN.toString(),
              ZkStateReader.NODE_NAME_PROP, nodeName,
              ZkStateReader.BASE_URL_PROP, baseUrl,
              ZkStateReader.REPLICA_TYPE, replicaPosition.type.name(),
              CommonAdminParams.WAIT_FOR_FINAL_STATE, Boolean.toString(waitForFinalState));
          if (isPRS) {
            // In case of a PRS collection, execute the ADDREPLICA directly instead of resubmitting
            // to the overseer queue.
            // TODO: Consider doing this for all collections, not just the PRS collections.
            ZkWriteCommand command = new SliceMutator(ocmh.cloudManager).addReplica(clusterState, props);
//            byte[] data = Utils.toJSON(Collections.singletonMap(collectionName, command.collection));
//            ocmh.zkStateReader.getZkClient().setData(collectionPath, data, true);
            clusterState = clusterState.copyWith(collectionName, command.collection);
            newColl = command.collection;
          } else {
            ocmh.overseer.offerStateUpdate(Utils.toJSON(props));
          }
        }

        // Need to create new params for each request
        ModifiableSolrParams params = new ModifiableSolrParams();
        params.set(CoreAdminParams.ACTION, CoreAdminParams.CoreAdminAction.CREATE.toString());

        params.set(CoreAdminParams.NAME, coreName);
        params.set(COLL_CONF, configName);
        params.set(CoreAdminParams.COLLECTION, collectionName);
        params.set(CoreAdminParams.SHARD, replicaPosition.shard);
        params.set(ZkStateReader.NUM_SHARDS_PROP, shardNames.size());
        params.set(CoreAdminParams.NEW_COLLECTION, "true");
        params.set(CoreAdminParams.REPLICA_TYPE, replicaPosition.type.name());

        if (async != null) {
          String coreAdminAsyncId = async + Math.abs(System.nanoTime());
          params.add(ASYNC, coreAdminAsyncId);
          shardRequestTracker.track(nodeName, coreAdminAsyncId);
        }
        ocmh.addPropertyParams(message, params);

        ShardRequest sreq = new ShardRequest();
        sreq.nodeName = nodeName;
        params.set("qt", ocmh.adminPath);
        sreq.purpose = 1;
        sreq.shards = new String[]{baseUrl};
        sreq.actualShards = sreq.shards;
        sreq.params = params;

        if (isLegacyCloud) {
          shardHandler.submit(sreq, sreq.shards[0], sreq.params);
        } else {
          coresToCreate.put(coreName, sreq);
        }
      }
      if(isPRS) {
        byte[] data =
            Utils.toJSON(
                Collections.singletonMap(
                    collectionName, clusterState.getCollection(collectionName)));
        zkStateReader.getZkClient().setData(collectionPath, data, true);
        ocmh.overseer.submit(new RefreshCollectionMessage(collectionName));
      }

      if(!isLegacyCloud) {
        // wait for all replica entries to be created
        Map<String, Replica> replicas ;
        if (isPRS) {
          replicas = new ConcurrentHashMap<>();
          newColl.getSlices().stream().flatMap(slice -> slice.getReplicas().stream())
              .filter(r -> coresToCreate.containsKey(r.getCoreName()))       // Only the elements that were asked for...
              .forEach(r -> replicas.putIfAbsent(r.getCoreName(), r)); // ...get added to the map
        } else {
          replicas = ocmh.waitToSeeReplicasInState(collectionName, coresToCreate.keySet());
        }

        for (Map.Entry<String, ShardRequest> e : coresToCreate.entrySet()) {
          ShardRequest sreq = e.getValue();
          sreq.params.set(CoreAdminParams.CORE_NODE_NAME, replicas.get(e.getKey()).getName());
          shardHandler.submit(sreq, sreq.shards[0], sreq.params);
        }
      }

      shardRequestTracker.processResponses(results, shardHandler, false, null, Collections.emptySet());
      @SuppressWarnings({"rawtypes"})
      boolean failure = results.get("failure") != null && ((SimpleOrderedMap)results.get("failure")).size() > 0;
      final Map<String, Replica> replicas;
      if (isPRS) {
        TimeOut timeout =
            new TimeOut(
                Integer.getInteger("solr.waitToSeeReplicasInStateTimeoutSeconds", 120),
                TimeUnit.SECONDS,
                ocmh.timeSource); // could be a big cluster
        PerReplicaStates prs =
            PerReplicaStates.fetch(collectionPath, ocmh.overseer.getZkStateReader().getZkClient(), null);
        while (!timeout.hasTimedOut()) {
          if (prs.allActive()) break;
          Thread.sleep(100);
          prs =
              PerReplicaStates.fetch(collectionPath, ocmh.overseer.getZkStateReader().getZkClient(), null);
        }
        if (prs.allActive()) {
          // we have successfully found all replicas to be ACTIVE
        } else {
          failure = true;
        }
      }
      if (failure) {
        // Let's cleanup as we hit an exception
        // We shouldn't be passing 'results' here for the cleanup as the response would then contain 'success'
        // element, which may be interpreted by the user as a positive ack
        ocmh.cleanupCollection(collectionName, new NamedList<Object>());
        log.info("Cleaned up artifacts for failed create collection for [{}]", collectionName);
        throw new SolrException(ErrorCode.BAD_REQUEST, "Underlying core creation failed while creating collection: " + collectionName);
      } else {
        log.debug("Finished create command on all shards for collection: {}", collectionName);

        // Emit a warning about production use of data driven functionality
        boolean defaultConfigSetUsed = message.getStr(COLL_CONF) == null ||
            message.getStr(COLL_CONF).equals(DEFAULT_CONFIGSET_NAME);
        if (defaultConfigSetUsed) {
          results.add("warning", "Using _default configset. Data driven schema functionality"
              + " is enabled by default, which is NOT RECOMMENDED for production use. To turn it off:"
              + " curl http://{host:port}/solr/" + collectionName + "/config -d '{\"set-user-property\": {\"update.autoCreateFields\":\"false\"}}'");
        }
      }

      // modify the `withCollection` and store this new collection's name with it
      if (withCollection != null) {
        ZkNodeProps props = new ZkNodeProps(
            Overseer.QUEUE_OPERATION, MODIFYCOLLECTION.toString(),
            ZkStateReader.COLLECTION_PROP, withCollection,
            CollectionAdminParams.COLOCATED_WITH, collectionName);
        ocmh.overseer.offerStateUpdate(Utils.toJSON(props));
        try {
          zkStateReader.waitForState(withCollection, 5, TimeUnit.SECONDS, (collectionState) -> collectionName.equals(collectionState.getStr(COLOCATED_WITH)));
        } catch (TimeoutException e) {
          log.warn("Timed out waiting to see the {} property set on collection: {}", COLOCATED_WITH, withCollection);
          // maybe the overseer queue is backed up, we don't want to fail the create request
          // because of this time out, continue
        }
      }

      // create an alias pointing to the new collection, if different from the collectionName
      if (!alias.equals(collectionName)) {
        ocmh.zkStateReader.aliasesManager.applyModificationAndExportToZk(a -> a.cloneWithCollectionAlias(alias, collectionName));
      }

    } catch (SolrException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, null, ex);
    } finally {
      if (sessionWrapper.get() != null) sessionWrapper.get().release();
    }
  }

  public static List<ReplicaPosition> buildReplicaPositions(SolrCloudManager cloudManager, ClusterState clusterState,
                                                            DocCollection docCollection,
                                                            ZkNodeProps message,
                                                            List<String> shardNames,
                                                            AtomicReference<PolicyHelper.SessionWrapper> sessionWrapper) throws IOException, InterruptedException, Assign.AssignmentException {
    final String collectionName = message.getStr(NAME);
    // look at the replication factor and see if it matches reality
    // if it does not, find best nodes to create more cores
    int numTlogReplicas = message.getInt(TLOG_REPLICAS, 0);
    int numNrtReplicas = message.getInt(NRT_REPLICAS, message.getInt(REPLICATION_FACTOR, numTlogReplicas>0?0:1));
    int numPullReplicas = message.getInt(PULL_REPLICAS, 0);

    int numSlices = shardNames.size();
    int maxShardsPerNode = message.getInt(MAX_SHARDS_PER_NODE, 1);
    if (maxShardsPerNode == -1) maxShardsPerNode = Integer.MAX_VALUE;
    cloudManager = wrapCloudManager(clusterState, cloudManager);

    // we need to look at every node and see how many cores it serves
    // add our new cores to existing nodes serving the least number of cores
    // but (for now) require that each core goes on a distinct node.

    List<ReplicaPosition> replicaPositions;
    List<String> nodeList =
        Assign.getLiveOrLiveAndCreateNodeSetList(
            clusterState.getLiveNodes(),
            message,
            OverseerCollectionMessageHandler.RANDOM,
            cloudManager.getDistribStateManager());
    if (nodeList.isEmpty()) {
      log.warn("It is unusual to create a collection ({}) without cores.", collectionName);

      replicaPositions = new ArrayList<>();
    } else {
      int totalNumReplicas = numNrtReplicas + numTlogReplicas + numPullReplicas;
      if (totalNumReplicas > nodeList.size()) {
        log.warn("Specified number of replicas of {} on collection {} is higher than the number of Solr instances currently live or live and part of your {}({}). {}"
            , totalNumReplicas
            , collectionName
            , OverseerCollectionMessageHandler.CREATE_NODE_SET
            , nodeList.size()
            , "It's unusual to run two replica of the same slice on the same Solr-instance.");
      }

      int maxShardsAllowedToCreate = maxShardsPerNode == Integer.MAX_VALUE ?
          Integer.MAX_VALUE :
          maxShardsPerNode * nodeList.size();
      int requestedShardsToCreate = numSlices * totalNumReplicas;
      if (maxShardsAllowedToCreate < requestedShardsToCreate) {
        throw new Assign.AssignmentException("Cannot create collection " + collectionName + ". Value of "
            + MAX_SHARDS_PER_NODE + " is " + maxShardsPerNode
            + ", and the number of nodes currently live or live and part of your "+OverseerCollectionMessageHandler.CREATE_NODE_SET+" is " + nodeList.size()
            + ". This allows a maximum of " + maxShardsAllowedToCreate
            + " to be created. Value of " + OverseerCollectionMessageHandler.NUM_SLICES + " is " + numSlices
            + ", value of " + NRT_REPLICAS + " is " + numNrtReplicas
            + ", value of " + TLOG_REPLICAS + " is " + numTlogReplicas
            + " and value of " + PULL_REPLICAS + " is " + numPullReplicas
            + ". This requires " + requestedShardsToCreate
            + " shards to be created (higher than the allowed number)");
      }
      Assign.AssignRequest assignRequest = new Assign.AssignRequestBuilder()
          .forCollection(collectionName)
          .forShard(shardNames)
          .assignNrtReplicas(numNrtReplicas)
          .assignTlogReplicas(numTlogReplicas)
          .assignPullReplicas(numPullReplicas)
          .onNodes(nodeList)
          .build();
      Assign.AssignStrategyFactory assignStrategyFactory = new Assign.AssignStrategyFactory(cloudManager);
      Assign.AssignStrategy assignStrategy = assignStrategyFactory.create(clusterState, docCollection);
      replicaPositions = assignStrategy.assign(cloudManager, assignRequest);
      sessionWrapper.set(PolicyHelper.getLastSessionWrapper(true));
    }
    return replicaPositions;
  }
  // the cloud manager should reflect the latest internal cluster state
  private static SolrCloudManager wrapCloudManager(
      ClusterState clusterState, SolrCloudManager solrCloudManager) {
    final ClusterStateProvider csp =
        new DelegatingClusterStateProvider(solrCloudManager.getClusterStateProvider()) {
          @Override
          public ClusterState.CollectionRef getState(String collection) {
            return clusterState.getCollectionRef(collection);
          }

          @Override
          public ClusterState getClusterState() {
            return clusterState;
          }

          @Override
          public DocCollection getCollection(String name) throws IOException {
            return clusterState.getCollection(name);
          }
        };

    return new DelegatingCloudManager(solrCloudManager) {
      @Override
      public ClusterStateProvider getClusterStateProvider() {
        return csp;
      }
    };
  }

  public static void checkReplicaTypes(ZkNodeProps message) {
    int numTlogReplicas = message.getInt(TLOG_REPLICAS, 0);
    int numNrtReplicas = message.getInt(NRT_REPLICAS, message.getInt(REPLICATION_FACTOR, numTlogReplicas > 0 ? 0 : 1));

    if (numNrtReplicas + numTlogReplicas <= 0) {
      throw new SolrException(ErrorCode.BAD_REQUEST, NRT_REPLICAS + " + " + TLOG_REPLICAS + " must be greater than 0");
    }
  }

  public static List<String> populateShardNames(ZkNodeProps message, String router) {
    List<String> shardNames = new ArrayList<>();
    Integer numSlices = message.getInt(OverseerCollectionMessageHandler.NUM_SLICES, null);
    if (ImplicitDocRouter.NAME.equals(router)) {
      ClusterStateMutator.getShardNames(shardNames, message.getStr("shards", null));
      numSlices = shardNames.size();
    } else {
      if (numSlices == null) {
        throw new SolrException(ErrorCode.BAD_REQUEST, OverseerCollectionMessageHandler.NUM_SLICES + " is a required param (when using CompositeId router).");
      }
      if (numSlices <= 0) {
        throw new SolrException(ErrorCode.BAD_REQUEST, OverseerCollectionMessageHandler.NUM_SLICES + " must be > 0");
      }
      ClusterStateMutator.getShardNames(numSlices, shardNames);
    }
    return shardNames;
  }

  String getConfigName(String coll, ZkNodeProps message) throws KeeperException, InterruptedException {
    String configName = message.getStr(COLL_CONF);

    if (configName == null) {
      // if there is only one conf, use that
      List<String> configNames = null;
      try {
        configNames = ocmh.zkStateReader.getZkClient().getChildren(ZkConfigManager.CONFIGS_ZKNODE, null, true);
        if (configNames.contains(DEFAULT_CONFIGSET_NAME)) {
          if (CollectionAdminParams.SYSTEM_COLL.equals(coll)) {
            return coll;
          } else {
            String intendedConfigSetName = getSuffixedNameForAutoGeneratedConfigSet(coll);
            copyDefaultConfigSetTo(configNames, intendedConfigSetName);
            return intendedConfigSetName;
          }
        } else if (configNames != null && configNames.size() == 1) {
          configName = configNames.get(0);
          // no config set named, but there is only 1 - use it
          log.info("Only one config set found in zk - using it: {}", configName);
        }
      } catch (KeeperException.NoNodeException e) {

      }
    }
    return "".equals(configName)? null: configName;
  }

  /**
   * Copies the _default configset to the specified configset name (overwrites if pre-existing)
   */
  private void copyDefaultConfigSetTo(List<String> configNames, String targetConfig) {
    ZkConfigManager configManager = new ZkConfigManager(ocmh.zkStateReader.getZkClient());

    // if a configset named collection exists, re-use it
    if (configNames.contains(targetConfig)) {
      log.info("There exists a configset by the same name as the collection we're trying to create: {}, re-using it.", targetConfig);
      return;
    }
    // Copy _default into targetConfig
    try {
      configManager.copyConfigDir(DEFAULT_CONFIGSET_NAME, targetConfig, new HashSet<>());
    } catch (Exception e) {
      throw new SolrException(ErrorCode.INVALID_STATE, "Error while copying _default to " + targetConfig, e);
    }
  }

  public static void createCollectionZkNode(DistribStateManager stateManager, String collection, Map<String,String> params) {
    log.debug("Check for collection zkNode: {}", collection);
    String collectionPath = ZkStateReader.COLLECTIONS_ZKNODE + "/" + collection;
    // clean up old terms node
    String termsPath = ZkStateReader.COLLECTIONS_ZKNODE + "/" + collection + "/terms";
    try {
      stateManager.removeRecursively(termsPath, true, true);
    } catch (InterruptedException e) {
      Thread.interrupted();
      throw new SolrException(ErrorCode.SERVER_ERROR, "Error deleting old term nodes for collection from Zookeeper", e);
    } catch (KeeperException | IOException | NotEmptyException | BadVersionException e) {
      throw new SolrException(ErrorCode.SERVER_ERROR, "Error deleting old term nodes for collection from Zookeeper", e);
    }
    try {
      if (!stateManager.hasData(collectionPath)) {
        log.debug("Creating collection in ZooKeeper: {}", collection);

        try {
          Map<String,Object> collectionProps = new HashMap<>();

          if (params.size() > 0) {
            collectionProps.putAll(params);
            // if the config name wasn't passed in, use the default
            if (!collectionProps.containsKey(ZkController.CONFIGNAME_PROP)) {
              // users can create the collection node and conf link ahead of time, or this may return another option
              getConfName(stateManager, collection, collectionPath, collectionProps);
            }

          } else if (System.getProperty("bootstrap_confdir") != null) {
            String defaultConfigName = System.getProperty(ZkController.COLLECTION_PARAM_PREFIX + ZkController.CONFIGNAME_PROP, collection);

            // if we are bootstrapping a collection, default the config for
            // a new collection to the collection we are bootstrapping
            log.info("Setting config for collection: {} to {}", collection, defaultConfigName);

            Properties sysProps = System.getProperties();
            for (String sprop : System.getProperties().stringPropertyNames()) {
              if (sprop.startsWith(ZkController.COLLECTION_PARAM_PREFIX)) {
                collectionProps.put(sprop.substring(ZkController.COLLECTION_PARAM_PREFIX.length()), sysProps.getProperty(sprop));
              }
            }

            // if the config name wasn't passed in, use the default
            if (!collectionProps.containsKey(ZkController.CONFIGNAME_PROP))
              collectionProps.put(ZkController.CONFIGNAME_PROP, defaultConfigName);

          } else if (Boolean.getBoolean("bootstrap_conf")) {
            // the conf name should should be the collection name of this core
            collectionProps.put(ZkController.CONFIGNAME_PROP, collection);
          } else {
            getConfName(stateManager, collection, collectionPath, collectionProps);
          }

          collectionProps.remove(ZkStateReader.NUM_SHARDS_PROP);  // we don't put numShards in the collections properties

          ZkNodeProps zkProps = new ZkNodeProps(collectionProps);
          stateManager.makePath(collectionPath, Utils.toJSON(zkProps), CreateMode.PERSISTENT, false);

        } catch (KeeperException e) {
          //TODO shouldn't the stateManager ensure this does not happen; should throw AlreadyExistsException
          // it's okay if the node already exists
          if (e.code() != KeeperException.Code.NODEEXISTS) {
            throw e;
          }
        } catch (AlreadyExistsException e) {
          // it's okay if the node already exists
        }
      } else {
        log.debug("Collection zkNode exists");
      }

    } catch (KeeperException e) {
      // it's okay if another beats us creating the node
      if (e.code() == KeeperException.Code.NODEEXISTS) {
        return;
      }
      throw new SolrException(ErrorCode.SERVER_ERROR, "Error creating collection node in Zookeeper", e);
    } catch (IOException e) {
      throw new SolrException(ErrorCode.SERVER_ERROR, "Error creating collection node in Zookeeper", e);
    } catch (InterruptedException e) {
      Thread.interrupted();
      throw new SolrException(ErrorCode.SERVER_ERROR, "Error creating collection node in Zookeeper", e);
    }

  }

  private static void getConfName(DistribStateManager stateManager, String collection, String collectionPath, Map<String,Object> collectionProps) throws IOException,
      KeeperException, InterruptedException {
    // check for configName
    log.debug("Looking for collection configName");
    if (collectionProps.containsKey("configName")) {
      if (log.isInfoEnabled()) {
        log.info("configName was passed as a param {}", collectionProps.get("configName"));
      }
      return;
    }

    List<String> configNames = null;
    int retry = 1;
    int retryLimt = 6;
    for (; retry < retryLimt; retry++) {
      if (stateManager.hasData(collectionPath)) {
        VersionedData data = stateManager.getData(collectionPath);
        ZkNodeProps cProps = ZkNodeProps.load(data.getData());
        if (cProps.containsKey(ZkController.CONFIGNAME_PROP)) {
          break;
        }
      }

      try {
        configNames = stateManager.listData(ZkConfigManager.CONFIGS_ZKNODE);
      } catch (NoSuchElementException | NoNodeException e) {
        // just keep trying
      }

      // check if there's a config set with the same name as the collection
      if (configNames != null && configNames.contains(collection)) {
        log.info("Could not find explicit collection configName, but found config name matching collection name - using that set.");
        collectionProps.put(ZkController.CONFIGNAME_PROP, collection);
        break;
      }
      // if _default exists, use that
      if (configNames != null && configNames.contains(DEFAULT_CONFIGSET_NAME)) {
        log.info("Could not find explicit collection configName, but found _default config set - using that set.");
        collectionProps.put(ZkController.CONFIGNAME_PROP, DEFAULT_CONFIGSET_NAME);
        break;
      }
      // if there is only one conf, use that
      if (configNames != null && configNames.size() == 1) {
        // no config set named, but there is only 1 - use it
        if (log.isInfoEnabled()) {
          log.info("Only one config set found in zk - using it: {}", configNames.get(0));
        }
        collectionProps.put(ZkController.CONFIGNAME_PROP, configNames.get(0));
        break;
      }

      log.info("Could not find collection configName - pausing for 3 seconds and trying again - try: {}", retry);
      Thread.sleep(3000);
    }
    if (retry == retryLimt) {
      log.error("Could not find configName for collection {}", collection);
      throw new ZooKeeperException(
          SolrException.ErrorCode.SERVER_ERROR,
          "Could not find configName for collection " + collection + " found:" + configNames);
    }
  }
}
