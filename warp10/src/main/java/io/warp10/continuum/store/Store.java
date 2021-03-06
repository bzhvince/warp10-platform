//
//   Copyright 2016  Cityzen Data
//
//   Licensed under the Apache License, Version 2.0 (the "License");
//   you may not use this file except in compliance with the License.
//   You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
//   Unless required by applicable law or agreed to in writing, software
//   distributed under the License is distributed on an "AS IS" BASIS,
//   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//   See the License for the specific language governing permissions and
//   limitations under the License.
//

package io.warp10.continuum.store;

import io.warp10.continuum.KafkaOffsetCounters;
import io.warp10.continuum.gts.GTSDecoder;
import io.warp10.continuum.gts.GTSEncoder;
import io.warp10.continuum.gts.GTSHelper;
import io.warp10.continuum.sensision.SensisionConstants;
import io.warp10.continuum.store.thrift.data.KafkaDataMessage;
import io.warp10.continuum.store.thrift.data.KafkaDataMessageType;
import io.warp10.continuum.store.thrift.data.Metadata;
import io.warp10.crypto.CryptoUtils;
import io.warp10.crypto.KeyStore;
import io.warp10.sensision.Sensision;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

import kafka.consumer.Consumer;
import kafka.consumer.ConsumerConfig;
import kafka.consumer.ConsumerIterator;
import kafka.consumer.KafkaStream;
import kafka.javaapi.consumer.ConsumerConnector;
import kafka.message.MessageAndMetadata;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.client.coprocessor.Batch;
import org.apache.hadoop.hbase.coprocessor.example.generated.BulkDeleteProtos.BulkDeleteRequest;
import org.apache.hadoop.hbase.coprocessor.example.generated.BulkDeleteProtos.BulkDeleteRequest.Builder;
import org.apache.hadoop.hbase.coprocessor.example.generated.BulkDeleteProtos.BulkDeleteRequest.DeleteType;
import org.apache.hadoop.hbase.coprocessor.example.generated.BulkDeleteProtos.BulkDeleteResponse;
import org.apache.hadoop.hbase.coprocessor.example.generated.BulkDeleteProtos.BulkDeleteService;
import org.apache.hadoop.hbase.ipc.BlockingRpcCallback;
import org.apache.hadoop.hbase.ipc.ServerRpcController;
import org.apache.hadoop.hbase.protobuf.ProtobufUtil;
import org.apache.thrift.TDeserializer;
import org.apache.thrift.protocol.TCompactProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.primitives.Longs;

/**
 * Class which implements pulling data from Kafka and storing it in
 * HBase
 */
public class Store extends Thread {
  
  private static final Logger LOG = LoggerFactory.getLogger(Store.class);
  
  /**
   * Prefix for 'raw' (individual datapoints) data
   */
  public static final byte[] HBASE_RAW_DATA_KEY_PREFIX = "R".getBytes(Charsets.UTF_8);

  /**
   * Prefix for 'archived' data
   */
  public static final byte[] HBASE_ARCHIVE_DATA_KEY_PREFIX = "A".getBytes(Charsets.UTF_8);
  

  /**
   * Set of required parameters, those MUST be set
   */
  private static final String[] REQUIRED_PROPERTIES = new String[] {
    io.warp10.continuum.Configuration.STORE_ZK_QUORUM,
    io.warp10.continuum.Configuration.STORE_ZK_ZNODE,
    io.warp10.continuum.Configuration.STORE_NTHREADS,
    io.warp10.continuum.Configuration.STORE_KAFKA_DATA_ZKCONNECT,
    io.warp10.continuum.Configuration.STORE_KAFKA_DATA_BROKERLIST,
    io.warp10.continuum.Configuration.STORE_KAFKA_DATA_TOPIC,
    io.warp10.continuum.Configuration.STORE_KAFKA_DATA_GROUPID,
    io.warp10.continuum.Configuration.STORE_KAFKA_DATA_COMMITPERIOD,
    io.warp10.continuum.Configuration.STORE_HBASE_DATA_ZKCONNECT,
    io.warp10.continuum.Configuration.STORE_HBASE_DATA_TABLE,
    io.warp10.continuum.Configuration.STORE_HBASE_DATA_COLFAM,
    io.warp10.continuum.Configuration.STORE_HBASE_DATA_ZNODE,
    io.warp10.continuum.Configuration.STORE_HBASE_DATA_MAXPENDINGPUTSSIZE,
    io.warp10.continuum.Configuration.STORE_KAFKA_DATA_INTERCOMMITS_MAXTIME,
  };

  /**
   * Keystore
   */
  private final KeyStore keystore;

  /**
   * Column family under which to store the readings
   */
  private final byte[] colfam;

  /**
   * Flag indicating an abort
   */
  private final AtomicBoolean abort = new AtomicBoolean(false);

  /**
   * CyclicBarrier for synchronizing producers prior to committing the offsets
   */
  private CyclicBarrier barrier;
  
  /**
   * Number of milliseconds between offsets commits
   */
  private final long commitPeriod;

  /**
   * Maximum number of milliseconds between offsets commits. This limit is there so we detect hang calls to htable.batch
   */
  private final long maxTimeBetweenCommits;
  
  /**
   * Maximum size we allow the pending Puts list to grow
   */
  private final long maxPendingPutsSize;
  
  /**
   * Pool used to retrieve HTableInterface instances
   */
  //private final HTablePool htpool;
  
  /**
   * Connection to HBase
   */
  private final Connection conn;
  
  /**
   * Boolean indicating that we should recreate the connection to HBase
   * due to IOErrors (such as regions which cannot be found due to region location cache not being updated)
   */
  private AtomicBoolean connReset = new AtomicBoolean(false);
  
  /**
   * HBase table where readings should be stored
   */
  private final TableName hbaseTable;
  
  private final boolean SKIP_WRITE;
  
  private int generation = 0;
  
  private UUID uuid = UUID.randomUUID();
  
  private static Map<String,Connection> connections = new HashMap<String,Connection>();
  
  public Store(KeyStore keystore, final Properties properties, Integer nthr) throws IOException {
    this.keystore = keystore;

    if ("true".equals(properties.containsKey("store.skip.write"))) {
      this.SKIP_WRITE = true;
    } else {
      this.SKIP_WRITE = false;
    }
    
    //
    // Check mandatory parameters
    //
    
    for (String required: REQUIRED_PROPERTIES) {
      Preconditions.checkNotNull(properties.getProperty(required), "Missing configuration parameter '%s'.", required);          
    }

    //
    // Extract parameters
    //
            
    final String topic = properties.getProperty(io.warp10.continuum.Configuration.STORE_KAFKA_DATA_TOPIC);
    final int nthreads = null != nthr ? nthr.intValue() : Integer.valueOf(properties.getProperty(io.warp10.continuum.Configuration.STORE_NTHREADS));
    
    //
    // Retrieve the connection singleton for this set of properties
    //
    
    this.conn = Store.getHBaseConnection(properties);
    
    this.hbaseTable = TableName.valueOf(properties.getProperty(io.warp10.continuum.Configuration.STORE_HBASE_DATA_TABLE));
    this.colfam = properties.getProperty(io.warp10.continuum.Configuration.STORE_HBASE_DATA_COLFAM).getBytes(Charsets.UTF_8);

    //
    // Extract keys
    //
    
    extractKeys(properties);
    
    final Store self = this;
    
    commitPeriod = Long.parseLong(properties.getProperty(io.warp10.continuum.Configuration.STORE_KAFKA_DATA_COMMITPERIOD));
    maxTimeBetweenCommits = Long.parseLong(properties.getProperty(io.warp10.continuum.Configuration.STORE_KAFKA_DATA_INTERCOMMITS_MAXTIME));
    
    if (maxTimeBetweenCommits <= commitPeriod) {
      throw new RuntimeException(io.warp10.continuum.Configuration.STORE_KAFKA_DATA_INTERCOMMITS_MAXTIME + " MUST be set to a value above that of " + io.warp10.continuum.Configuration.STORE_KAFKA_DATA_COMMITPERIOD);
    }
    
    maxPendingPutsSize = Long.parseLong(properties.getProperty(io.warp10.continuum.Configuration.STORE_HBASE_DATA_MAXPENDINGPUTSSIZE));
    
    final String groupid = properties.getProperty(io.warp10.continuum.Configuration.STORE_KAFKA_DATA_GROUPID);

    final KafkaOffsetCounters counters = new KafkaOffsetCounters(topic, groupid, commitPeriod * 2);

    Thread t = new Thread(new Runnable() {
      @Override
      public void run() {
        
        ExecutorService executor = null;
        ConsumerConnector connector = null;
        
        StoreConsumer[] consumers = new StoreConsumer[nthreads];
        Table[] tables = new Table[nthreads];
        
        for (int i = 0; i < nthreads; i++) {
          try {
            tables[i] = conn.getTable(hbaseTable);
          } catch (IOException ioe) {
            throw new RuntimeException(ioe);
          }
        }

        while(true) {
          try {
            //
            // Enter an endless loop which will spawn 'nthreads' threads
            // each time the Kafka consumer is shut down (which will happen if an error
            // happens while talking to HBase, to get a chance to re-read data from the
            // previous snapshot).
            //
            
            Map<String,Integer> topicCountMap = new HashMap<String, Integer>();
              
            topicCountMap.put(topic, nthreads);
                          
            Properties props = new Properties();
            props.setProperty("zookeeper.connect", properties.getProperty(io.warp10.continuum.Configuration.STORE_KAFKA_DATA_ZKCONNECT));
            props.setProperty("group.id", groupid);
            if (null != properties.getProperty(io.warp10.continuum.Configuration.STORE_KAFKA_DATA_CONSUMER_CLIENTID)) {
              props.setProperty("client.id", properties.getProperty(io.warp10.continuum.Configuration.STORE_KAFKA_DATA_CONSUMER_CLIENTID));
            }
            props.setProperty("auto.commit.enable", "false");
            //
            // This is VERY important, offset MUST be reset to 'smallest' so we get a chance to store as many datapoints
            // as we can when the lag gets beyond the history Kafka maintains.
            //
            props.setProperty("auto.offset.reset", "smallest");
            
            ConsumerConfig config = new ConsumerConfig(props);
            connector = Consumer.createJavaConsumerConnector(config);

            Map<String,List<KafkaStream<byte[], byte[]>>> consumerMap = connector.createMessageStreams(topicCountMap);
            
            List<KafkaStream<byte[], byte[]>> streams = consumerMap.get(topic);
            
            self.barrier = new CyclicBarrier(streams.size() + 1);
            
            executor = Executors.newFixedThreadPool(nthreads);
            
            //
            // now create runnables which will consume messages
            // We reset the counters when we re-allocate the consumers
            //
            
            counters.reset();
                        
            int idx = 0;
            
            for (final KafkaStream<byte[],byte[]> stream : streams) {
              if (null != consumers[idx] && consumers[idx].getHBaseReset()) {
                try {
                  tables[idx].close();
                } catch (IOException ioe) {                  
                }
                try {
                  tables[idx] = conn.getTable(hbaseTable);
                } catch (IOException ioe) {
                  throw new RuntimeException(ioe);
                }
              }
              consumers[idx] = new StoreConsumer(tables[idx], self, stream, counters);
              executor.submit(consumers[idx]);
              idx++;
            }      
                
            long lastBarrierSync = System.currentTimeMillis();
            
            while(!abort.get()) {
              if (streams.size() == barrier.getNumberWaiting()) {
                //
                // Check if we should abort, which could happen when
                // an exception was thrown when flushing the commits just before
                // entering the barrier
                //
                  
                if (abort.get()) {
                  break;
                }
                 
                //
                // All processing threads are waiting on the barrier, this means we can flush the offsets because
                // they have all processed data successfully for the given activity period
                //
                  
                // Commit offsets
                connector.commitOffsets();
                counters.sensisionPublish();
                Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_STORE_KAFKA_COMMITS, Sensision.EMPTY_LABELS, 1);
                
                // Release the waiting threads
                try {
                  barrier.await();
                  lastBarrierSync = System.currentTimeMillis();
                } catch (Exception e) {
                  break;
                }
              } else if (System.currentTimeMillis() - lastBarrierSync > maxTimeBetweenCommits) {
                //
                // If the last barrier synchronization was more than 'maxTimeBetweenCommits' ms ago
                // we abort, because it probably means the call to htable.batch has hang and
                // we need to respawn the consuming threads.
                //
                // FIXME(hbs): We might have to fix this to be able to tolerate long deletes.
                //
                Sensision.update(SensisionConstants.CLASS_WARP_STORE_KAFKA_COMMITS_OVERDUE, Sensision.EMPTY_LABELS, 1);
                LOG.debug("Last Kafka commit was more than " + maxTimeBetweenCommits + " ms ago, aborting.");
                for (int i = 0; i < consumers.length; i++) {
                  consumers[i].setHBaseReset(true);
                }
                abort.set(true);
              }
              
              LockSupport.parkNanos(1000000L);
            }

          } catch (Throwable t) {
            LOG.error("Caught Throwable while synchronizing", t);
          } finally {
            
            LOG.debug("Spawner reinitializing.");
            
            //
            // We exited the loop, this means one of the threads triggered an abort,
            // we will shut down the executor and shut down the connector to start over.
            //
        
            if (null != connector) {
              LOG.debug("Closing Kafka connector.");
              try {
                connector.shutdown();
              } catch (Exception e) {
                LOG.error("Error while closing connector", e);
              }
            }

            if (null != executor) {
              LOG.debug("Closing executor.");
              try {
                for (StoreConsumer consumer: consumers) {
                  consumer.localabort.set(true);
                  while(consumer.getSynchronizer().isAlive()) {
                    consumer.getSynchronizer().interrupt();
                    LockSupport.parkNanos(100000000L);
                  }   
                  //
                  // We need to shut down the executor prior to waiting for the
                  // consumer threads to die, otherwise they will never die!
                  //
                  executor.shutdownNow();
                  while(!consumer.isDone()) {
                    consumer.getConsumerThread().interrupt();
                    LockSupport.parkNanos(100000000L);
                  }
                }                
              } catch (Exception e) {
                LOG.error("Error while closing executor", e);
              }
            }
            
            //
            // Reset barrier
            //
            
            if (null != barrier) {
              LOG.debug("Resetting barrier.");
              barrier.reset();
            }
            
            LOG.debug("Increasing generation.");
            generation++;
            
            Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_STORE_ABORTS, Sensision.EMPTY_LABELS, 1);
            
            abort.set(false);

            LockSupport.parkNanos(100000000L);
          }
        }
      }
    });
    
    t.setName("[Continuum Store Spawner " + this.uuid + "]");
    t.setDaemon(true);
    t.start();
    
    this.setName("[Continuum Store " + this.uuid + "]");
    this.setDaemon(true);
    this.start();
  }
  
  @Override
  public void run() {
    while (true){      
      LockSupport.parkNanos(1000000000L);
      
      if (!this.connReset.getAndSet(false)) {
        continue;
      }
      
      //ConnectionHelper.clearMetaCache(this.conn);

//      //
//      // Regenerate the HBase connection
//      //
//      
//      Connection newconn = null;
//      Connection oldconn = this.conn;
//      
//      try {
//        newconn = ConnectionFactory.createConnection(this.config);
//      } catch (IOException ioe) {
//        LOG.error("Error while creating HBase connection.", ioe);
//        continue;
//      }
//      
//      if (null != newconn) {
//        this.conn = newconn;
//      } else {
//        continue;
//      }
//      
//      try {
//        if (null != oldconn) {
//          oldconn.close();
//        }
//      } catch (IOException ioe) {
//        LOG.error("Error closing previous HBase connection.", ioe);
//      }
      
      Sensision.update(SensisionConstants.CLASS_WARP_STORE_HBASE_CONN_RESETS, Sensision.EMPTY_LABELS, 1);
    }
  }
  
  private static class StoreConsumer implements Runnable {

    private boolean resetHBase = false;
    private boolean done = false;
    private final Store store;
    private final KafkaStream<byte[],byte[]> stream;
    private final byte[] hbaseAESKey;
    private Table table = null;
    private final AtomicLong lastPut = new AtomicLong(0L);
    private final List<Put> puts;
    private final ReentrantLock putslock;
    private final AtomicLong putsSize = new AtomicLong(0L);
    private final AtomicBoolean localabort = new AtomicBoolean(false);
    private final AtomicBoolean forcecommit = new AtomicBoolean(false);
    private final Semaphore flushsem = new Semaphore(0);
    private final KafkaOffsetCounters counters;
    
    private Thread synchronizer = null;    

    public StoreConsumer(Table table, Store store, KafkaStream<byte[], byte[]> stream, KafkaOffsetCounters counters) {
      this.store = store;
      this.stream = stream;
      this.puts = new ArrayList<Put>();
      this.putslock = new ReentrantLock();
      this.counters = counters;
      this.table = table;
      this.hbaseAESKey = store.keystore.getKey(KeyStore.AES_HBASE_DATA);
    }
    
    private Thread getSynchronizer() {
      return this.synchronizer;
    }
    
    private Thread getConsumerThread() {
      return Thread.currentThread();
    }
    
    private boolean isDone() {
      return this.done;
    }
    
    private boolean getHBaseReset() {
      return this.resetHBase;
    }
    
    private void setHBaseReset(boolean reset) {
      this.resetHBase = reset;
    }
    
    @Override
    public void run() {
      
      Thread.currentThread().setName("[Store Consumer - gen " + this.store.uuid + "/" + store.generation + "]");

      long count = 0L;
            
      try {
        ConsumerIterator<byte[],byte[]> iter = this.stream.iterator();

        byte[] siphashKey = store.keystore.getKey(KeyStore.SIPHASH_KAFKA_DATA);
        byte[] aesKey = store.keystore.getKey(KeyStore.AES_KAFKA_DATA);
        
        final Table ht = table;
        
        //
        // AtomicLong with the timestamp of the last Put or 0 if
        // none were added since the last flush
        //
        
        final StoreConsumer self = this;
        
        //
        // Start the synchronization Thread
        //
        
        final CyclicBarrier ourbarrier = store.barrier;
        
        synchronizer = new Thread(new Runnable() {
          @Override
          public void run() {
            try {
            long lastsync = System.currentTimeMillis();
            
            //
            // Check for how long we've been storing readings, if we've reached the commitperiod,
            // flush any pending commits and synchronize with the other threads so offsets can be committed
            //

            while(!localabort.get() && !synchronizer.isInterrupted()) { 
              long now = System.currentTimeMillis();
              
              if (now - lastsync > store.commitPeriod) {
                //
                // We synchronize on 'puts' so the main Thread does not add Puts to ht
                //
                
                try {
                  putslock.lockInterruptibly();
                  
                  //
                  // Attempt to flush
                  //
                  
                  try {
                    Object[] results = new Object[puts.size()];
                    long nanos = System.nanoTime();
                    if (!store.SKIP_WRITE) {
                      LOG.debug("HBase batch of " + puts.size() + " Puts.");
                      //
                      // TODO(hbs): consider switching to streaming Puts???, i.e. setAutoFlush(false), then series of
                      // calls to Table.put and finally a call to flushCommits to trigger the Kafka commit.
                      //
                      ht.batch(puts, results);
                      // Check results for nulls
                      for (Object o: results) {
                        if (null == o) {
                          throw new IOException("At least one Put failed.");
                        }
                      }    
                    }
                    
                    Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_STORE_HBASE_PUTS_COMMITTED, Sensision.EMPTY_LABELS, puts.size());

                    puts.clear();
                    putsSize.set(0L);
                    nanos = System.nanoTime() - nanos;
                    LOG.debug("HBase batch took " + nanos + " ns.");
                    Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_STORE_HBASE_COMMITS, Sensision.EMPTY_LABELS, 1);
                    Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_STORE_HBASE_TIME_NANOS, Sensision.EMPTY_LABELS, nanos);
                  } catch (InterruptedException ie) {
                    // Clear list of Puts
                    puts.clear();
                    putsSize.set(0L);
                    // If an exception is thrown, abort
                    store.abort.set(true);
                    LOG.error("Received InterruptedException", ie);
                    return;                    
                  } catch (Throwable t) {
                    store.connReset.set(true);
                    // Clear list of Puts
                    puts.clear();
                    putsSize.set(0L);
                    // If an exception is thrown, abort
                    store.abort.set(true);
                    resetHBase = true;
                    LOG.error("Received Throwable while writing to HBase - forcing HBase reset", t);
                    return;
                  }
                  //
                  // Now join the cyclic barrier which will trigger the
                  // commit of offsets
                  //
                  try {                    
                    // We wait at most maxTimeBetweenCommits so we can abort in case the synchronization was too long ago
                    ourbarrier.await(store.maxTimeBetweenCommits, TimeUnit.MILLISECONDS);
                    Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_STORE_BARRIER_SYNCS, Sensision.EMPTY_LABELS, 1);
                  } catch (Exception e) {
                    store.abort.set(true);
                    LOG.debug("Received Exception while await barrier", e);
                    return;
                  } finally {
                    lastsync = System.currentTimeMillis();
                  }
                  
                  if (forcecommit.getAndSet(false)) {
                    flushsem.release();
                  }
                } catch (InterruptedException ie) {
                  store.abort.set(true);
                  return;
                } finally {
                  if (putslock.isHeldByCurrentThread()) {
                    putslock.unlock();
                  }
                }
//                synchronized (puts) {
//                }
              } else if (forcecommit.get() || (0 != lastPut.get() && (now - lastPut.get() > 500) || putsSize.get() > store.maxPendingPutsSize)) {
                //
                // If the last Put was added to 'puts' more than 500ms ago, force a flush
                //
                
                try {
                  putslock.lockInterruptibly();
                  if (!puts.isEmpty()) {
                    try {
                      Object[] results = new Object[puts.size()];
                      long nanos = System.nanoTime();
                      if (!store.SKIP_WRITE) {
                        LOG.debug("Forcing HBase batch of " + puts.size() + " Puts.");
                        ht.batch(puts, results);
                        // Check results for nulls
                        for (Object o: results) {
                          if (null == o) {
                            throw new IOException("At least one Put failed.");
                          }
                        }         
                      }
                      
                      Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_STORE_HBASE_PUTS_COMMITTED, Sensision.EMPTY_LABELS, puts.size());

                      puts.clear();
                      putsSize.set(0L);
                      nanos = System.nanoTime() - nanos;
                      LOG.debug("Forced HBase batch took " + nanos + " ns");
                      Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_STORE_HBASE_COMMITS, Sensision.EMPTY_LABELS, 1);
                      Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_STORE_HBASE_TIME_NANOS, Sensision.EMPTY_LABELS, nanos);
                      // Reset lastPut to 0
                      lastPut.set(0L);
                    } catch (InterruptedException ie) {
                      // Clear list of Puts
                      puts.clear();
                      putsSize.set(0L);
                      // If an exception is thrown, abort
                      store.abort.set(true);
                      LOG.error("Received InterrupedException", ie);
                      return;                    
                    } catch (Throwable t) {
                      // Some errors of HBase are reported as RuntimeException, so we
                      // handle those in a more general Throwable catch clause.
                      // Mark the HBase connection as needing reset
                      store.connReset.set(true);
                      // Clear list of Puts
                      puts.clear();
                      putsSize.set(0L);
                      // If an exception is thrown, abort
                      store.abort.set(true);                      
                      resetHBase = true;
                      LOG.error("Received Throwable while forced writing to HBase - forcing HBase reset", t);
                      return;
                    }
                  }                  
                } catch (InterruptedException ie) {
                  store.abort.set(true);
                  return;
                } finally {
                  if (putslock.isHeldByCurrentThread()) {
                    putslock.unlock();
                  }
                }

//                synchronized(puts) {
//                }
              }
 
              if (forcecommit.getAndSet(false)) {
                flushsem.release();
              }

              LockSupport.parkNanos(1000000L);
            }
            } finally {
              //
              // Attempt to close the Table instance
              //
              //if (null != ht) {
              //  try {
              //    ht.close();
              //  } catch (Exception e) {                  
              // }
              //} 
              LOG.debug("Synchronizer done.");
              localabort.set(true);
              done = true;
            }
          }
        });

        synchronizer.setName("[Continuum Store Synchronizer - gen " + this.store.uuid + "/" + store.generation + "]");
        synchronizer.setDaemon(true);
        synchronizer.start();

        TDeserializer deserializer = new TDeserializer(new TCompactProtocol.Factory());

        // TODO(hbs): allow setting of writeBufferSize

        while (iter.hasNext() && !Thread.currentThread().isInterrupted()) {
          //
          // Since the cal to 'next' may block, we need to first
          // check that there is a message available, otherwise we
          // will miss the synchronization point with the other
          // threads.
          //
          
          boolean nonEmpty = iter.nonEmpty();
          
          if (nonEmpty) {
            
            count++;
            MessageAndMetadata<byte[], byte[]> msg = iter.next();
            self.counters.count(msg.partition(), msg.offset());
            
            byte[] data = msg.message();

            Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_STORE_KAFKA_COUNT, Sensision.EMPTY_LABELS, 1);
            Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_STORE_KAFKA_BYTES, Sensision.EMPTY_LABELS, data.length);
            
            if (null != siphashKey) {
              data = CryptoUtils.removeMAC(siphashKey, data);
            }
            
            // Skip data whose MAC was not verified successfully
            if (null == data) {
              Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_STORE_KAFKA_FAILEDMACS, Sensision.EMPTY_LABELS, 1);
              // TODO(hbs): increment Sensision metric
              continue;
            }
            
            // Unwrap data if need be
            if (null != aesKey) {
              data = CryptoUtils.unwrap(aesKey, data);
            }
            
            // Skip data that was not unwrapped successfuly
            if (null == data) {
              Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_STORE_KAFKA_FAILEDDECRYPTS, Sensision.EMPTY_LABELS, 1);
              // TODO(hbs): increment Sensision metric
              continue;
            }
            
            //
            // Extract KafkaDataMessage
            //
            
            KafkaDataMessage tmsg = new KafkaDataMessage();
            deserializer.deserialize(tmsg, data);
            
            switch(tmsg.getType()) {
              case STORE:
                handleStore(ht, tmsg);              
                break;
              case DELETE:
                handleDelete(ht, tmsg);              
                break;
              case ARCHIVE:
                handleArchive(ht, tmsg);              
                break;
              default:
                throw new RuntimeException("Invalid message type.");
            }
            

          } else {
            // Sleep a tiny while
            LockSupport.parkNanos(1000000L);
          }          
        }        
      } catch (Throwable t) {
        // FIXME(hbs): log something/update Sensision metrics
        LOG.error("Received Throwable while processing Kafka message.", t);
      } finally {
        // Interrupt the synchronizer thread
        this.localabort.set(true);
        try {
          LOG.debug("Consumer exiting, killing synchronizer.");
          while(synchronizer.isAlive()) {
            synchronizer.interrupt();
            LockSupport.parkNanos(100000000L);
          }
        } catch (Exception e) {}
        // Set abort to true in case we exit the 'run' method
        store.abort.set(true);
        //if (null != table) {
        //  try { table.close(); } catch (IOException ioe) { LOG.error("Error closing table ", ioe); }
        //}
      }
    }
    
    private void handleStore(Table ht, KafkaDataMessage msg) throws IOException {
      
      if (KafkaDataMessageType.STORE != msg.getType()) {
        return;
      }
      
      // Skip if there are no data to decode
      if (null == msg.getData() || 0 == msg.getData().length) {
        return;
      }
      //
      // Create a GTSDecoder with the given readings (@see Ingress.java for the packed format)
      //
      
      GTSDecoder decoder = new GTSDecoder(0L, ByteBuffer.wrap(msg.getData()));
      
      Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_STORE_GTSDECODERS,  Sensision.EMPTY_LABELS, 1);
      
      long datapoints = 0L;
      
      // We will store each reading separately, this makes readings storage idempotent
      // If BLOCK_ENCODING is enabled, prefix encoding will be used to shrink column qualifiers
      
      while(decoder.next()) {
        // FIXME(hbs): allow for encrypting readings
        long basets = decoder.getTimestamp();
        GTSEncoder encoder = new GTSEncoder(basets, hbaseAESKey);
        encoder.addValue(basets, decoder.getLocation(), decoder.getElevation(), decoder.getValue());
        
        // Prefix + classId + labelsId + timestamp
        byte[] rowkey = new byte[HBASE_RAW_DATA_KEY_PREFIX.length + 8 + 8 + 8];

        System.arraycopy(HBASE_RAW_DATA_KEY_PREFIX, 0, rowkey, 0, HBASE_RAW_DATA_KEY_PREFIX.length);
        // Copy classId/labelsId
        System.arraycopy(Longs.toByteArray(msg.getClassId()), 0, rowkey, HBASE_RAW_DATA_KEY_PREFIX.length, 8);
        System.arraycopy(Longs.toByteArray(msg.getLabelsId()), 0, rowkey, HBASE_RAW_DATA_KEY_PREFIX.length + 8, 8);
        // Copy timestamp % DEFAULT_MODULUS
        // It could be useful to have per GTS modulus BUT we don't do lookups for metadata, so we can't access a per GTS
        // modulus.... This means we will use the default.
        
        Put put = null;

        byte[] bytes = encoder.getBytes();
        
        if (1 == Constants.DEFAULT_MODULUS) {
          System.arraycopy(Longs.toByteArray(Long.MAX_VALUE - basets), 0, rowkey, HBASE_RAW_DATA_KEY_PREFIX.length + 16, 8);
          put = new Put(rowkey);
          //
          // If the modulus is 1, we don't use a column qualifier
          //
          put.addColumn(store.colfam, null, bytes);
        } else {
          System.arraycopy(Longs.toByteArray(Long.MAX_VALUE - (basets - (basets % Constants.DEFAULT_MODULUS))), 0, rowkey, HBASE_RAW_DATA_KEY_PREFIX.length + 16, 8);
          put = new Put(rowkey);
          //
          // We use the reversed base timestamp as the column qualifier. This introduces some redundancy but it
          // ensures that we have columns in reverse chronological order and can accomodate any modulus. Switching to
          // a 32 bit representation of the offset from basets would restrict the modulus we could use as we might hit
          // an overflow.
          // By using DATA_BLOCK_ENCODING=FASTDIFF, we should mitigate the redundancy in the qualifiers and attain a
          // storage size similar to the one we could have attained by simply storing a delta from basets in the qualifier,
          // but with the added benefit of having a slightly faster decoding process since we don't have to read the row basets
          // AND the qualifier, the qualifier is sufficient.
          //
          put.addColumn(store.colfam, Longs.toByteArray(Long.MAX_VALUE - basets), bytes);
        }
                
        try {
          putslock.lockInterruptibly();
          puts.add(put);
          datapoints++;
          putsSize.addAndGet(bytes.length);          
          lastPut.set(System.currentTimeMillis());
        } catch (InterruptedException ie) {
          localabort.set(true);
          return;
        } finally {
          if (putslock.isHeldByCurrentThread()) {
            putslock.unlock();
          }
        }
//        synchronized (puts) {
//        }                
      }
      
      Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_STORE_HBASE_PUTS, Sensision.EMPTY_LABELS, datapoints);

    }
    
    private void handleDelete(Table ht, KafkaDataMessage msg) throws Throwable {
      
      if (KafkaDataMessageType.DELETE != msg.getType()) {
        return;
      }
      
      if (1 != Constants.DEFAULT_MODULUS) {
        throw new IOException("Delete not implemented for modulus != 1");
      }

      //
      // We need to wait for the current data to be flushed to HBase, otherwise we might have data to delete which
      // is not yet committed (depending on the commit period).
      // We don't need to commit the Kafka offsets as the DELETE would also be replayed if Kafka is read over.
      // We only need to wait if 'puts' is not empty. We first set forcecommit to true then attempt to acquire the
      // 'flushsem' Semaphore every microsecond.
      // 'flushsem' is released by the Synchronizer thread
      //
      
      if (!puts.isEmpty()) {
        forcecommit.set(true);
        while(!flushsem.tryAcquire()) {
          LockSupport.parkNanos(1000);
        }        
      }
      
      //
      // @see https://hbase.apache.org/apidocs/org/apache/hadoop/hbase/coprocessor/example/BulkDeleteEndpoint.html
      //
      
      //
      // The Coprocessor MUST be declared on each RegionServer using the following property in the configuration file:
      //
      // <property>
      //   <name>hbase.coprocessor.region.classes</name>
      //   <value>org.apache.hadoop.hbase.coprocessor.example.BulkDeleteEndpoint</value>
      // </property>
      //
      // This class is in the hbase-example jar file
      //

      //
      // Create the Scan
      //

      final Scan scan = new Scan();
      scan.addFamily(store.colfam);

      // Prefix + classId + labelsId + timestamp
      byte[] rowkey = new byte[HBASE_RAW_DATA_KEY_PREFIX.length + 8 + 8 + 8];

      System.arraycopy(HBASE_RAW_DATA_KEY_PREFIX, 0, rowkey, 0, HBASE_RAW_DATA_KEY_PREFIX.length);
      // Copy classId/labelsId
      System.arraycopy(Longs.toByteArray(msg.getClassId()), 0, rowkey, HBASE_RAW_DATA_KEY_PREFIX.length, 8);
      System.arraycopy(Longs.toByteArray(msg.getLabelsId()), 0, rowkey, HBASE_RAW_DATA_KEY_PREFIX.length + 8, 8);

      long start = msg.getDeletionStartTimestamp();
      long end = msg.getDeletionEndTimestamp();
      
      byte[] startkey = Arrays.copyOf(rowkey, rowkey.length);
      // Endkey is one extra byte long so we include the most ancient ts in the deletion
      byte[] endkey = Arrays.copyOf(rowkey, rowkey.length + 1);

      if (Long.MAX_VALUE == end && Long.MIN_VALUE == start) {
        // Only set the end key.
        Arrays.fill(endkey, HBASE_RAW_DATA_KEY_PREFIX.length + 8 + 8, endkey.length - 1, (byte) 0xff);
      } else {
        // Add reversed timestamps. The end timestamps is the start key
        System.arraycopy(Longs.toByteArray(Long.MAX_VALUE - end), 0, startkey, HBASE_RAW_DATA_KEY_PREFIX.length + 8 + 8, 8);
        System.arraycopy(Longs.toByteArray(Long.MAX_VALUE - start), 0, endkey, HBASE_RAW_DATA_KEY_PREFIX.length + 8 + 8, 8);
      }
      
      scan.setStartRow(startkey);
      scan.setStopRow(endkey);
      scan.setMaxVersions();
      //
      // Set 'raw' to true so we correctly delete the cells still in memstore.
      //
      scan.setRaw(true);
      
      //
      // Do not pollute the block cache
      //
      scan.setCacheBlocks(false);
      
      final long minage = msg.getDeletionMinAge();
      
      //
      // Add a timestamp range if 'minage' is > 0
      //
      
      if (minage > 0) {
        long maxts = System.currentTimeMillis() - minage + 1;
        scan.setTimeRange(0, maxts);
      }
      
      //
      // Call the Coprocessor endpoint on each RegionServer
      //
      
      
      Batch.Call<BulkDeleteService, BulkDeleteResponse> callable = new Batch.Call<BulkDeleteService, BulkDeleteResponse>() {
        ServerRpcController controller = new ServerRpcController();
        BlockingRpcCallback<BulkDeleteResponse> rpcCallback = new BlockingRpcCallback<BulkDeleteResponse>();

        public BulkDeleteResponse call(BulkDeleteService service) throws IOException {
          Builder builder = BulkDeleteRequest.newBuilder();
          builder.setScan(ProtobufUtil.toScan(scan));
          
          builder.setDeleteType(DeleteType.VERSION);
          
          // Arbitrary for now, maybe come up with a better heuristic
          builder.setRowBatchSize(1000);
          service.delete(controller, builder.build(), rpcCallback);
          return rpcCallback.get();
        }
      };

      long nano = System.nanoTime();

      Map<byte[], BulkDeleteResponse> result = table.coprocessorService(BulkDeleteService.class, scan.getStartRow(), scan.getStopRow(), callable);

      nano = System.nanoTime() - nano;
      
      long noOfDeletedRows = 0L;
      long noOfDeletedVersions = 0L;
      long noOfRegions = result.size();

      // One element per region
      for (BulkDeleteResponse response : result.values()) {
        noOfDeletedRows += response.getRowsDeleted();
        noOfDeletedVersions += response.getVersionsDeleted();
      }
      
      //
      // Update Sensision metrics for deletion
      //
      
      Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_STORE_HBASE_DELETE_TIME_NANOS, Sensision.EMPTY_LABELS, nano);
      Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_STORE_HBASE_DELETE_OPS, Sensision.EMPTY_LABELS, 1);
      Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_STORE_HBASE_DELETE_REGIONS, Sensision.EMPTY_LABELS, noOfRegions);
      Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_STORE_HBASE_DELETE_DATAPOINTS, Sensision.EMPTY_LABELS, noOfDeletedVersions);

      Metadata meta = msg.getMetadata();
      if (null != meta) {
        Map<String, String> labels = new HashMap<>();
        labels.put(SensisionConstants.SENSISION_LABEL_OWNER, meta.getLabels().get(Constants.OWNER_LABEL));
        labels.put(SensisionConstants.SENSISION_LABEL_APPLICATION, meta.getLabels().get(Constants.APPLICATION_LABEL));
        Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_STORE_HBASE_DELETE_DATAPOINTS_PEROWNERAPP, labels, noOfDeletedVersions);
      }
    }
    
    private void handleArchive(Table ht, KafkaDataMessage msg) {
      
      if (KafkaDataMessageType.ARCHIVE != msg.getType()) {
        return;
      }
      
      
      throw new RuntimeException("Archive not implemented yet.");
    }
  }
  
  
  /**
   * Extract Store related keys and populate the KeyStore with them.
   * 
   * @param props Properties from which to extract the key specs
   */
  private void extractKeys(Properties props) {
    String keyspec = props.getProperty(io.warp10.continuum.Configuration.STORE_KAFKA_DATA_MAC);
    
    if (null != keyspec) {
      byte[] key = this.keystore.decodeKey(keyspec);
      Preconditions.checkArgument(16 == key.length, "Key " + io.warp10.continuum.Configuration.STORE_KAFKA_DATA_MAC + " MUST be 128 bits long.");
      this.keystore.setKey(KeyStore.SIPHASH_KAFKA_DATA, key);
    }

    keyspec = props.getProperty(io.warp10.continuum.Configuration.STORE_KAFKA_DATA_AES);
    
    if (null != keyspec) {
      byte[] key = this.keystore.decodeKey(keyspec);
      Preconditions.checkArgument(16 == key.length || 24 == key.length || 32 == key.length, "Key " + io.warp10.continuum.Configuration.STORE_KAFKA_DATA_AES + " MUST be 128, 192 or 256 bits long.");
      this.keystore.setKey(KeyStore.AES_KAFKA_DATA, key);
    }
    
    keyspec = props.getProperty(io.warp10.continuum.Configuration.STORE_HBASE_DATA_AES);
    
    if (null != keyspec) {
      byte[] key = this.keystore.decodeKey(keyspec);
      Preconditions.checkArgument(16 == key.length || 24 == key.length || 32 == key.length, "Key " + io.warp10.continuum.Configuration.STORE_HBASE_DATA_AES + " MUST be 128, 192 or 256 bits long.");
      this.keystore.setKey(KeyStore.AES_HBASE_DATA, key);
    }
  }
  
  private static synchronized Connection getHBaseConnection(Properties properties) throws IOException {
    
    //
    // Compute two SipHash of the properties which will be used as a key in the singleton map
    //
    
    Map<String,String> props = new HashMap<String,String>();
    
    for (Entry<Object,Object> prop: properties.entrySet()) {
      props.put(prop.getKey().toString(), prop.getValue().toString());
    }
    
    long msb = GTSHelper.labelsId(0xAC1C573839114320L, 0x8638D30B3E5E3491L, props);
    long lsb = GTSHelper.labelsId(0xC4DFC7F5A82D4626L, 0x9AB1748AF5EC0C16L, props);
    
    String uuid = new UUID(msb,lsb).toString();
    
    Connection conn = connections.get(uuid);
    
    if (null != conn) {
      return conn;
    }
    
    //
    // We need to create a new connection
    //
    
    Configuration config = new Configuration();
    if (properties.containsKey(io.warp10.continuum.Configuration.STORE_HBASE_HCONNECTION_THREADS_MAX)) {
      config.set("hbase.hconnection.threads.max", properties.getProperty(io.warp10.continuum.Configuration.STORE_HBASE_HCONNECTION_THREADS_MAX));
    } else {
      config.set("hbase.hconnection.threads.max", Integer.toString(Runtime.getRuntime().availableProcessors() * 8));
    }
    if (properties.containsKey(io.warp10.continuum.Configuration.STORE_HBASE_HCONNECTION_THREADS_CORE)) {
      config.set("hbase.hconnection.threads.core", properties.getProperty(io.warp10.continuum.Configuration.STORE_HBASE_HCONNECTION_THREADS_MAX));
    } else {
      config.set("hbase.hconnection.threads.core", Integer.toString(Runtime.getRuntime().availableProcessors() * 8));
    }
    config.set("hbase.zookeeper.quorum", properties.getProperty(io.warp10.continuum.Configuration.STORE_HBASE_DATA_ZKCONNECT));
    if (!"".equals(properties.getProperty(io.warp10.continuum.Configuration.STORE_HBASE_DATA_ZNODE))) {
      config.set("zookeeper.znode.parent", properties.getProperty(io.warp10.continuum.Configuration.STORE_HBASE_DATA_ZNODE));
    }
    if (properties.containsKey(io.warp10.continuum.Configuration.STORE_HBASE_ZOOKEEPER_PROPERTY_CLIENTPORT)) {
      config.set("hbase.zookeeper.property.clientPort", properties.getProperty(io.warp10.continuum.Configuration.STORE_HBASE_ZOOKEEPER_PROPERTY_CLIENTPORT));
    }
    if (properties.containsKey(io.warp10.continuum.Configuration.STORE_HBASE_CLIENT_IPC_POOL_SIZE)) {
      config.set("hbase.client.ipc.pool.size", properties.getProperty(io.warp10.continuum.Configuration.STORE_HBASE_CLIENT_IPC_POOL_SIZE));
    }
    
    conn = ConnectionFactory.createConnection(config);

    connections.put(uuid,conn);
    
    return conn;
  }
}
