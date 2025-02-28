package com.gentics.mesh.graphdb;

import static com.gentics.mesh.core.rest.error.Errors.error;
import static com.gentics.mesh.metric.SimpleMetric.TOPOLOGY_LOCK_TIMEOUT_COUNT;
import static com.gentics.mesh.metric.SimpleMetric.TOPOLOGY_LOCK_WAITING_TIME;
import static com.gentics.mesh.metric.SimpleMetric.TX_RETRY;
import static com.gentics.mesh.metric.SimpleMetric.TX_TIME;
import static com.gentics.mesh.util.StreamUtil.toStream;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.apache.commons.lang3.tuple.Triple;
import com.gentics.mesh.Mesh;
import com.gentics.mesh.MeshStatus;
import com.gentics.mesh.changelog.changes.ChangesList;
import com.gentics.mesh.cli.BootstrapInitializer;
import com.gentics.mesh.core.data.MeshVertex;
import com.gentics.mesh.core.data.dao.DaoCollection;
import com.gentics.mesh.core.data.dao.PermissionRoots;
import com.gentics.mesh.core.db.Tx;
import com.gentics.mesh.core.db.TxAction;
import com.gentics.mesh.core.db.TxAction0;
import com.gentics.mesh.core.rest.admin.cluster.ClusterConfigRequest;
import com.gentics.mesh.core.rest.admin.cluster.ClusterConfigResponse;
import com.gentics.mesh.core.rest.admin.cluster.ClusterServerConfig;
import com.gentics.mesh.core.rest.admin.cluster.ServerRole;
import com.gentics.mesh.core.rest.error.GenericRestException;
import com.gentics.mesh.core.result.Result;
import com.gentics.mesh.core.verticle.handler.WriteLock;
import com.gentics.mesh.etc.config.ClusterOptions;
import com.gentics.mesh.etc.config.GraphStorageOptions;
import com.gentics.mesh.etc.config.MeshOptions;
import com.gentics.mesh.graphdb.check.DiskQuotaChecker;
import com.gentics.mesh.graphdb.cluster.OrientDBClusterManager;
import com.gentics.mesh.graphdb.cluster.TxCleanupTask;
import com.gentics.mesh.graphdb.dagger.TransactionComponent;
import com.gentics.mesh.graphdb.index.OrientDBIndexHandler;
import com.gentics.mesh.graphdb.index.OrientDBTypeHandler;
import com.gentics.mesh.graphdb.model.MeshElement;
import com.gentics.mesh.graphdb.spi.AbstractDatabase;
import com.gentics.mesh.graphdb.spi.GraphStorage;
import com.gentics.mesh.graphdb.tx.OrientStorage;
import com.gentics.mesh.graphdb.tx.impl.OrientLocalStorageImpl;
import com.gentics.mesh.graphdb.tx.impl.OrientServerStorageImpl;
import com.gentics.mesh.madl.frame.VertexFrame;
import com.gentics.mesh.madl.traversal.TraversalResult;
import com.gentics.mesh.metric.MetricsService;
import com.gentics.mesh.metric.SimpleMetric;
import com.gentics.mesh.util.ETag;
import com.orientechnologies.common.concur.ONeedRetryException;
import com.orientechnologies.orient.core.OConstants;
import com.orientechnologies.orient.core.Orient;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.exception.OSchemaException;
import com.orientechnologies.orient.core.index.OIndex;
import com.orientechnologies.orient.core.index.OIndexCursor;
import com.orientechnologies.orient.core.intent.OIntentMassiveInsert;
import com.orientechnologies.orient.core.storage.ORecordDuplicatedException;
import com.orientechnologies.orient.server.distributed.ODistributedConfiguration;
import com.orientechnologies.orient.server.distributed.ODistributedConfiguration.ROLES;
import com.orientechnologies.orient.server.distributed.OModifiableDistributedConfiguration;
import com.orientechnologies.orient.server.hazelcast.OHazelcastPlugin;
import com.sun.xml.bind.v2.model.core.Ref;
import com.syncleus.ferma.EdgeFrame;
import com.syncleus.ferma.FramedGraph;
import com.syncleus.ferma.ext.orientdb.DelegatingFramedOrientGraph;
import com.syncleus.ferma.typeresolvers.TypeResolver;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Element;
import com.tinkerpop.blueprints.Graph;
import com.tinkerpop.blueprints.Vertex;
import com.tinkerpop.blueprints.impls.orient.OrientBaseGraph;
import com.tinkerpop.blueprints.impls.orient.OrientElement;
import com.tinkerpop.blueprints.impls.orient.OrientGraph;
import com.tinkerpop.blueprints.impls.orient.OrientGraphNoTx;
import com.tinkerpop.blueprints.impls.orient.OrientVertex;
import com.tinkerpop.blueprints.impls.orient.OrientVertexType;
import com.tinkerpop.blueprints.util.wrappers.wrapped.WrappedVertex;
import com.tinkerpop.pipes.util.FastNoSuchElementException;

import dagger.Lazy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.vertx.core.Vertx;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

/**
 * OrientDB specific mesh graph database implementation.
 */
@Singleton
public class OrientDBDatabase extends AbstractDatabase {

	private static final Logger log = LoggerFactory.getLogger(OrientDBDatabase.class);

	private static final String RIDBAG_PARAM_KEY = "ridBag.embeddedToSbtreeBonsaiThreshold";

	private static final String DISK_QUOTA_CHECKER_THREAD_NAME = "mesh-disk-quota-checker";

	private MeshTypeResolver resolver;

	private OrientStorage txProvider;

	private MetricsService metrics;

	private Timer txTimer;

	private Counter txRetryCounter;

	private OrientDBIndexHandler indexHandler;

	private OrientDBTypeHandler typeHandler;

	private OrientDBClusterManager clusterManager;

	private final TxCleanupTask txCleanUpTask;

	private Thread txCleanupThread;

	private Timer topologyLockTimer;

	private Counter topologyLockTimeoutCounter;

	private Mesh mesh;

	private WriteLock writeLock;

	private final TransactionComponent.Factory txFactory;

	/**
	 * Executor service for running the disk quota check
	 */
	private ScheduledExecutorService diskQuotaCheckerService = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
		@Override
		public Thread newThread(Runnable r) {
			return new Thread(r, DISK_QUOTA_CHECKER_THREAD_NAME);
		}
	});

	/**
	 * scheduled disk quota check
	 */
	private ScheduledFuture<?> diskQuotaChecker;

	/**
	 * Local disk-quota-exceeded status (will be set to "true" if the local disk gets full)
	 */
	private boolean diskQuotaExceeded = false;

	/**
	 * Long Gauge Metric for the total disk space
	 */
	private AtomicLong totalDiskSpace;

	/**
	 * Long Gauge Metric for usable disk space
	 */
	private AtomicLong usableDiskSpace;

	@Inject
	public OrientDBDatabase(MeshOptions options, Lazy<Vertx> vertx, Lazy<BootstrapInitializer> boot, Lazy<DaoCollection> daos, MetricsService metrics,
		OrientDBTypeHandler typeHandler,
		OrientDBIndexHandler indexHandler,
		OrientDBClusterManager clusterManager,
		TxCleanupTask txCleanupTask,
		Lazy<PermissionRoots> permissionRoots, Mesh mesh, WriteLock writeLock,
		TransactionComponent.Factory txFactory) {
		super(vertx);
		this.options = options;
		this.metrics = metrics;
		if (metrics != null) {
			txTimer = metrics.timer(TX_TIME);
			txRetryCounter = metrics.counter(TX_RETRY);
			topologyLockTimer = metrics.timer(TOPOLOGY_LOCK_WAITING_TIME);
			topologyLockTimeoutCounter = metrics.counter(TOPOLOGY_LOCK_TIMEOUT_COUNT);
			totalDiskSpace = metrics.longGauge(OrientDBStorageMetric.DISK_TOTAL);
			usableDiskSpace = metrics.longGauge(OrientDBStorageMetric.DISK_USABLE);
		}
		this.typeHandler = typeHandler;
		this.indexHandler = indexHandler;
		this.clusterManager = clusterManager;
		this.txCleanUpTask = txCleanupTask;
		this.mesh = mesh;
		this.writeLock = writeLock;
		this.txFactory = txFactory;
	}

	@Override
	public void stop() {
		txCleanUpTask.interruptActive();
		Tx.setActive(null);
		if (txCleanupThread != null) {
			log.info("Stopping tx cleanup thread");
			txCleanupThread.interrupt();
		}

		clusterManager.stop();

		if (txProvider != null) {
			txProvider.close();
		}
	}

	@Override
	public void clear() {
		txProvider.clear();
	}

	@Override
	public void init(String meshVersion, String... basePaths) throws Exception {
		super.init(meshVersion);

		GraphStorageOptions storageOptions = options.getStorageOptions();
		boolean startOrientServer = storageOptions != null && storageOptions.getStartServer();
		boolean isInMemory = storageOptions.getDirectory() == null;

		if (isInMemory && startOrientServer) {
			throw new RuntimeException(
				"Using the graph database server is only possible for non-in-memory databases. You have not specified a graph database directory.");
		}

		int value = getRidBagValue(options);
		if (log.isTraceEnabled()) {
			log.trace("Using ridbag transition threshold {" + value + "}");
		}
		OGlobalConfiguration.RID_BAG_EMBEDDED_TO_SBTREEBONSAI_THRESHOLD.setValue(value);
		OGlobalConfiguration.WARNING_DEFAULT_USERS.setValue(false);

		clusterManager.initConfigurationFiles();

		// resolver = new OrientDBTypeResolver(basePaths);
		resolver = new MeshTypeResolver(basePaths);

		if (storageOptions.getTxCommitTimeout() != 0) {
			startTxCleanupTask();
		}

		startDiskQuotaChecker();
	}

	/**
	 * Load the ridbag configuration setting.
	 *
	 * @param options
	 * @return
	 */
	private int getRidBagValue(MeshOptions options) {
		boolean isClusterMode = options.getClusterOptions() != null && options.getClusterOptions().isEnabled();
		if (isClusterMode) {
			// This is the mandatory setting when using OrientDB in clustered mode.
			return Integer.MAX_VALUE;
		} else {
			GraphStorageOptions storageOptions = options.getStorageOptions();
			String val = storageOptions.getParameters().get(RIDBAG_PARAM_KEY);
			if (val != null) {
				try {
					return Integer.parseInt(val);
				} catch (Exception e) {
					log.error("Could not parse value of storage parameter {" + RIDBAG_PARAM_KEY + "}");
					throw new RuntimeException("Parameter {" + RIDBAG_PARAM_KEY + "} could not be parsed.");
				}
			}

		}
		// Default instead of 40 to avoid sudden changes in sort order
		return Integer.MAX_VALUE;
	}

	@Override
	public void setMassInsertIntent() {
		txProvider.setMassInsertIntent();
	}

	@Override
	public void resetIntent() {
		txProvider.resetIntent();
	}

	@Override
	public OrientGraph rawTx() {
		return txProvider.rawTx();
	}

	protected OrientGraphNoTx rawNoTx() {
		return txProvider.rawNoTx();
	}

	/**
	 * Start the orientdb related process. This will also setup the graph connection pool and handle clustering.
	 */
	@Override
	public void setupConnectionPool() throws Exception {
		startDiskQuotaChecker();
		Orient.instance().startup();
		// The mesh shutdown hook manages OrientDB shutdown.
		// We need to manage this ourself since hazelcast is otherwise shutdown before closing vert.x
		// When we control the shutdown we can ensure a clean shutdown process.
		Orient.instance().removeShutdownHook();
		initGraphDB();
	}

	/**
	 * Setup the OrientDB Graph connection
	 */
	private void initGraphDB() {
		if (clusterManager.isServerActive()) {
			txProvider = new OrientServerStorageImpl(options, clusterManager.getServer().getContext(), metrics);
		} else {
			txProvider = new OrientLocalStorageImpl(options, metrics);
		}
		// Open the storage
		txProvider.open();
	}

	@Override
	public void closeConnectionPool() {
		txProvider.close();
	}

	@Override
	public void shutdown() {
		stopDiskQuotaChecker();
		Orient.instance().shutdown();
	}

	@Override
	public Iterator<Vertex> getVertices(Class<?> classOfVertex, String[] fieldNames, Object[] fieldValues) {
		OrientBaseGraph orientBaseGraph = unwrapCurrentGraph();
		return orientBaseGraph.getVertices(classOfVertex.getSimpleName(), fieldNames, fieldValues).iterator();
	}

	@Override
	public Iterable<Vertex> getVerticesForRange(Class<?> classOfVertex, String indexPostfix, String[] fieldNames, Object[] fieldValues,
		String rangeKey, long start,
		long end) {
		OrientBaseGraph orientBaseGraph = unwrapCurrentGraph();
		OrientVertexType elementType = orientBaseGraph.getVertexType(classOfVertex.getSimpleName());
		String indexName = classOfVertex.getSimpleName() + "_" + indexPostfix;
		OIndex index = elementType.getClassIndex(indexName);
		Object startKey = index().createComposedIndexKey(fieldValues[0], start);
		Object endKey = index().createComposedIndexKey(fieldValues[0], end);
		OIndexCursor entries = index.getInternal().iterateEntriesBetween(startKey, true, endKey, true, false);
		return () -> entries.toEntries().stream().map(entry -> {
			Vertex vertex = new OrientVertex(orientBaseGraph, entry.getValue());
			return vertex;
		}).iterator();
	}

	@Override
	public <T extends VertexFrame> Result<T> getVerticesTraversal(Class<T> classOfVertex, String[] fieldNames, Object[] fieldValues) {
		Stream<Vertex> stream = toStream(getVertices(classOfVertex, fieldNames, fieldValues));
		FramedGraph graph = Tx.get().getGraph();

		return new TraversalResult<>(stream.map(v -> {
			return graph.frameElementExplicit(v, classOfVertex);
		}));
	}

	@Override
	public <T extends MeshVertex> Iterator<? extends T> getVerticesForType(Class<T> classOfVertex) {
		OrientBaseGraph orientBaseGraph = unwrapCurrentGraph();
		FramedGraph fermaGraph = Tx.get().getGraph();
		Iterator<Vertex> rawIt = orientBaseGraph.getVertices("@class", classOfVertex.getSimpleName()).iterator();
		return fermaGraph.frameExplicit(rawIt, classOfVertex);
	}

	/**
	 * Unwrap the current thread local graph.
	 *
	 * @return
	 */
	public OrientBaseGraph unwrapCurrentGraph() {
		FramedGraph graph = Tx.get().getGraph();
		Graph baseGraph = ((DelegatingFramedOrientGraph) graph).getBaseGraph();
		OrientBaseGraph tx = ((OrientBaseGraph) baseGraph);
		return tx;
	}

	@Override
	public void enableMassInsert() {
		OrientBaseGraph tx = unwrapCurrentGraph();
		tx.getRawGraph().getTransaction().setUsingLog(false);
		tx.declareIntent(new OIntentMassiveInsert().setDisableHooks(true).setDisableValidation(true));
	}

	@Override
	public <T extends MeshElement> T findVertex(String fieldKey, Object fieldValue, Class<T> clazz) {
		FramedGraph graph = Tx.get().getGraph();
		OrientBaseGraph orientBaseGraph = unwrapCurrentGraph();
		Iterator<Vertex> it = orientBaseGraph.getVertices(clazz.getSimpleName(), new String[] { fieldKey }, new Object[] { fieldValue }).iterator();
		if (it.hasNext()) {
			return graph.frameNewElementExplicit(it.next(), clazz);
		}
		return null;
	}

	@Override
	public long count(Class<? extends MeshVertex> clazz) {
		OrientBaseGraph orientBaseGraph = unwrapCurrentGraph();
		OrientVertexType type = orientBaseGraph.getVertexType(clazz.getSimpleName());
		if (type == null) {
			type = orientBaseGraph.getVertexType(clazz.getSimpleName() + "Impl");
		}
		if (type == null) {
			throw new RuntimeException("Count for class " + clazz.getName() + " could not be determined.");
		}
		return type.count();
	}

	@Override
	public <T extends EdgeFrame> T findEdge(String fieldKey, Object fieldValue, Class<T> clazz) {
		FramedGraph graph = Tx.get().getGraph();
		OrientBaseGraph orientBaseGraph = unwrapCurrentGraph();
		Iterator<Edge> it = orientBaseGraph.getEdges(fieldKey, fieldValue).iterator();
		if (it.hasNext()) {
			return graph.frameNewElementExplicit(it.next(), clazz);
		}
		return null;
	}

	@Override
	public void reload(MeshElement element) {
		reload(element.getElement());
	}

	@Override
	public void reload(Element element) {
		if (element instanceof OrientElement) {
			if (metrics.isEnabled()) {
				metrics.counter(SimpleMetric.GRAPH_ELEMENT_RELOAD).increment();
			}
			((OrientElement) element).reload();
		}
	}

	/**
	 * @deprecated Don't use tx method directly. Use {@link #tx(TxAction0)} instead to avoid tx commit issues.
	 */
	@Override
	@Deprecated
	public Tx tx() {
		return txFactory.create(txProvider, resolver).tx();
	}

	@Override
	public void blockingTopologyLockCheck() {
		ClusterOptions clusterOptions = options.getClusterOptions();
		long lockTimeout = clusterOptions.getTopologyLockTimeout();
		if (clusterOptions.isEnabled() && clusterManager() != null && lockTimeout != 0) {
			long start = System.currentTimeMillis();
			long i = 0;
			Timer.Sample sample = Timer.start();
			while (clusterManager().isClusterTopologyLocked()) {
				long dur = System.currentTimeMillis() - start;
				if (i % 250 == 0) {
					log.info("Write operation locked due to topology lock. Locked since " + dur + "ms");
				}
				if (dur > lockTimeout) {
					topologyLockTimeoutCounter.increment();
					log.warn("Tx global lock timeout of {" + lockTimeout + "} reached.");
					break;
				}
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					log.error("Interrupting topology lock delay.", e);
					break;
				}
				i++;
			}
			sample.stop(this.topologyLockTimer);
		}
	}

	@Override
	public <T> T tx(TxAction<T> txHandler) {
		/**
		 * OrientDB uses the MVCC pattern which requires a retry of the code that manipulates the graph in cases where for example an
		 * {@link OConcurrentModificationException} is thrown.
		 */
		T handlerResult = null;
		boolean handlerFinished = false;
		int maxRetry = options.getStorageOptions().getTxRetryLimit();
		Throwable cause = null;
		for (int retry = 0; retry < maxRetry; retry++) {
			Timer.Sample sample = Timer.start();
			// Check the status to prevent transactions during shutdown
			checkStatus();
			try (Tx tx = tx()) {
				handlerResult = txHandler.handle(tx);
				handlerFinished = true;
				tx.success();
			} catch (OSchemaException e) {
				cause = e;
				log.error("OrientDB schema exception detected.");
				// TODO maybe we should invoke a metadata getschema reload?
				// factory.getTx().getRawGraph().getMetadata().getSchema().reload();
				// Database.getThreadLocalGraph().getMetadata().getSchema().reload();
			} catch (InterruptedException | ONeedRetryException | FastNoSuchElementException e) {
				cause = e;
				if (log.isTraceEnabled()) {
					log.trace("Error while handling transaction. Retrying " + retry, e);
				}
				int delay = options.getStorageOptions().getTxRetryDelay();
				if (retry > 0 && delay > 0) {
					try {
						Thread.sleep(delay);
					} catch (InterruptedException e1) {
						e1.printStackTrace();
					}
				}
				// Reset previous result
				handlerFinished = false;
				handlerResult = null;
			} catch (ORecordDuplicatedException e) {
				log.error(e);
				throw error(INTERNAL_SERVER_ERROR, "error_internal");
			} catch (GenericRestException e) {
				// Don't log. Just throw it along so that others can handle it
				throw e;
			} catch (RuntimeException e) {
				if (log.isDebugEnabled()) {
					log.debug("Error handling transaction", e);
				}
				throw e;
			} catch (Exception e) {
				if (log.isDebugEnabled()) {
					log.debug("Error handling transaction", e);
				}
				throw new RuntimeException("Transaction error", e);
			} finally {
				sample.stop(txTimer);
			}
			if (!handlerFinished && log.isDebugEnabled()) {
				log.debug("Retrying .. {" + retry + "}");
				if (metrics.isEnabled()) {
					txRetryCounter.increment();
				}
			}
			if (handlerFinished) {
				return handlerResult;
			}
		}
		throw new RuntimeException("Retry limit {" + maxRetry + "} for trx exceeded", cause);
	}

	private void checkStatus() {
		MeshStatus status = mesh.getStatus();
		switch (status) {
		case READY:
		case STARTING:
			return;
		default:
			throw new RuntimeException("Mesh is not ready. Current status " + status.name() + ". Aborting transaction.");
		}
	}

	@Override
	public String backupGraph(String backupDirectory) throws IOException {
		return txProvider.backup(backupDirectory);
	}

	@Override
	public void restoreGraph(String backupFile) throws IOException {
		txProvider.restore(backupFile);
	}

	@Override
	public void exportGraph(String outputDirectory) throws IOException {
		txProvider.exportGraph(outputDirectory);
	}

	@Override
	public void importGraph(String importFile) throws IOException {
		txProvider.importGraph(importFile);
	}

	@Override
	public String getElementVersion(Element element) {
		if (element instanceof WrappedVertex) {
			element = ((WrappedVertex) element).getBaseElement();
		}
		OrientElement e = (OrientElement) element;
		String uuid = element.getProperty("uuid");
		return ETag.hash(uuid + e.getRecord().getVersion());
	}

	@Override
	public String getVendorName() {
		return "orientdb";
	}

	@Override
	public String getVersion() {
		return OConstants.getVersion();
	}

	@Override
	public OrientDBTypeHandler type() {
		return typeHandler;
	}

	@Override
	public OrientDBIndexHandler index() {
		return indexHandler;
	}

	public OrientStorage getTxProvider() {
		return txProvider;
	}

	/**
	 * Return the orientdb cluster manager.
	 */
	public OrientDBClusterManager clusterManager() {
		return clusterManager;
	}

	@Override
	public List<String> getChangeUuidList() {
		return ChangesList.getList(options).stream().map(c -> c.getUuid()).collect(Collectors.toList());
	}

	@Override
	public ClusterConfigResponse loadClusterConfig() {
		if (clusterManager() != null) {
			OHazelcastPlugin plugin = clusterManager().getHazelcastPlugin();
			ODistributedConfiguration storageCfg = plugin.getDatabaseConfiguration(GraphStorage.DB_NAME);

			ClusterConfigResponse response = new ClusterConfigResponse();
			for (String server : storageCfg.getAllConfiguredServers()) {
				ClusterServerConfig serverConfig = new ClusterServerConfig();
				serverConfig.setName(server);

				ROLES role = storageCfg.getServerRole(server);
				ServerRole restRole = ServerRole.valueOf(role.name());
				serverConfig.setRole(restRole);

				response.getServers().add(serverConfig);
			}

			Object writeQuorum = storageCfg.getDocument().getProperty("writeQuorum");
			if (writeQuorum instanceof String) {
				response.setWriteQuorum((String) writeQuorum);
			} else if (writeQuorum instanceof Integer) {
				response.setWriteQuorum(String.valueOf((Integer) writeQuorum));
			}

			Integer readQuorum = storageCfg.getDocument().getProperty("readQuorum");
			response.setReadQuorum(readQuorum);
			return response;
		} else {
			throw error(BAD_REQUEST, "error_cluster_status_only_available_in_cluster_mode");
		}
	}

	@Override
	public void setToMaster() {
		OHazelcastPlugin plugin = clusterManager().getHazelcastPlugin();
		ODistributedConfiguration storageCfg = plugin.getDatabaseConfiguration(GraphStorage.DB_NAME);
		final OModifiableDistributedConfiguration newCfg = storageCfg.modify();
		for (String server : storageCfg.getAllConfiguredServers()) {
			boolean isSelf = server.equals(options.getNodeName());
			ROLES newORole = isSelf ? ROLES.MASTER : ROLES.REPLICA;
			newCfg.setServerRole(server, newORole);
		}
		plugin.updateCachedDatabaseConfiguration(GraphStorage.DB_NAME, newCfg, true);
	}

	@Override
	public void updateClusterConfig(ClusterConfigRequest request) {
		if (clusterManager() != null) {
			OHazelcastPlugin plugin = clusterManager().getHazelcastPlugin();
			ODistributedConfiguration storageCfg = plugin.getDatabaseConfiguration(GraphStorage.DB_NAME);
			final OModifiableDistributedConfiguration newCfg = storageCfg.modify();

			for (ClusterServerConfig server : request.getServers()) {
				// Check whether role changed
				ServerRole newRole = server.getRole();
				ROLES newORole = ROLES.valueOf(newRole.name());
				ROLES oldRole = newCfg.getServerRole(server.getName());
				if (oldRole != newORole) {
					log.debug("Updating server role {" + server.getName() + "} from {" + oldRole + "} to {" + newRole + "}");
					newCfg.setServerRole(server.getName(), newORole);
				}
			}
			String newWriteQuorum = request.getWriteQuorum();
			if (newWriteQuorum != null) {
				if (newWriteQuorum.equalsIgnoreCase("all") || newWriteQuorum.equalsIgnoreCase("majority")) {
					newCfg.getDocument().setProperty("writeQuorum", newWriteQuorum);
				} else {
					try {
						int newWriteQuorumInt = Integer.parseInt(newWriteQuorum);
						newCfg.getDocument().setProperty("writeQuorum", newWriteQuorumInt);
					} catch (Exception e) {
						throw new RuntimeException("Unsupported write quorum value {" + newWriteQuorum + "}");
					}
				}
			}

			Integer newReadQuorum = request.getReadQuorum();
			if (newReadQuorum != null) {
				newCfg.getDocument().setProperty("readQuorum", newReadQuorum);
			}

			// force hazelcast plugin to increase version of the distributed configuration.
			// This is needed because if there are changes only in document properties (e.g. writeQuorum or readQuorum)
			// the plugin won't detect them
			// see https://github.com/orientechnologies/orientdb/blob/3.1.x/distributed/src/main/java/com/orientechnologies/orient/server/distributed/impl/ODistributedAbstractPlugin.java#L441
			newCfg.override(newCfg.getDocument());
			plugin.updateCachedDatabaseConfiguration(GraphStorage.DB_NAME, newCfg, true);
		} else {
			throw error(BAD_REQUEST, "error_cluster_status_only_available_in_cluster_mode");
		}
	}

	private void startTxCleanupTask() {
		txCleanupThread = new Thread(() -> {
			while (!Thread.currentThread().isInterrupted()) {
				txCleanUpTask.checkTransactions();
				try {
					// Interval is fixed
					Thread.sleep(500);
				} catch (InterruptedException e1) {
					log.info("Cleanup task stopped");
					break;
				}
			}
		});
		txCleanupThread.setName("mesh-tx-cleanup-task");
		txCleanupThread.start();
	}

	@Override
	public WriteLock writeLock() {
		return writeLock;
	}

	@Override
	public boolean isEmptyDatabase() {
		return tx(tx -> !tx.getGraph().v().hasNext());
	}

	@Override
	public boolean requiresTypeInit() {
		return true;
	}

	@Override
	public boolean isReadOnly(boolean logError) {
		if (diskQuotaExceeded) {
			if (logError) {
				log.error("Local instance is read-only due to limited disk space.");
			} else {
				log.warn("Local instance is read-only due to limited disk space.");
			}
			return true;
		} else {
			Optional<String> readOnlyInstance = clusterManager.getInstanceDiskQuotaExceeded();
			if (readOnlyInstance.isPresent()) {
				if (logError) {
					log.error("Instance " + readOnlyInstance.get() + " is read-only due to limited disk space.");
				} else {
					log.warn("Instance " + readOnlyInstance.get() + " is read-only due to limited disk space.");
				}
				return true;
			} else {
				return false;
			}
		}
	}

	/**
	 * Start the disk quota checker, if configured to do so and not started before
	 */
	private void startDiskQuotaChecker() {
		if (diskQuotaChecker == null && options.getStorageOptions() != null
				&& options.getStorageOptions().getDirectory() != null
				&& options.getStorageOptions().getDiskQuotaOptions().getCheckInterval() > 0) {
			if (log.isDebugEnabled()) {
				log.debug("Starting disk quota checker");
			}
			diskQuotaChecker = diskQuotaCheckerService.scheduleAtFixedRate(
					new DiskQuotaChecker(new File(options.getStorageOptions().getDirectory()),
							options.getStorageOptions().getDiskQuotaOptions(), this::setDiskQuotaExceededStatus),
					0, options.getStorageOptions().getDiskQuotaOptions().getCheckInterval(), TimeUnit.MILLISECONDS);
		}
	}

	/**
	 * Set the disk-quota-exceeded status locally and in the cluster (if clustering is enabled)
	 * @param result result of the disk quota checker as triple of disk-quota-exceeded status, total space and usable space
	 */
	private void setDiskQuotaExceededStatus(Triple<Boolean, Long, Long> result) {
		this.diskQuotaExceeded = result.getLeft();
		this.clusterManager.setLocalMemberDiskQuotaExceeded(diskQuotaExceeded);
		if (this.totalDiskSpace != null) {
			this.totalDiskSpace.set(result.getMiddle());
	}
		if (this.usableDiskSpace != null) {
			this.usableDiskSpace.set(result.getRight());
		}
	}

	/**
	 * Stop the disk quota checker (if started before)
	 */
	private void stopDiskQuotaChecker() {
		if (diskQuotaChecker != null) {
			if (log.isDebugEnabled()) {
				log.debug("Stopping disk quota checker");
			}
			diskQuotaChecker.cancel(true);
			diskQuotaChecker = null;
		}
	}
}
