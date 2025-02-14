/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.spark.procedure;

import org.apache.paimon.CoreOptions;
import org.apache.paimon.annotation.VisibleForTesting;
import org.apache.paimon.append.AppendOnlyCompactionTask;
import org.apache.paimon.append.AppendOnlyTableCompactionCoordinator;
import org.apache.paimon.data.BinaryRow;
import org.apache.paimon.disk.IOManager;
import org.apache.paimon.operation.AppendOnlyFileStoreWrite;
import org.apache.paimon.options.Options;
import org.apache.paimon.predicate.Predicate;
import org.apache.paimon.predicate.PredicateBuilder;
import org.apache.paimon.spark.DynamicOverWrite$;
import org.apache.paimon.spark.SparkUtils;
import org.apache.paimon.spark.commands.WriteIntoPaimonTable;
import org.apache.paimon.spark.sort.TableSorter;
import org.apache.paimon.table.AppendOnlyFileStoreTable;
import org.apache.paimon.table.BucketMode;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.sink.BatchTableCommit;
import org.apache.paimon.table.sink.BatchTableWrite;
import org.apache.paimon.table.sink.BatchWriteBuilder;
import org.apache.paimon.table.sink.CommitMessage;
import org.apache.paimon.table.sink.CompactionTaskSerializer;
import org.apache.paimon.table.sink.TableCommitImpl;
import org.apache.paimon.table.source.DataSplit;
import org.apache.paimon.table.source.InnerTableScan;
import org.apache.paimon.utils.Pair;
import org.apache.paimon.utils.ParameterUtils;
import org.apache.paimon.utils.Preconditions;
import org.apache.paimon.utils.StringUtils;

import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.connector.catalog.Identifier;
import org.apache.spark.sql.connector.catalog.TableCatalog;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.Metadata;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;

import javax.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.apache.spark.sql.types.DataTypes.StringType;

/**
 * Compact procedure. Usage:
 *
 * <pre><code>
 *  CALL sys.compact(table => 'tableId', [partitions => 'p1;p2'], [order_strategy => 'xxx'], [order_by => 'xxx'])
 * </code></pre>
 */
public class CompactProcedure extends BaseProcedure {

    private static final ProcedureParameter[] PARAMETERS =
            new ProcedureParameter[] {
                ProcedureParameter.required("table", StringType),
                ProcedureParameter.optional("partitions", StringType),
                ProcedureParameter.optional("order_strategy", StringType),
                ProcedureParameter.optional("order_by", StringType),
            };

    private static final StructType OUTPUT_TYPE =
            new StructType(
                    new StructField[] {
                        new StructField("result", DataTypes.BooleanType, true, Metadata.empty())
                    });

    protected CompactProcedure(TableCatalog tableCatalog) {
        super(tableCatalog);
    }

    @Override
    public ProcedureParameter[] parameters() {
        return PARAMETERS;
    }

    @Override
    public StructType outputType() {
        return OUTPUT_TYPE;
    }

    @Override
    public InternalRow[] call(InternalRow args) {
        Preconditions.checkArgument(args.numFields() >= 1);
        Identifier tableIdent = toIdentifier(args.getString(0), PARAMETERS[0].name());
        String partitions = blank(args, 1) ? null : args.getString(1);
        String sortType = blank(args, 2) ? TableSorter.OrderType.NONE.name() : args.getString(2);
        List<String> sortColumns =
                blank(args, 3)
                        ? Collections.emptyList()
                        : Arrays.asList(args.getString(3).split(","));
        if (TableSorter.OrderType.NONE.name().equals(sortType) && !sortColumns.isEmpty()) {
            throw new IllegalArgumentException(
                    "order_strategy \"none\" cannot work with order_by columns.");
        }

        return modifyPaimonTable(
                tableIdent,
                table -> {
                    Preconditions.checkArgument(table instanceof FileStoreTable);
                    InternalRow internalRow =
                            newInternalRow(
                                    execute(
                                            (FileStoreTable) table,
                                            sortType,
                                            sortColumns,
                                            partitions));
                    return new InternalRow[] {internalRow};
                });
    }

    @Override
    public String description() {
        return "This procedure execute compact action on paimon table.";
    }

    private boolean blank(InternalRow args, int index) {
        return args.isNullAt(index) || StringUtils.isBlank(args.getString(index));
    }

    private boolean execute(
            FileStoreTable table,
            String sortType,
            List<String> sortColumns,
            @Nullable String partitions) {
        table = table.copy(Collections.singletonMap(CoreOptions.WRITE_ONLY.key(), "false"));
        BucketMode bucketMode = table.bucketMode();
        TableSorter.OrderType orderType = TableSorter.OrderType.of(sortType);

        if (orderType.equals(TableSorter.OrderType.NONE)) {
            JavaSparkContext javaSparkContext = new JavaSparkContext(spark().sparkContext());
            Predicate filter =
                    StringUtils.isBlank(partitions)
                            ? null
                            : PredicateBuilder.partitions(
                                    ParameterUtils.getPartitions(partitions), table.rowType());
            switch (bucketMode) {
                case FIXED:
                case DYNAMIC:
                    compactAwareBucketTable(table, filter, javaSparkContext);
                    break;
                case UNAWARE:
                    compactUnAwareBucketTable(table, filter, javaSparkContext);
                    break;
                default:
                    throw new UnsupportedOperationException(
                            "Spark compact with " + bucketMode + " is not support yet.");
            }
        } else {
            switch (bucketMode) {
                case UNAWARE:
                    sortCompactUnAwareBucketTable(table, orderType, sortColumns, partitions);
                    break;
                default:
                    throw new UnsupportedOperationException(
                            "Spark compact with sort_type "
                                    + sortType
                                    + " only support unaware-bucket append-only table yet.");
            }
        }
        return true;
    }

    private void compactAwareBucketTable(
            FileStoreTable table, @Nullable Predicate filter, JavaSparkContext javaSparkContext) {
        InnerTableScan scan = table.newScan();
        if (filter != null) {
            scan.withFilter(filter);
        }

        List<Pair<BinaryRow, Integer>> partitionBuckets =
                scan.plan().splits().stream()
                        .map(split -> (DataSplit) split)
                        .map(dataSplit -> Pair.of(dataSplit.partition(), dataSplit.bucket()))
                        .distinct()
                        .collect(Collectors.toList());

        if (partitionBuckets.isEmpty()) {
            return;
        }

        BatchWriteBuilder writeBuilder = table.newBatchWriteBuilder();
        JavaRDD<CommitMessage> commitMessageJavaRDD =
                javaSparkContext
                        .parallelize(partitionBuckets)
                        .mapPartitions(
                                (FlatMapFunction<Iterator<Pair<BinaryRow, Integer>>, CommitMessage>)
                                        pairIterator -> {
                                            IOManager ioManager = SparkUtils.createIOManager();
                                            BatchTableWrite write = writeBuilder.newWrite();
                                            write.withIOManager(ioManager);
                                            try {
                                                while (pairIterator.hasNext()) {
                                                    Pair<BinaryRow, Integer> pair =
                                                            pairIterator.next();
                                                    write.compact(
                                                            pair.getLeft(), pair.getRight(), true);
                                                }
                                                return write.prepareCommit().iterator();
                                            } finally {
                                                write.close();
                                                ioManager.close();
                                            }
                                        });

        try (BatchTableCommit commit = writeBuilder.newCommit()) {
            commit.commit(commitMessageJavaRDD.collect());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void compactUnAwareBucketTable(
            FileStoreTable table, @Nullable Predicate filter, JavaSparkContext javaSparkContext) {
        AppendOnlyFileStoreTable fileStoreTable = (AppendOnlyFileStoreTable) table;
        List<AppendOnlyCompactionTask> compactionTasks =
                new AppendOnlyTableCompactionCoordinator(fileStoreTable, false, filter).run();
        if (compactionTasks.isEmpty()) {
            return;
        }

        CompactionTaskSerializer serializer = new CompactionTaskSerializer();
        List<byte[]> serializedTasks = new ArrayList<>();
        try {
            for (AppendOnlyCompactionTask compactionTask : compactionTasks) {
                serializedTasks.add(serializer.serialize(compactionTask));
            }
        } catch (IOException e) {
            throw new RuntimeException("serialize compaction task failed");
        }

        String commitUser = UUID.randomUUID().toString();
        JavaRDD<CommitMessage> commitMessageJavaRDD =
                javaSparkContext
                        .parallelize(serializedTasks)
                        .mapPartitions(
                                (FlatMapFunction<Iterator<byte[]>, CommitMessage>)
                                        taskIterator -> {
                                            AppendOnlyFileStoreWrite write =
                                                    fileStoreTable.store().newWrite(commitUser);
                                            CompactionTaskSerializer ser =
                                                    new CompactionTaskSerializer();
                                            ArrayList<CommitMessage> messages = new ArrayList<>();
                                            try {
                                                while (taskIterator.hasNext()) {
                                                    AppendOnlyCompactionTask task =
                                                            ser.deserialize(
                                                                    ser.getVersion(),
                                                                    taskIterator.next());
                                                    messages.add(task.doCompact(write));
                                                }
                                                return messages.iterator();
                                            } finally {
                                                write.close();
                                            }
                                        });

        try (TableCommitImpl commit = table.newCommit(commitUser)) {
            commit.commit(commitMessageJavaRDD.collect());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void sortCompactUnAwareBucketTable(
            FileStoreTable table,
            TableSorter.OrderType orderType,
            List<String> sortColumns,
            @Nullable String partitions) {
        Dataset<Row> row =
                spark().read().format("paimon").load(table.coreOptions().path().toString());
        row = StringUtils.isBlank(partitions) ? row : row.where(toWhere(partitions));
        new WriteIntoPaimonTable(
                        table,
                        DynamicOverWrite$.MODULE$,
                        TableSorter.getSorter(table, orderType, sortColumns).sort(row),
                        new Options())
                .run(spark());
    }

    @VisibleForTesting
    static String toWhere(String partitions) {
        List<Map<String, String>> maps = ParameterUtils.getPartitions(partitions.split(";"));

        return maps.stream()
                .map(
                        a ->
                                a.entrySet().stream()
                                        .map(entry -> entry.getKey() + "=" + entry.getValue())
                                        .reduce((s0, s1) -> s0 + " AND " + s1))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(a -> "(" + a + ")")
                .reduce((a, b) -> a + " OR " + b)
                .orElse(null);
    }

    public static ProcedureBuilder builder() {
        return new BaseProcedure.Builder<CompactProcedure>() {
            @Override
            public CompactProcedure doBuild() {
                return new CompactProcedure(tableCatalog());
            }
        };
    }
}
