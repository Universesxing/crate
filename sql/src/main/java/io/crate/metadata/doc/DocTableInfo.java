/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.metadata.doc;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.SettableFuture;
import io.crate.analyze.AlterPartitionedTableParameterInfo;
import io.crate.analyze.TableParameterInfo;
import io.crate.analyze.WhereClause;
import io.crate.analyze.symbol.DynamicReference;
import io.crate.exceptions.ColumnUnknownException;
import io.crate.exceptions.UnavailableShardsException;
import io.crate.metadata.*;
import io.crate.metadata.sys.TableColumn;
import io.crate.metadata.table.ColumnPolicy;
import io.crate.metadata.table.Operation;
import io.crate.metadata.table.ShardedTable;
import io.crate.metadata.table.TableInfo;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateObserver;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.routing.GroupShardsIterator;
import org.elasticsearch.cluster.routing.ShardIterator;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.shard.ShardId;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.*;


public class DocTableInfo implements TableInfo, ShardedTable {

    private final TimeValue routingFetchTimeout;

    private final List<ReferenceInfo> columns;
    private final List<GeneratedReferenceInfo> generatedColumns;
    private final List<ReferenceInfo> partitionedByColumns;
    private final Map<ColumnIdent, IndexReferenceInfo> indexColumns;
    private final ImmutableMap<ColumnIdent, ReferenceInfo> references;
    private final ImmutableMap<ColumnIdent, String> analyzers;
    private final TableIdent ident;
    private final List<ColumnIdent> primaryKeys;
    private final ColumnIdent clusteredBy;
    private final String[] concreteIndices;
    private final List<ColumnIdent> partitionedBy;
    private final int numberOfShards;
    private final BytesRef numberOfReplicas;
    private final ImmutableMap<String, Object> tableParameters;
    private final TableColumn docColumn;
    private final ExecutorService executorService;
    private final ClusterService clusterService;
    private final TableParameterInfo tableParameterInfo;
    private static final ESLogger logger = Loggers.getLogger(DocTableInfo.class);

    private final String[] indices;
    private final List<PartitionName> partitions;

    private final boolean isAlias;
    private final boolean hasAutoGeneratedPrimaryKey;
    private final boolean isPartitioned;

    private final ColumnPolicy columnPolicy;
    private final IndexNameExpressionResolver indexNameExpressionResolver;

    public DocTableInfo(TableIdent ident,
                        List<ReferenceInfo> columns,
                        List<ReferenceInfo> partitionedByColumns,
                        List<GeneratedReferenceInfo> generatedColumns,
                        ImmutableMap<ColumnIdent, IndexReferenceInfo> indexColumns,
                        ImmutableMap<ColumnIdent, ReferenceInfo> references,
                        ImmutableMap<ColumnIdent, String> analyzers,
                        List<ColumnIdent> primaryKeys,
                        ColumnIdent clusteredBy,
                        boolean isAlias,
                        boolean hasAutoGeneratedPrimaryKey,
                        String[] concreteIndices,
                        ClusterService clusterService,
                        IndexNameExpressionResolver indexNameExpressionResolver,
                        int numberOfShards,
                        BytesRef numberOfReplicas,
                        ImmutableMap<String, Object> tableParameters,
                        List<ColumnIdent> partitionedBy,
                        List<PartitionName> partitions,
                        ColumnPolicy columnPolicy,
                        ExecutorService executorService) {
        this.indexNameExpressionResolver = indexNameExpressionResolver;
        assert (partitionedBy.size() == partitionedByColumns.size()) : "partitionedBy and partitionedByColumns must have same amount of items in list";
        this.clusterService = clusterService;
        this.columns = columns;
        this.partitionedByColumns = partitionedByColumns;
        this.generatedColumns = generatedColumns;
        this.indexColumns = indexColumns;
        this.references = references;
        this.analyzers = analyzers;
        this.ident = ident;
        this.primaryKeys = primaryKeys;
        this.clusteredBy = clusteredBy;
        this.concreteIndices = concreteIndices;
        this.numberOfShards = numberOfShards;
        this.numberOfReplicas = numberOfReplicas;
        this.tableParameters = tableParameters;
        this.executorService = executorService;
        indices = new String[]{ident.indexName()};
        this.isAlias = isAlias;
        this.hasAutoGeneratedPrimaryKey = hasAutoGeneratedPrimaryKey;
        isPartitioned = !partitionedByColumns.isEmpty();
        this.partitionedBy = partitionedBy;
        this.partitions = partitions;
        this.columnPolicy = columnPolicy;
        if (isPartitioned) {
            tableParameterInfo = new AlterPartitionedTableParameterInfo();
        } else {
            tableParameterInfo = new TableParameterInfo();
        }
        // scale the fetchrouting timeout by n# of partitions
        this.routingFetchTimeout = new TimeValue(5 * Math.max(1, this.partitions.size()), TimeUnit.SECONDS);
        this.docColumn = new TableColumn(DocSysColumns.DOC, references);
    }

    @Nullable
    public ReferenceInfo getReferenceInfo(ColumnIdent columnIdent) {
        ReferenceInfo referenceInfo = references.get(columnIdent);
        if (referenceInfo == null) {
            return docColumn.getReferenceInfo(ident(), columnIdent);
        }
        return referenceInfo;
    }


    @Override
    public Collection<ReferenceInfo> columns() {
        return columns;
    }

    public List<GeneratedReferenceInfo> generatedColumns() {
        return generatedColumns;
    }

    @Override
    public RowGranularity rowGranularity() {
        return RowGranularity.DOC;
    }

    @Override
    public TableIdent ident() {
        return ident;
    }

    private void processShardRouting(Map<String, Map<String, List<Integer>>> locations, ShardRouting shardRouting) {
        String node = shardRouting.currentNodeId();
        Map<String, List<Integer>> nodeMap = locations.get(node);
        if (nodeMap == null) {
            nodeMap = new TreeMap<>();
            locations.put(shardRouting.currentNodeId(), nodeMap);
        }

        List<Integer> shards = nodeMap.get(shardRouting.getIndex());
        if (shards == null) {
            shards = new ArrayList<>();
            nodeMap.put(shardRouting.getIndex(), shards);
        }
        shards.add(shardRouting.id());
    }

    private GroupShardsIterator getShardIterators(WhereClause whereClause,
                                                  @Nullable String preference,
                                                  ClusterState clusterState) throws IndexNotFoundException {
        String[] routingIndices = concreteIndices;
        if (whereClause.partitions().size() > 0) {
            routingIndices = whereClause.partitions().toArray(new String[whereClause.partitions().size()]);
        }

        Map<String, Set<String>> routingMap = null;
        if (whereClause.clusteredBy().isPresent()) {
            routingMap = indexNameExpressionResolver.resolveSearchRouting(
                    clusterState, whereClause.routingValues(), routingIndices);
        }
        return clusterService.operationRouting().searchShards(
                clusterState,
                routingIndices,
                routingMap,
                preference
        );
    }

    @Nullable
    private Routing getRouting(ClusterState state, WhereClause whereClause, String preference, final List<ShardId> missingShards) {
        final Map<String, Map<String, List<Integer>>> locations = new TreeMap<>();
        GroupShardsIterator shardIterators;
        try {
            shardIterators = getShardIterators(whereClause, preference, state);
        } catch (IndexNotFoundException e) {
            return new Routing(locations);
        }

        fillLocationsFromShardIterators(locations, shardIterators, missingShards);

        if (missingShards.isEmpty()) {
            return new Routing(locations);
        } else {
            return null;
        }
    }

    @Override
    public Routing getRouting(final WhereClause whereClause, @Nullable final String preference) {
        Routing routing = getRouting(clusterService.state(), whereClause, preference, new ArrayList<ShardId>(0));
        if (routing != null) return routing;

        ClusterStateObserver observer = new ClusterStateObserver(clusterService, routingFetchTimeout, logger);
        final SettableFuture<Routing> routingSettableFuture = SettableFuture.create();
        observer.waitForNextChange(
                new FetchRoutingListener(routingSettableFuture, whereClause, preference),
                new ClusterStateObserver.ChangePredicate() {

                    @Override
                    public boolean apply(ClusterState previousState, ClusterState.ClusterStateStatus previousStatus, ClusterState newState, ClusterState.ClusterStateStatus newStatus) {
                        return validate(newState);
                    }

                    @Override
                    public boolean apply(ClusterChangedEvent changedEvent) {
                        return validate(changedEvent.state());
                    }

                    private boolean validate(ClusterState state) {
                        final Map<String, Map<String, List<Integer>>> locations = new TreeMap<>();

                        GroupShardsIterator shardIterators;
                        try {
                            shardIterators = getShardIterators(whereClause, preference, state);
                        } catch (IndexNotFoundException e) {
                            return true;
                        }

                        final List<ShardId> missingShards = new ArrayList<>(0);
                        fillLocationsFromShardIterators(locations, shardIterators, missingShards);

                        return missingShards.isEmpty();
                    }

                });

        try {
            return routingSettableFuture.get();
        } catch (ExecutionException e) {
            throw Throwables.propagate(e.getCause());
        } catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }

    private void fillLocationsFromShardIterators(Map<String, Map<String, List<Integer>>> locations,
                                                 GroupShardsIterator shardIterators,
                                                 List<ShardId> missingShards) {
        ShardRouting shardRouting;
        for (ShardIterator shardIterator : shardIterators) {
            shardRouting = shardIterator.nextOrNull();
            if (shardRouting != null) {
                if (shardRouting.active()) {
                    processShardRouting(locations, shardRouting);
                } else {
                    missingShards.add(shardIterator.shardId());
                }
            } else {
                if (isPartitioned) {
                    // if the table is partitioned maybe a index/shard just got newly created ...
                    missingShards.add(shardIterator.shardId());
                } else {
                    throw new UnavailableShardsException(shardIterator.shardId());
                }
            }
        }
    }

    public List<ColumnIdent> primaryKey() {
        return primaryKeys;
    }

    @Override
    public int numberOfShards() {
        return numberOfShards;
    }

    @Override
    public BytesRef numberOfReplicas() {
        return numberOfReplicas;
    }

    @Override
    public ColumnIdent clusteredBy() {
        return clusteredBy;
    }

    public boolean hasAutoGeneratedPrimaryKey() {
        return hasAutoGeneratedPrimaryKey;
    }

    /**
     * @return true if this <code>TableInfo</code> is referenced by an alias name, false otherwise
     */
    public boolean isAlias() {
        return isAlias;
    }

    public String[] concreteIndices() {
        return concreteIndices;
    }

    /**
     * columns this table is partitioned by.
     *
     * guaranteed to be in the same order as defined in CREATE TABLE statement
     * @return always a list, never null
     */
    public List<ReferenceInfo> partitionedByColumns() {
        return partitionedByColumns;
    }

    /**
     * column names of columns this table is partitioned by (in dotted syntax).
     *
     * guaranteed to be in the same order as defined in CREATE TABLE statement
     * @return always a list, never null
     */
    public List<ColumnIdent> partitionedBy() {
        return partitionedBy;
    }

    public List<PartitionName> partitions() {
        return partitions;
    }

    /**
     * returns <code>true</code> if this table is a partitioned table,
     * <code>false</code> otherwise
     *
     * if so, {@linkplain #partitions()} returns infos about the concrete indices that make
     * up this virtual partitioned table
     */
    public boolean isPartitioned() {
        return isPartitioned;
    }

    public IndexReferenceInfo indexColumn(ColumnIdent ident) {
        return indexColumns.get(ident);
    }

    public Iterator<IndexReferenceInfo> indexColumns() {
        return indexColumns.values().iterator();
    }

    @Override
    public Iterator<ReferenceInfo> iterator() {
        return references.values().iterator();
    }

    /**
     * return the column policy of this table
     * that defines how adding new columns will be handled.
     * <ul>
     * <li><code>STRICT</code> means no new columns are allowed
     * <li><code>DYNAMIC</code> means new columns will be added to the schema
     * <li><code>IGNORED</code> means new columns will not be added to the schema.
     * those ignored columns can only be selected.
     * </ul>
     */
    public ColumnPolicy columnPolicy() {
        return columnPolicy;
    }

    public TableParameterInfo tableParameterInfo () {
        return tableParameterInfo;
    }

    public ImmutableMap<String, Object> tableParameters() {
        return tableParameters;
    }

    @Override
    public Set<Operation> supportedOperations() {
        return Operation.ALL;
    }

    public String getAnalyzerForColumnIdent(ColumnIdent ident) {
        return analyzers.get(ident);
    }

    private class FetchRoutingListener implements ClusterStateObserver.Listener {

        private final SettableFuture<Routing> routingFuture;
        private final WhereClause whereClause;
        private final String preference;
        Future<?> innerTaskFuture;

        public FetchRoutingListener(SettableFuture<Routing> routingFuture, WhereClause whereClause, String preference) {
            this.routingFuture = routingFuture;
            this.whereClause = whereClause;
            this.preference = preference;
        }

        @Override
        public void onNewClusterState(final ClusterState state) {
            try {
                innerTaskFuture = executorService.submit(new Runnable() {
                    @Override
                    public void run() {
                        final List<ShardId> missingShards = new ArrayList<>(0);
                        Routing routing = getRouting(state, whereClause, preference, missingShards);
                        if (routing == null) {
                            routingFuture.setException(new UnavailableShardsException(missingShards.get(0)));
                        } else {
                            routingFuture.set(routing);
                        }
                    }
                });
            } catch (RejectedExecutionException e) {
                routingFuture.setException(e);
            }
        }

        @Override
        public void onClusterServiceClose() {
            if (innerTaskFuture != null) {
                innerTaskFuture.cancel(true);
            }
            routingFuture.setException(new IllegalStateException("ClusterService closed"));
        }

        @Override
        public void onTimeout(TimeValue timeout) {
            if (innerTaskFuture != null) {
                innerTaskFuture.cancel(true);
            }
            routingFuture.setException(new IllegalStateException("Fetching table info routing timed out."));
        }
    }

    @Nullable
    public DynamicReference getDynamic(ColumnIdent ident, boolean forWrite) {
        boolean parentIsIgnored = false;
        ColumnPolicy parentPolicy = columnPolicy();
        if (!ident.isColumn()) {
            // see if parent is strict object
            ColumnIdent parentIdent = ident.getParent();
            ReferenceInfo parentInfo = null;

            while (parentIdent != null) {
                parentInfo = getReferenceInfo(parentIdent);
                if (parentInfo != null) {
                    break;
                }
                parentIdent = parentIdent.getParent();
            }

            if (parentInfo != null) {
                parentPolicy = parentInfo.columnPolicy();
            }
        }

        switch (parentPolicy) {
            case DYNAMIC:
                if (!forWrite) return null;
                break;
            case STRICT:
                if (forWrite) throw new ColumnUnknownException(ident.sqlFqn());
                return null;
            case IGNORED:
                parentIsIgnored = true;
                break;
            default:
                break;
        }
        DynamicReference reference = new DynamicReference(new ReferenceIdent(ident(), ident), rowGranularity());
        if (parentIsIgnored) {
            reference.columnPolicy(ColumnPolicy.IGNORED);
        }
        return reference;
    }

    @Override
    public String toString() {
        return ident.fqn();
    }
}
