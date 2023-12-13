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
package org.apache.solr.handler.admin;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.solr.client.solrj.SolrResponse;
import org.apache.solr.cloud.OverseerSolrResponse;
import org.apache.solr.cloud.OverseerSolrResponseSerializer;
import org.apache.solr.cloud.OverseerTaskQueue.QueueEvent;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.cloud.ZkConfigManager;
import org.apache.solr.common.cloud.ZkMaintenanceUtils;
import org.apache.solr.common.cloud.ZkNodeProps;
import org.apache.solr.common.params.ConfigSetParams;
import org.apache.solr.common.params.ConfigSetParams.ConfigSetAction;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.ContentStream;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.common.util.Utils;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.handler.RequestHandlerBase;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.security.AuthenticationPlugin;
import org.apache.solr.security.AuthorizationContext;
import org.apache.solr.security.PermissionNameProvider;
import org.apache.solr.util.FileTypeMagicUtil;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.solr.cloud.Overseer.QUEUE_OPERATION;
import static org.apache.solr.cloud.OverseerConfigSetMessageHandler.BASE_CONFIGSET;
import static org.apache.solr.cloud.OverseerConfigSetMessageHandler.CONFIGSETS_ACTION_PREFIX;
import static org.apache.solr.cloud.OverseerConfigSetMessageHandler.PROPERTY_PREFIX;
import static org.apache.solr.common.params.CommonParams.NAME;
import static org.apache.solr.common.params.ConfigSetParams.ConfigSetAction.CREATE;
import static org.apache.solr.common.params.ConfigSetParams.ConfigSetAction.DELETE;
import static org.apache.solr.common.params.ConfigSetParams.ConfigSetAction.LIST;
import static org.apache.solr.common.params.ConfigSetParams.ConfigSetAction.UPLOAD;

/**
 * A {@link org.apache.solr.request.SolrRequestHandler} for ConfigSets API requests.
 */
public class ConfigSetsHandler extends RequestHandlerBase implements PermissionNameProvider {
  final public static Boolean DISABLE_CREATE_AUTH_CHECKS = Boolean.getBoolean("solr.disableConfigSetsCreateAuthChecks"); // this is for back compat only
  final public static String DEFAULT_CONFIGSET_NAME = "_default";
  final public static String AUTOCREATED_CONFIGSET_SUFFIX = ".AUTOCREATED";
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  protected final CoreContainer coreContainer;
  public static long DEFAULT_ZK_TIMEOUT = 300 * 1000;
  /**
   * Overloaded ctor to inject CoreContainer into the handler.
   *
   * @param coreContainer Core Container of the solr webapp installed.
   */
  public ConfigSetsHandler(final CoreContainer coreContainer) {
    this.coreContainer = coreContainer;
  }

  public static String getSuffixedNameForAutoGeneratedConfigSet(String configName) {
    return configName + AUTOCREATED_CONFIGSET_SUFFIX;
  }

  public static boolean isAutoGeneratedConfigSet(String configName) {
    return configName != null && configName.endsWith(AUTOCREATED_CONFIGSET_SUFFIX);
  }


  @Override
  public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse rsp) throws Exception {
    checkErrors();

    // Pick the action
    SolrParams params = req.getParams();
    String a = params.get(ConfigSetParams.ACTION);
    if (a != null) {
      ConfigSetAction action = ConfigSetAction.get(a);
      if (action == null)
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Unknown action: " + a);
      if (action == ConfigSetAction.UPLOAD) {
        handleConfigUploadRequest(req, rsp);
        return;
      }
      invokeAction(req, rsp, action);
    } else {
      throw new SolrException(ErrorCode.BAD_REQUEST, "action is a required param");
    }

    rsp.setHttpCaching(false);
  }

  protected void checkErrors() {
    if (coreContainer == null) {
      throw new SolrException(ErrorCode.BAD_REQUEST,
          "Core container instance missing");
    }

    // Make sure that the core is ZKAware
    if (!coreContainer.isZooKeeperAware()) {
      throw new SolrException(ErrorCode.BAD_REQUEST,
          "Solr instance is not running in SolrCloud mode.");
    }
  }

  void invokeAction(SolrQueryRequest req, SolrQueryResponse rsp, ConfigSetAction action) throws Exception {
    ConfigSetOperation operation = ConfigSetOperation.get(action);
    if (log.isInfoEnabled()) {
      log.info("Invoked ConfigSet Action :{} with params {} ", action.toLower(), req.getParamString());
    }
    Map<String, Object> result = operation.call(req, rsp, this);
    sendToZk(rsp, operation, result);
  }

  protected void sendToZk(SolrQueryResponse rsp, ConfigSetOperation operation, Map<String, Object> result)
      throws KeeperException, InterruptedException {
    if (result != null) {
      // We need to differentiate between collection and configsets actions since they currently
      // use the same underlying queue.
      result.put(QUEUE_OPERATION, CONFIGSETS_ACTION_PREFIX + operation.action.toLower());
      ZkNodeProps props = new ZkNodeProps(result);
      handleResponse(operation.action.toLower(), props, rsp, DEFAULT_ZK_TIMEOUT);
    }
  }

  private void handleConfigUploadRequest(SolrQueryRequest req, SolrQueryResponse rsp) throws Exception {
    if (!"true".equals(System.getProperty("configset.upload.enabled", "true"))) {
      throw new SolrException(ErrorCode.BAD_REQUEST,
          "Configset upload feature is disabled. To enable this, start Solr with '-Dconfigset.upload.enabled=true'.");
    }

    String configSetName = req.getParams().get(NAME);
    if (StringUtils.isBlank(configSetName)) {
      throw new SolrException(ErrorCode.BAD_REQUEST,
          "The configuration name should be provided in the \"name\" parameter");
    }

    SolrZkClient zkClient = coreContainer.getZkController().getZkClient();
    String configPathInZk = ZkConfigManager.CONFIGS_ZKNODE + "/" + configSetName;

    boolean overwritesExisting = zkClient.exists(configPathInZk, true);

    boolean requestIsTrusted = isTrusted(req, coreContainer.getAuthenticationPlugin());

    // Get upload parameters
    String singleFilePath = req.getParams().get(ConfigSetParams.FILE_PATH, "");
    boolean allowOverwrite = req.getParams().getBool(ConfigSetParams.OVERWRITE, false);
    boolean cleanup = req.getParams().getBool(ConfigSetParams.CLEANUP, false);

    Iterator<ContentStream> contentStreamsIterator = req.getContentStreams().iterator();

    if (!contentStreamsIterator.hasNext()) {
      throw new SolrException(ErrorCode.BAD_REQUEST,
              "No stream found for the config data to be uploaded");
    }

    InputStream inputStream = contentStreamsIterator.next().getStream();

    // Only Upload a single file
    if (!singleFilePath.isEmpty()) {
      String fixedSingleFilePath = singleFilePath;
      if (fixedSingleFilePath.charAt(0) == '/') {
        fixedSingleFilePath = fixedSingleFilePath.substring(1);
      }
      if (fixedSingleFilePath.isEmpty()) {
        throw new SolrException(ErrorCode.BAD_REQUEST, "The file path provided for upload, '" + singleFilePath + "', is not valid.");
      } else if (cleanup) {
        // Cleanup is not allowed while using singleFilePath upload
        throw new SolrException(ErrorCode.BAD_REQUEST, "ConfigSet uploads do not allow cleanup=true when file path is used.");
      } else {
        try {
          // Create a node for the configuration in zookeeper
          // For creating the baseZnode, the cleanup parameter is only allowed to be true when singleFilePath is not passed.
          byte[] bytes = IOUtils.toByteArray(inputStream);
          if (!FileTypeMagicUtil.isFileForbiddenInConfigset(bytes)) {
            createBaseZnode(zkClient, overwritesExisting, requestIsTrusted, configPathInZk);
            String filePathInZk = configPathInZk + "/" + fixedSingleFilePath;
            zkClient.makePath(filePathInZk, bytes, CreateMode.PERSISTENT, null, !allowOverwrite, true);
          } else {
            String mimeType = FileTypeMagicUtil.INSTANCE.guessMimeType(bytes);
            throw new SolrException(ErrorCode.BAD_REQUEST,
                String.format(Locale.ROOT, "Not uploading file %s to configset, as it matched the MAGIC signature of a forbidden mime type %s",
                    fixedSingleFilePath, mimeType));
          }
        } catch(KeeperException.NodeExistsException nodeExistsException) {
          throw new SolrException(ErrorCode.BAD_REQUEST,
                  "The path " + singleFilePath + " for configSet " + configSetName + " already exists. In order to overwrite, provide overwrite=true or use an HTTP PUT with the V2 API.");
        }
      }
      return;
    }

    if (overwritesExisting && !allowOverwrite) {
      throw new SolrException(ErrorCode.BAD_REQUEST,
              "The configuration " + configSetName + " already exists in zookeeper");
    }

    Set<String> filesToDelete;
    if (overwritesExisting && cleanup) {
      filesToDelete = getAllConfigsetFiles(zkClient, configPathInZk);
    } else {
      filesToDelete = Collections.emptySet();
    }

    // Create a node for the configuration in zookeeper
    // For creating the baseZnode, the cleanup parameter is only allowed to be true when singleFilePath is not passed.
    createBaseZnode(zkClient, overwritesExisting, requestIsTrusted, configPathInZk);

    ZipInputStream zis = new ZipInputStream(inputStream, StandardCharsets.UTF_8);
    ZipEntry zipEntry = null;
    boolean hasEntry = false;
    while ((zipEntry = zis.getNextEntry()) != null) {
      hasEntry = true;
      String filePathInZk = configPathInZk + "/" + zipEntry.getName();
      if (filePathInZk.endsWith("/")) {
        filesToDelete.remove(filePathInZk.substring(0, filePathInZk.length() -1));
      } else {
        filesToDelete.remove(filePathInZk);
      }
      if (zipEntry.isDirectory()) {
        zkClient.makePath(filePathInZk, false,  true);
      } else {
        byte[] bytes = IOUtils.toByteArray(zis);
        if (!FileTypeMagicUtil.isFileForbiddenInConfigset(bytes)) {
          createZkNodeIfNotExistsAndSetData(zkClient, filePathInZk, bytes);
        } else {
          String mimeType = FileTypeMagicUtil.INSTANCE.guessMimeType(bytes);
          throw new SolrException(ErrorCode.BAD_REQUEST,
              String.format(Locale.ROOT, "Not uploading file %s to configset, as it matched the MAGIC signature of a forbidden mime type %s",
                  zipEntry.getName(), mimeType));
        }
      }
    }
    zis.close();
    if (!hasEntry) {
      throw new SolrException(ErrorCode.BAD_REQUEST,
              "Either empty zipped data, or non-zipped data was uploaded. In order to upload a configSet, you must zip a non-empty directory to upload.");
    }
    deleteUnusedFiles(zkClient, filesToDelete);

    // If the request is doing a full trusted overwrite of an untrusted configSet (overwrite=true, cleanup=true), then trust the configSet.
    if (cleanup && requestIsTrusted && overwritesExisting && !isCurrentlyTrusted(zkClient, configPathInZk)) {
      byte[] baseZnodeData =  ("{\"trusted\": true}").getBytes(StandardCharsets.UTF_8);
      zkClient.setData(configPathInZk, baseZnodeData, true);
    }
  }

  private void createBaseZnode(SolrZkClient zkClient, boolean overwritesExisting, boolean requestIsTrusted, String configPathInZk) throws KeeperException, InterruptedException {
    byte[] baseZnodeData =  ("{\"trusted\": " + Boolean.toString(requestIsTrusted) + "}").getBytes(StandardCharsets.UTF_8);

    if (overwritesExisting) {
      if (!requestIsTrusted) {
        ensureOverwritingUntrustedConfigSet(zkClient, configPathInZk);
      }
      // If the request is trusted and cleanup=true, then the configSet will be set to trusted after the overwriting has been done.
    } else {
      zkClient.makePath(configPathInZk, baseZnodeData, true);
    }
  }

  private void deleteUnusedFiles(SolrZkClient zkClient, Set<String> filesToDelete) throws InterruptedException, KeeperException {
    if (!filesToDelete.isEmpty()) {
      if (log.isInfoEnabled()) {
        log.info("Cleaning up {} unused files", filesToDelete.size());
      }
      if (log.isDebugEnabled()) {
        log.debug("Cleaning up unused files: {}", filesToDelete);
      }
      for (String f:filesToDelete) {
        try {
          zkClient.delete(f, -1, true);
        } catch (KeeperException.NoNodeException nne) {
        }
      }
    }
  }

  private Set<String> getAllConfigsetFiles(SolrZkClient zkClient, String configPathInZk) throws KeeperException, InterruptedException {
    final Set<String> files = new HashSet<>();
    if (!configPathInZk.startsWith(ZkConfigManager.CONFIGS_ZKNODE + "/")) {
      throw new IllegalArgumentException("\"" + configPathInZk + "\" not recognized as a configset path");
    }
    ZkMaintenanceUtils.traverseZkTree(zkClient, configPathInZk, ZkMaintenanceUtils.VISIT_ORDER.VISIT_POST, files::add);
    files.remove(configPathInZk);
    return files;
  }

  /*
   * Fail if an untrusted request tries to update a trusted ConfigSet
   */
  private void ensureOverwritingUntrustedConfigSet(SolrZkClient zkClient, String configSetZkPath) {
    boolean isCurrentlyTrusted = isCurrentlyTrusted(zkClient, configSetZkPath);
    if (isCurrentlyTrusted) {
      throw new SolrException(ErrorCode.BAD_REQUEST, "Trying to make an unstrusted ConfigSet update on a trusted configSet");
    }
  }

  public static boolean isCurrentlyTrusted(SolrZkClient zkClient, String configSetZkPath) {
    byte[] configSetNodeContent;
    try {
      configSetNodeContent = zkClient.getData(configSetZkPath, null, null, true);
      if (configSetNodeContent == null || configSetNodeContent.length == 0) {
        return true;
      }
    } catch (KeeperException e) {
      throw new SolrException(ErrorCode.SERVER_ERROR, "Exception while fetching current configSet at " + configSetZkPath, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SolrException(ErrorCode.SERVER_ERROR, "Interrupted while fetching current configSet at " + configSetZkPath, e);
    }
    @SuppressWarnings("unchecked")
    Map<Object, Object> contentMap = (Map<Object, Object>) Utils.fromJSON(configSetNodeContent);
    return (boolean) contentMap.getOrDefault("trusted", true);
  }

  public void setConfigMetadata(String configName, Map<String, Object> data) throws IOException {
    try {
      coreContainer.getZkController().getZkClient().makePath(
          ZkConfigManager.CONFIGS_ZKNODE + "/" + configName,
          Utils.toJSON(data),
          CreateMode.PERSISTENT,
          null,
          false,
          true);
    } catch (KeeperException | InterruptedException e) {
      throw new IOException("Error setting config metadata", SolrZkClient.checkInterrupted(e));
    }
  }

  public void removeConfigSetTrust(String configSetName) {
    try {
      Map<String, Object> metadata = Collections.singletonMap("trusted", false);
      setConfigMetadata(configSetName, metadata);
    } catch (IOException e) {
      throw new SolrException(
          SolrException.ErrorCode.SERVER_ERROR,
          "Could not remove trusted flag for configSet " + configSetName + ": " + e.getMessage(),
          e);
    }
  }

  public static boolean isTrusted(SolrQueryRequest req, AuthenticationPlugin authPlugin) {
    if (authPlugin != null && req.getUserPrincipal() != null) {
      log.debug("Trusted configset request");
      return true;
    }
    log.debug("Untrusted configset request");
    return false;
  }

  private void createZkNodeIfNotExistsAndSetData(SolrZkClient zkClient,
                                                 String filePathInZk, byte[] data) throws Exception {
    if (!zkClient.exists(filePathInZk, true)) {
      zkClient.create(filePathInZk, data, CreateMode.PERSISTENT, true);
    } else {
      zkClient.setData(filePathInZk, data, true);
    }
  }

  @SuppressWarnings({"unchecked"})
  private void handleResponse(String operation, ZkNodeProps m,
                              SolrQueryResponse rsp, long timeout) throws KeeperException, InterruptedException {
    long time = System.nanoTime();

    QueueEvent event = coreContainer.getZkController()
        .getOverseerConfigSetQueue()
        .offer(Utils.toJSON(m), timeout);
    if (event.getBytes() != null) {
      SolrResponse response = OverseerSolrResponseSerializer.deserialize(event.getBytes());
      rsp.getValues().addAll(response.getResponse());
      @SuppressWarnings({"rawtypes"})
      SimpleOrderedMap exp = (SimpleOrderedMap) response.getResponse().get("exception");
      if (exp != null) {
        Integer code = (Integer) exp.get("rspCode");
        rsp.setException(new SolrException(code != null && code != -1 ? ErrorCode.getErrorCode(code) : ErrorCode.SERVER_ERROR, (String) exp.get("msg")));
      }
    } else {
      if (System.nanoTime() - time >= TimeUnit.NANOSECONDS.convert(timeout, TimeUnit.MILLISECONDS)) {
        throw new SolrException(ErrorCode.SERVER_ERROR, operation
            + " the configset time out:" + timeout / 1000 + "s");
      } else if (event.getWatchedEvent() != null) {
        throw new SolrException(ErrorCode.SERVER_ERROR, operation
            + " the configset error [Watcher fired on path: "
            + event.getWatchedEvent().getPath() + " state: "
            + event.getWatchedEvent().getState() + " type "
            + event.getWatchedEvent().getType() + "]");
      } else {
        throw new SolrException(ErrorCode.SERVER_ERROR, operation
            + " the configset unknown case");
      }
    }
  }

  private static Map<String, Object> copyPropertiesWithPrefix(SolrParams params, Map<String, Object> props, String prefix) {
    Iterator<String> iter = params.getParameterNamesIterator();
    while (iter.hasNext()) {
      String param = iter.next();
      if (param.startsWith(prefix)) {
        props.put(param, params.get(param));
      }
    }

    // The configset created via an API should be mutable.
    props.put("immutable", "false");

    return props;
  }

  @Override
  public String getDescription() {
    return "Manage SolrCloud ConfigSets";
  }

  @Override
  public Category getCategory() {
    return Category.ADMIN;
  }

  public enum ConfigSetOperation {
    UPLOAD_OP(UPLOAD) {
      @Override
      public Map<String, Object> call(SolrQueryRequest req, SolrQueryResponse rsp, ConfigSetsHandler h) throws Exception {
        h.handleConfigUploadRequest(req, rsp);
        return null;
      }
    },
    CREATE_OP(CREATE) {
      @Override
      public Map<String, Object> call(SolrQueryRequest req, SolrQueryResponse rsp, ConfigSetsHandler h) throws Exception {
        String baseConfigSetName = req.getParams().get(BASE_CONFIGSET, DEFAULT_CONFIGSET_NAME);
        String newConfigSetName = req.getParams().get(NAME);
        if (newConfigSetName == null || newConfigSetName.length() == 0) {
          throw new SolrException(ErrorCode.BAD_REQUEST, "ConfigSet name not specified");
        }

        ZkConfigManager zkConfigManager = new ZkConfigManager(h.coreContainer.getZkController().getZkStateReader().getZkClient());
        if (zkConfigManager.configExists(newConfigSetName)) {
          throw new SolrException(ErrorCode.BAD_REQUEST, "ConfigSet already exists: " + newConfigSetName);
        }

        // is there a base config that already exists
        if (!zkConfigManager.configExists(baseConfigSetName)) {
          throw new SolrException(ErrorCode.BAD_REQUEST,
                  "Base ConfigSet does not exist: " + baseConfigSetName);
        }

        Map<String, Object> props = CollectionsHandler.copy(req.getParams().required(), null, NAME);
        props.put(BASE_CONFIGSET, baseConfigSetName);
        if (!DISABLE_CREATE_AUTH_CHECKS &&
                !isTrusted(req, h.coreContainer.getAuthenticationPlugin()) &&
                isCurrentlyTrusted(h.coreContainer.getZkController().getZkClient(), ZkConfigManager.CONFIGS_ZKNODE + "/" +  baseConfigSetName)) {
          throw new SolrException(ErrorCode.UNAUTHORIZED, "Can't create a configset with an unauthenticated request from a trusted " + BASE_CONFIGSET);
        }
        return copyPropertiesWithPrefix(req.getParams(), props, PROPERTY_PREFIX + ".");
      }
    },
    DELETE_OP(DELETE) {
      @Override
      public Map<String, Object> call(SolrQueryRequest req, SolrQueryResponse rsp, ConfigSetsHandler h) throws Exception {
        return CollectionsHandler.copy(req.getParams().required(), null, NAME);
      }
    },
    @SuppressWarnings({"unchecked"})
    LIST_OP(LIST) {
      @Override
      public Map<String, Object> call(SolrQueryRequest req, SolrQueryResponse rsp, ConfigSetsHandler h) throws Exception {
        NamedList<Object> results = new NamedList<>();
        SolrZkClient zk = h.coreContainer.getZkController().getZkStateReader().getZkClient();
        ZkConfigManager zkConfigManager = new ZkConfigManager(zk);
        List<String> configSetsList = zkConfigManager.listConfigs();
        results.add("configSets", configSetsList);
        SolrResponse response = new OverseerSolrResponse(results);
        rsp.getValues().addAll(response.getResponse());
        return null;
      }
    };

    ConfigSetAction action;

    ConfigSetOperation(ConfigSetAction action) {
      this.action = action;
    }

    public abstract Map<String, Object> call(SolrQueryRequest req, SolrQueryResponse rsp, ConfigSetsHandler h) throws Exception;

    public static ConfigSetOperation get(ConfigSetAction action) {
      for (ConfigSetOperation op : values()) {
        if (op.action == action) return op;
      }
      throw new SolrException(ErrorCode.SERVER_ERROR, "No such action" + action);
    }
  }

  @Override
  public Name getPermissionName(AuthorizationContext ctx) {
    String a = ctx.getParams().get(ConfigSetParams.ACTION);
    if (a != null) {
      ConfigSetAction action = ConfigSetAction.get(a);
      if (action == ConfigSetAction.CREATE || action == ConfigSetAction.DELETE || action == ConfigSetAction.UPLOAD) {
        return Name.CONFIG_EDIT_PERM;
      } else if (action == ConfigSetAction.LIST) {
        return Name.CONFIG_READ_PERM;
      }
    }
    return null;
  }
}
