package com.booking.replication.applier.hbase.writer;

import com.booking.replication.applier.hbase.mutation.HBaseApplierMutationGenerator;
import com.booking.replication.applier.hbase.schema.HBaseSchemaManager;
import com.booking.replication.applier.hbase.util.AugmentedEventRowExtractor;
import com.booking.replication.augmenter.model.event.AugmentedEvent;
import com.booking.replication.augmenter.model.event.DeleteRowsAugmentedEventData;
import com.booking.replication.augmenter.model.event.UpdateRowsAugmentedEventData;
import com.booking.replication.augmenter.model.event.WriteRowsAugmentedEventData;
import com.booking.replication.augmenter.model.row.AugmentedRow;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class HBaseTimeMachineWriter implements HBaseApplierWriter {

        private static final Logger LOG = LogManager.getLogger(HBaseRawEventsWriter.class);

        private final int FLUSH_RETRY_LIMIT = 30;
        private long bufferClearTime = 0L;

        private final String HBASE_COLUMN_DEFAULT_FAMILY_NAME = "d";

        private HBaseSchemaManager hbaseSchemaManager;
        Connection connection;
        Admin admin;
        Collection<AugmentedEvent> buffered;
        HBaseApplierMutationGenerator mutationGenerator;

        public HBaseTimeMachineWriter(Configuration hbaseConfig,
                                      HBaseSchemaManager hbaseSchemaManager,
                                      Map<String, Object> configuration)
                throws IOException, NoSuchAlgorithmException {

            this.hbaseSchemaManager = hbaseSchemaManager;
            connection = ConnectionFactory.createConnection(hbaseConfig);
            admin = connection.getAdmin();
            buffered = new ArrayList<>();
            mutationGenerator = new HBaseApplierMutationGenerator(configuration);
        }

        @Override
        public void buffer(Collection<AugmentedEvent> events) {
            for (AugmentedEvent event : events) {
                buffered.add(event);
            }
        }

        @Override
        public long getBufferClearTime() {
            return bufferClearTime;
        }

        @Override
        public int getBufferSize() {
            return buffered.size();
        }

        @Override
        public boolean flush() {
            boolean result = false;
            try {
                result = flushWithRetry(); // false means all retries have failed
                buffered.clear();
                bufferClearTime = Instant.now().toEpochMilli();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return result;
        }

        private Boolean flushWithRetry() throws InterruptedException {

            int counter = FLUSH_RETRY_LIMIT;

            while (counter > 0) {
                counter--;

                try {
                    writeToHBase(buffered);
                    return true;
                } catch (IOException e) {
                    LOG.warn("Failed to write to HBase.", e);
                }
                Thread.sleep(1000); // TODO: exponential backoff
            }
            return false;
        }

        private void writeToHBase(Collection<AugmentedEvent> events) throws IOException {

            List<HBaseApplierMutationGenerator.PutMutation> mutations = new ArrayList<>();

            // extract augmented rows
            for (AugmentedEvent event : events) {

                List<AugmentedRow> augmentedRows = AugmentedEventRowExtractor.extractAugmentedRows(event);

                List<String> tables =  augmentedRows.stream().map(ar -> ar.getTableName()).collect(Collectors.toList());
                for (String t:tables) {
                    LOG.info("0000 " + t);
                }

                String eventTableName;
                switch (event.getHeader().getEventType()) {
                    case WRITE_ROWS:
                        eventTableName = ((WriteRowsAugmentedEventData) event.getData()).getEventTable().getName();
                        LOG.info("^^^^" + eventTableName);
                        break;
                    case UPDATE_ROWS:
                        eventTableName = ((UpdateRowsAugmentedEventData) event.getData()).getEventTable().getName();
                        LOG.info("^^^^" + eventTableName);
                        break;
                    case DELETE_ROWS:
                        eventTableName = ((DeleteRowsAugmentedEventData) event.getData()).getEventTable().getName();
                        LOG.info("^^^^" + eventTableName);
                        break;
                    default:
                        throw new RuntimeException("Impossible condition!");
                }

                if (eventTableName != null) {
                    try {
                        hbaseSchemaManager.createMirroredTableIfNotExists(eventTableName);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                } else {
                    LOG.info("eventTableName is NULL!");
                    throw new RuntimeException("Table name cannot be null");
                }

                List<HBaseApplierMutationGenerator.PutMutation> eventMutations = augmentedRows.stream()
                        .flatMap(
                                row -> Stream.of(mutationGenerator.getPutForMirroredTable(row))
                        )
                        .collect(Collectors.toList());
                mutations.addAll(eventMutations);
            }

            // group by table
            Map<String, List<HBaseApplierMutationGenerator.PutMutation>> mutationsByTable =
                    mutations
                            .stream()
                            .collect(
                                    Collectors.groupingBy( mutation -> mutation.getTable() )
                            );

            // write to hbase
            for (Map.Entry<String, List<HBaseApplierMutationGenerator.PutMutation>> entry : mutationsByTable.entrySet()){

                String tableName = entry.getKey();
                List<HBaseApplierMutationGenerator.PutMutation> tableMutations = entry.getValue();

                    List<Put> putList = tableMutations.stream().map(
                            mutation -> mutation.getPut()
                    ).collect(Collectors.toList());

                    Table table = connection.getTable(TableName.valueOf(tableName));
                    table.put(putList);
                    table.close();
                    // TODO: send sample to validator
            }
        }
}
