/*
 * Copyright The Apache Software Foundation
 *
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
package org.apache.hadoop.hbase.master;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.InterProcessLock;
import org.apache.hadoop.hbase.InterProcessLock.MetadataHandler;
import org.apache.hadoop.hbase.InterProcessReadWriteLock;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.exceptions.LockTimeoutException;
import org.apache.hadoop.hbase.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.protobuf.generated.ZooKeeperProtos;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.zookeeper.ZKUtil;
import org.apache.hadoop.hbase.zookeeper.ZooKeeperWatcher;
import org.apache.hadoop.hbase.zookeeper.lock.ZKInterProcessReadWriteLock;
import org.apache.zookeeper.KeeperException;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

/**
 * A manager for distributed table level locks.
 */
@InterfaceAudience.Private
public abstract class TableLockManager {

  private static final Log LOG = LogFactory.getLog(TableLockManager.class);

  /** Configuration key for enabling table-level locks for schema changes */
  public static final String TABLE_LOCK_ENABLE =
    "hbase.table.lock.enable";

  /** by default we should enable table-level locks for schema changes */
  private static final boolean DEFAULT_TABLE_LOCK_ENABLE = true;

  /** Configuration key for time out for trying to acquire table locks */
  protected static final String TABLE_WRITE_LOCK_TIMEOUT_MS =
    "hbase.table.write.lock.timeout.ms";

  /** Configuration key for time out for trying to acquire table locks */
  protected static final String TABLE_READ_LOCK_TIMEOUT_MS =
    "hbase.table.read.lock.timeout.ms";

  protected static final int DEFAULT_TABLE_WRITE_LOCK_TIMEOUT_MS =
    600 * 1000; //10 min default

  protected static final int DEFAULT_TABLE_READ_LOCK_TIMEOUT_MS =
    600 * 1000; //10 min default

  /**
   * A distributed lock for a table.
   */
  @InterfaceAudience.Private
  public static interface TableLock {
    /**
     * Acquire the lock, with the configured lock timeout.
     * @throws LockTimeoutException If unable to acquire a lock within a specified
     * time period (if any)
     * @throws IOException If unrecoverable error occurs
     */
    public void acquire() throws IOException;

    /**
     * Release the lock already held.
     * @throws IOException If there is an unrecoverable error releasing the lock
     */
    public void release() throws IOException;
  }

  /**
   * Returns a TableLock for locking the table for exclusive access
   * @param tableName Table to lock
   * @param purpose Human readable reason for locking the table
   * @return A new TableLock object for acquiring a write lock
   */
  public abstract TableLock writeLock(byte[] tableName, String purpose);

  /**
   * Returns a TableLock for locking the table for shared access among read-lock holders
   * @param tableName Table to lock
   * @param purpose Human readable reason for locking the table
   * @return A new TableLock object for acquiring a read lock
   */
  public abstract TableLock readLock(byte[] tableName, String purpose);

  /**
   * Force releases all table write locks and lock attempts even if this thread does
   * not own the lock. The behavior of the lock holders still thinking that they
   * have the lock is undefined. This should be used carefully and only when
   * we can ensure that all write-lock holders have died. For example if only
   * the master can hold write locks, then we can reap it's locks when the backup
   * master starts.
   */
  public abstract void reapAllTableWriteLocks() throws IOException;

  /**
   * Called after a table has been deleted, and after the table lock is  released.
   * TableLockManager should do cleanup for the table state.
   * @param tableName name of the table
   * @throws IOException If there is an unrecoverable error releasing the lock
   */
  public abstract void tableDeleted(byte[] tableName)
      throws IOException;

  /**
   * Creates and returns a TableLockManager according to the configuration
   */
  public static TableLockManager createTableLockManager(Configuration conf,
      ZooKeeperWatcher zkWatcher, ServerName serverName) {
    // Initialize table level lock manager for schema changes, if enabled.
    if (conf.getBoolean(TABLE_LOCK_ENABLE,
        DEFAULT_TABLE_LOCK_ENABLE)) {
      int writeLockTimeoutMs = conf.getInt(TABLE_WRITE_LOCK_TIMEOUT_MS,
          DEFAULT_TABLE_WRITE_LOCK_TIMEOUT_MS);
      int readLockTimeoutMs = conf.getInt(TABLE_READ_LOCK_TIMEOUT_MS,
          DEFAULT_TABLE_READ_LOCK_TIMEOUT_MS);
      return new ZKTableLockManager(zkWatcher, serverName, writeLockTimeoutMs, readLockTimeoutMs);
    }

    return new NullTableLockManager();
  }

  /**
   * A null implementation
   */
  @InterfaceAudience.Private
  static class NullTableLockManager extends TableLockManager {
    static class NullTableLock implements TableLock {
      @Override
      public void acquire() throws IOException {
      }
      @Override
      public void release() throws IOException {
      }
    }
    @Override
    public TableLock writeLock(byte[] tableName, String purpose) {
      return new NullTableLock();
    }
    @Override
    public TableLock readLock(byte[] tableName, String purpose) {
      return new NullTableLock();
    }
    @Override
    public void reapAllTableWriteLocks() throws IOException {
    }
    @Override
    public void tableDeleted(byte[] tableName) throws IOException {
    }
  }

  /**
   * ZooKeeper based TableLockManager
   */
  @InterfaceAudience.Private
  private static class ZKTableLockManager extends TableLockManager {

    private static final MetadataHandler METADATA_HANDLER = new MetadataHandler() {
      @Override
      public void handleMetadata(byte[] ownerMetadata) {
        if (!LOG.isDebugEnabled()) {
          return;
        }
        ZooKeeperProtos.TableLock data = fromBytes(ownerMetadata);
        if (data == null) {
          return;
        }
        LOG.debug("Table is locked by: " +
            String.format("[tableName=%s, lockOwner=%s, threadId=%s, " +
                "purpose=%s, isShared=%s]", Bytes.toString(data.getTableName().toByteArray()),
                ProtobufUtil.toServerName(data.getLockOwner()), data.getThreadId(),
                data.getPurpose(), data.getIsShared()));
      }
    };

    private static class TableLockImpl implements TableLock {
      long lockTimeoutMs;
      byte[] tableName;
      String tableNameStr;
      InterProcessLock lock;
      boolean isShared;
      ZooKeeperWatcher zkWatcher;
      ServerName serverName;
      String purpose;

      public TableLockImpl(byte[] tableName, ZooKeeperWatcher zkWatcher,
          ServerName serverName, long lockTimeoutMs, boolean isShared, String purpose) {
        this.tableName = tableName;
        tableNameStr = Bytes.toString(tableName);
        this.zkWatcher = zkWatcher;
        this.serverName = serverName;
        this.lockTimeoutMs = lockTimeoutMs;
        this.isShared = isShared;
        this.purpose = purpose;
      }

      @Override
      public void acquire() throws IOException {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Attempt to acquire table " + (isShared ? "read" : "write")
              + " lock on :" + tableNameStr + " for:" + purpose);
        }

        lock = createTableLock();
        try {
          if (lockTimeoutMs == -1) {
            // Wait indefinitely
            lock.acquire();
          } else {
            if (!lock.tryAcquire(lockTimeoutMs)) {
              throw new LockTimeoutException("Timed out acquiring " +
                (isShared ? "read" : "write") + "lock for table:" + tableNameStr +
                "for:" + purpose + " after " + lockTimeoutMs + " ms.");
            }
          }
        } catch (InterruptedException e) {
          LOG.warn("Interrupted acquiring a lock for " + tableNameStr, e);
          Thread.currentThread().interrupt();
          throw new InterruptedIOException("Interrupted acquiring a lock");
        }
        LOG.debug("Acquired table " + (isShared ? "read" : "write")
            + " lock on :" + tableNameStr + " for:" + purpose);
      }

      @Override
      public void release() throws IOException {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Attempt to release table " + (isShared ? "read" : "write")
              + " lock on :" + tableNameStr);
        }
        if (lock == null) {
          throw new IllegalStateException("Table " + tableNameStr +
            " is not locked!");
        }

        try {
          lock.release();
        } catch (InterruptedException e) {
          LOG.warn("Interrupted while releasing a lock for " + tableNameStr);
          Thread.currentThread().interrupt();
          throw new InterruptedIOException();
        }
        if (LOG.isDebugEnabled()) {
          LOG.debug("Released table lock on :" + tableNameStr);
        }
      }

      private InterProcessLock createTableLock() {
        String tableLockZNode = ZKUtil.joinZNode(zkWatcher.tableLockZNode, tableNameStr);

        ZooKeeperProtos.TableLock data = ZooKeeperProtos.TableLock.newBuilder()
          .setTableName(ByteString.copyFrom(tableName))
          .setLockOwner(ProtobufUtil.toServerName(serverName))
          .setThreadId(Thread.currentThread().getId())
          .setPurpose(purpose)
          .setIsShared(isShared).build();
        byte[] lockMetadata = toBytes(data);

        InterProcessReadWriteLock lock = new ZKInterProcessReadWriteLock(zkWatcher, tableLockZNode,
          METADATA_HANDLER);
        return isShared ? lock.readLock(lockMetadata) : lock.writeLock(lockMetadata);
      }
    }

    private static byte[] toBytes(ZooKeeperProtos.TableLock data) {
      return ProtobufUtil.prependPBMagic(data.toByteArray());
    }

    private static ZooKeeperProtos.TableLock fromBytes(byte[] bytes) {
      int pblen = ProtobufUtil.lengthOfPBMagic();
      if (bytes == null || bytes.length < pblen) {
        return null;
      }
      try {
        ZooKeeperProtos.TableLock data = ZooKeeperProtos.TableLock.newBuilder().mergeFrom(
            bytes, pblen, bytes.length - pblen).build();
        return data;
      } catch (InvalidProtocolBufferException ex) {
        LOG.warn("Exception in deserialization", ex);
      }
      return null;
    }

    private final ServerName serverName;
    private final ZooKeeperWatcher zkWatcher;
    private final long writeLockTimeoutMs;
    private final long readLockTimeoutMs;

    /**
     * Initialize a new manager for table-level locks.
     * @param zkWatcher
     * @param serverName Address of the server responsible for acquiring and
     * releasing the table-level locks
     * @param writeLockTimeoutMs Timeout (in milliseconds) for acquiring a write lock for a
     * given table, or -1 for no timeout
     * @param readLockTimeoutMs Timeout (in milliseconds) for acquiring a read lock for a
     * given table, or -1 for no timeout
     */
    public ZKTableLockManager(ZooKeeperWatcher zkWatcher,
      ServerName serverName, long writeLockTimeoutMs, long readLockTimeoutMs) {
      this.zkWatcher = zkWatcher;
      this.serverName = serverName;
      this.writeLockTimeoutMs = writeLockTimeoutMs;
      this.readLockTimeoutMs = readLockTimeoutMs;
    }

    @Override
    public TableLock writeLock(byte[] tableName, String purpose) {
      return new TableLockImpl(tableName, zkWatcher,
          serverName, writeLockTimeoutMs, false, purpose);
    }

    public TableLock readLock(byte[] tableName, String purpose) {
      return new TableLockImpl(tableName, zkWatcher,
          serverName, readLockTimeoutMs, true, purpose);
    }

    @Override
    public void reapAllTableWriteLocks() throws IOException {
      //get the table names
      try {
        List<String> tableNames;
        try {
          tableNames = ZKUtil.listChildrenNoWatch(zkWatcher, zkWatcher.tableLockZNode);
        } catch (KeeperException e) {
          LOG.error("Unexpected ZooKeeper error when listing children", e);
          throw new IOException("Unexpected ZooKeeper exception", e);
        }

        for (String tableName : tableNames) {
          String tableLockZNode = ZKUtil.joinZNode(zkWatcher.tableLockZNode, tableName);
          ZKInterProcessReadWriteLock lock = new ZKInterProcessReadWriteLock(
              zkWatcher, tableLockZNode, null);
          lock.writeLock(null).reapAllLocks();
        }
      } catch (IOException ex) {
        throw ex;
      } catch (Exception ex) {
        LOG.warn("Caught exception while reaping table write locks", ex);
      }
    }

    @Override
    public void tableDeleted(byte[] tableName) throws IOException {
      //table write lock from DeleteHandler is already released, just delete the parent znode
      String tableNameStr = Bytes.toString(tableName);
      String tableLockZNode = ZKUtil.joinZNode(zkWatcher.tableLockZNode, tableNameStr);
      try {
        ZKUtil.deleteNode(zkWatcher, tableLockZNode);
      } catch (KeeperException ex) {
        if (ex.code() == KeeperException.Code.NOTEMPTY) {
          //we might get this in rare occasions where a CREATE table or some other table operation
          //is waiting to acquire the lock. In this case, parent znode won't be deleted.
          LOG.warn("Could not delete the znode for table locks because NOTEMPTY: "
              + tableLockZNode);
          return;
        }
        throw new IOException(ex);
      }
    }
  }
}
