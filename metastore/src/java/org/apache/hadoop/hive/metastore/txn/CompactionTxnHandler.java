/**
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
package org.apache.hadoop.hive.metastore.txn;

import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.api.CompactionType;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Extends the transaction handler with methods needed only by the compactor threads.  These
 * methods are not available through the thrift interface.
 */
class CompactionTxnHandler extends TxnHandler {
  static final private String CLASS_NAME = CompactionTxnHandler.class.getName();
  static final private Logger LOG = LoggerFactory.getLogger(CLASS_NAME);

  public CompactionTxnHandler() {
  }

  /**
   * This will look through the completed_txn_components table and look for partitions or tables
   * that may be ready for compaction.  Also, look through txns and txn_components tables for
   * aborted transactions that we should add to the list.
   * @param maxAborted Maximum number of aborted queries to allow before marking this as a
   *                   potential compaction.
   * @return list of CompactionInfo structs.  These will not have id, type,
   * or runAs set since these are only potential compactions not actual ones.
   */
  public Set<CompactionInfo> findPotentialCompactions(int maxAborted) throws MetaException {
    Connection dbConn = null;
    Set<CompactionInfo> response = new HashSet<CompactionInfo>();
    Statement stmt = null;
    ResultSet rs = null;
    try {
      try {
        dbConn = getDbConn(Connection.TRANSACTION_READ_COMMITTED);
        stmt = dbConn.createStatement();
        // Check for completed transactions
        String s = "select distinct ctc_database, ctc_table, " +
          "ctc_partition from COMPLETED_TXN_COMPONENTS";
        LOG.debug("Going to execute query <" + s + ">");
        rs = stmt.executeQuery(s);
        while (rs.next()) {
          CompactionInfo info = new CompactionInfo();
          info.dbname = rs.getString(1);
          info.tableName = rs.getString(2);
          info.partName = rs.getString(3);
          response.add(info);
        }
        rs.close();

        // Check for aborted txns
        s = "select tc_database, tc_table, tc_partition " +
          "from TXNS, TXN_COMPONENTS " +
          "where txn_id = tc_txnid and txn_state = '" + TXN_ABORTED + "' " +
          "group by tc_database, tc_table, tc_partition " +
          "having count(*) > " + maxAborted;

        LOG.debug("Going to execute query <" + s + ">");
        rs = stmt.executeQuery(s);
        while (rs.next()) {
          CompactionInfo info = new CompactionInfo();
          info.dbname = rs.getString(1);
          info.tableName = rs.getString(2);
          info.partName = rs.getString(3);
          info.tooManyAborts = true;
          response.add(info);
        }

        LOG.debug("Going to rollback");
        dbConn.rollback();
      } catch (SQLException e) {
        LOG.error("Unable to connect to transaction database " + e.getMessage());
        checkRetryable(dbConn, e, "findPotentialCompactions(maxAborted:" + maxAborted + ")");
      } finally {
        close(rs, stmt, dbConn);
      }
      return response;
    }
    catch (RetryException e) {
      return findPotentialCompactions(maxAborted);
    }
  }

  /**
   * Sets the user to run as.  This is for the case
   * where the request was generated by the user and so the worker must set this value later.
   * @param cq_id id of this entry in the queue
   * @param user user to run the jobs as
   */
  public void setRunAs(long cq_id, String user) throws MetaException {
    try {
      Connection dbConn = null;
      Statement stmt = null;
      try {
        dbConn = getDbConn(Connection.TRANSACTION_READ_COMMITTED);
        stmt = dbConn.createStatement();
        String s = "update COMPACTION_QUEUE set cq_run_as = '" + user + "' where cq_id = " + cq_id;
        LOG.debug("Going to execute update <" + s + ">");
        int updCnt = stmt.executeUpdate(s);
        if (updCnt != 1) {
          LOG.error("Unable to set cq_run_as=" + user + " for compaction record with cq_id=" + cq_id + ".  updCnt=" + updCnt);
          LOG.debug("Going to rollback");
          dbConn.rollback();
        }
        LOG.debug("Going to commit");
        dbConn.commit();
      } catch (SQLException e) {
        LOG.error("Unable to update compaction queue, " + e.getMessage());
        LOG.debug("Going to rollback");
        rollbackDBConn(dbConn);
        checkRetryable(dbConn, e, "setRunAs(cq_id:" + cq_id + ",user:" + user +")");
      } finally {
        closeDbConn(dbConn);
        closeStmt(stmt);
      }
    } catch (RetryException e) {
      setRunAs(cq_id, user);
    }
  }

  /**
   * This will grab the next compaction request off of
   * the queue, and assign it to the worker.
   * @param workerId id of the worker calling this, will be recorded in the db
   * @return an info element for this compaction request, or null if there is no work to do now.
   */
  public CompactionInfo findNextToCompact(String workerId) throws MetaException {
    try {
      Connection dbConn = null;
      Statement stmt = null;
      //need a separate stmt for executeUpdate() otherwise it will close the ResultSet(HIVE-12725)
      Statement updStmt = null;
      ResultSet rs = null;
      try {
        dbConn = getDbConn(Connection.TRANSACTION_READ_COMMITTED);
        stmt = dbConn.createStatement();
        String s = "select cq_id, cq_database, cq_table, cq_partition, " +
          "cq_type, cq_tblproperties from COMPACTION_QUEUE where cq_state = '" + INITIATED_STATE + "'";
        LOG.debug("Going to execute query <" + s + ">");
        rs = stmt.executeQuery(s);
        if (!rs.next()) {
          LOG.debug("No compactions found ready to compact");
          dbConn.rollback();
          return null;
        }
        updStmt = dbConn.createStatement();
        do {
          CompactionInfo info = new CompactionInfo();
          info.id = rs.getLong(1);
          info.dbname = rs.getString(2);
          info.tableName = rs.getString(3);
          info.partName = rs.getString(4);
          info.type = dbCompactionType2ThriftType(rs.getString(5).charAt(0));
          info.properties = rs.getString(6);
          // Now, update this record as being worked on by this worker.
          long now = getDbTime(dbConn);
          s = "update COMPACTION_QUEUE set cq_worker_id = '" + workerId + "', " +
            "cq_start = " + now + ", cq_state = '" + WORKING_STATE + "' where cq_id = " + info.id +
            " AND cq_state='" + INITIATED_STATE + "'";
          LOG.debug("Going to execute update <" + s + ">");
          int updCount = updStmt.executeUpdate(s);
          if(updCount == 1) {
            dbConn.commit();
            return info;
          }
          if(updCount == 0) {
            LOG.debug("Another Worker picked up " + info);
            continue;
          }
          LOG.error("Unable to set to cq_state=" + WORKING_STATE + " for compaction record: " +
            info + ". updCnt=" + updCount + ".");
          dbConn.rollback();
          return null;
        } while( rs.next());
        dbConn.rollback();
        return null;
      } catch (SQLException e) {
        LOG.error("Unable to select next element for compaction, " + e.getMessage());
        LOG.debug("Going to rollback");
        rollbackDBConn(dbConn);
        checkRetryable(dbConn, e, "findNextToCompact(workerId:" + workerId + ")");
        throw new MetaException("Unable to connect to transaction database " +
          StringUtils.stringifyException(e));
      } finally {
        closeStmt(updStmt);
        close(rs, stmt, dbConn);
      }
    } catch (RetryException e) {
      return findNextToCompact(workerId);
    }
  }

  /**
   * This will mark an entry in the queue as compacted
   * and put it in the ready to clean state.
   * @param info info on the compaction entry to mark as compacted.
   */
  public void markCompacted(CompactionInfo info) throws MetaException {
    try {
      Connection dbConn = null;
      Statement stmt = null;
      try {
        dbConn = getDbConn(Connection.TRANSACTION_READ_COMMITTED);
        stmt = dbConn.createStatement();
        String s = "update COMPACTION_QUEUE set cq_state = '" + READY_FOR_CLEANING + "', " +
          "cq_worker_id = null where cq_id = " + info.id;
        LOG.debug("Going to execute update <" + s + ">");
        int updCnt = stmt.executeUpdate(s);
        if (updCnt != 1) {
          LOG.error("Unable to set cq_state=" + READY_FOR_CLEANING + " for compaction record: " + info + ". updCnt=" + updCnt);
          LOG.debug("Going to rollback");
          dbConn.rollback();
        }
        LOG.debug("Going to commit");
        dbConn.commit();
      } catch (SQLException e) {
        LOG.error("Unable to update compaction queue " + e.getMessage());
        LOG.debug("Going to rollback");
        rollbackDBConn(dbConn);
        checkRetryable(dbConn, e, "markCompacted(" + info + ")");
        throw new MetaException("Unable to connect to transaction database " +
          StringUtils.stringifyException(e));
      } finally {
        closeStmt(stmt);
        closeDbConn(dbConn);
      }
    } catch (RetryException e) {
      markCompacted(info);
    }
  }

  /**
   * Find entries in the queue that are ready to
   * be cleaned.
   * @return information on the entry in the queue.
   */
  public List<CompactionInfo> findReadyToClean() throws MetaException {
    Connection dbConn = null;
    List<CompactionInfo> rc = new ArrayList<CompactionInfo>();

    Statement stmt = null;
    ResultSet rs = null;
    try {
      try {
        dbConn = getDbConn(Connection.TRANSACTION_READ_COMMITTED);
        stmt = dbConn.createStatement();
        String s = "select cq_id, cq_database, cq_table, cq_partition, " +
          "cq_type, cq_run_as, cq_highest_txn_id from COMPACTION_QUEUE where cq_state = '" + READY_FOR_CLEANING + "'";
        LOG.debug("Going to execute query <" + s + ">");
        rs = stmt.executeQuery(s);
        while (rs.next()) {
          CompactionInfo info = new CompactionInfo();
          info.id = rs.getLong(1);
          info.dbname = rs.getString(2);
          info.tableName = rs.getString(3);
          info.partName = rs.getString(4);
          switch (rs.getString(5).charAt(0)) {
            case MAJOR_TYPE: info.type = CompactionType.MAJOR; break;
            case MINOR_TYPE: info.type = CompactionType.MINOR; break;
            default: throw new MetaException("Unexpected compaction type " + rs.getString(5));
          }
          info.runAs = rs.getString(6);
          info.highestTxnId = rs.getLong(7);
          rc.add(info);
        }
        LOG.debug("Going to rollback");
        dbConn.rollback();
        return rc;
      } catch (SQLException e) {
        LOG.error("Unable to select next element for cleaning, " + e.getMessage());
        LOG.debug("Going to rollback");
        rollbackDBConn(dbConn);
        checkRetryable(dbConn, e, "findReadyToClean");
        throw new MetaException("Unable to connect to transaction database " +
          StringUtils.stringifyException(e));
      } finally {
        close(rs, stmt, dbConn);
      }
    } catch (RetryException e) {
      return findReadyToClean();
    }
  }

  /**
   * This will remove an entry from the queue after
   * it has been compacted.
   * 
   * @param info info on the compaction entry to remove
   */
  public void markCleaned(CompactionInfo info) throws MetaException {
    try {
      Connection dbConn = null;
      Statement stmt = null;
      PreparedStatement pStmt = null;
      ResultSet rs = null;
      try {
        dbConn = getDbConn(Connection.TRANSACTION_READ_COMMITTED);
        stmt = dbConn.createStatement();
        rs = stmt.executeQuery("select CQ_ID, CQ_DATABASE, CQ_TABLE, CQ_PARTITION, CQ_STATE, CQ_TYPE, CQ_TBLPROPERTIES, CQ_WORKER_ID, CQ_START, CQ_RUN_AS, CQ_HIGHEST_TXN_ID, CQ_META_INFO, CQ_HADOOP_JOB_ID from COMPACTION_QUEUE WHERE CQ_ID = " + info.id);
        if(rs.next()) {
          info = CompactionInfo.loadFullFromCompactionQueue(rs);
        }
        else {
          throw new IllegalStateException("No record with CQ_ID=" + info.id + " found in COMPACTION_QUEUE");
        }
        close(rs);
        String s = "delete from COMPACTION_QUEUE where cq_id = " + info.id;
        LOG.debug("Going to execute update <" + s + ">");
        int updCount = stmt.executeUpdate(s);
        if (updCount != 1) {
          LOG.error("Unable to delete compaction record: " + info +  ".  Update count=" + updCount);
          LOG.debug("Going to rollback");
          dbConn.rollback();
        }
        pStmt = dbConn.prepareStatement("insert into COMPLETED_COMPACTIONS(CC_ID, CC_DATABASE, CC_TABLE, CC_PARTITION, CC_STATE, CC_TYPE, CC_TBLPROPERTIES, CC_WORKER_ID, CC_START, CC_END, CC_RUN_AS, CC_HIGHEST_TXN_ID, CC_META_INFO, CC_HADOOP_JOB_ID) VALUES(?,?,?,?,?, ?,?,?,?,?, ?,?,?,?)");
        info.state = SUCCEEDED_STATE;
        CompactionInfo.insertIntoCompletedCompactions(pStmt, info, getDbTime(dbConn));
        updCount = pStmt.executeUpdate();

        // Remove entries from completed_txn_components as well, so we don't start looking there
        // again but only up to the highest txn ID include in this compaction job.
        //highestTxnId will be NULL in upgrade scenarios
        s = "delete from COMPLETED_TXN_COMPONENTS where ctc_database = '" + info.dbname + "' and " +
          "ctc_table = '" + info.tableName + "'";
        if (info.partName != null) {
          s += " and ctc_partition = '" + info.partName + "'";
        }
        if(info.highestTxnId != 0) {
          s += " and ctc_txnid <= " + info.highestTxnId;
        }
        LOG.debug("Going to execute update <" + s + ">");
        if (stmt.executeUpdate(s) < 1) {
          LOG.error("Expected to remove at least one row from completed_txn_components when " +
            "marking compaction entry as clean!");
        }

        s = "select distinct txn_id from TXNS, TXN_COMPONENTS where txn_id = tc_txnid and txn_state = '" +
          TXN_ABORTED + "' and tc_database = '" + info.dbname + "' and tc_table = '" +
          info.tableName + "'" + (info.highestTxnId == 0 ? "" : " and txn_id <= " + info.highestTxnId);
        if (info.partName != null) s += " and tc_partition = '" + info.partName + "'";
        LOG.debug("Going to execute update <" + s + ">");
        rs = stmt.executeQuery(s);
        List<Long> txnids = new ArrayList<>();
        while (rs.next()) txnids.add(rs.getLong(1));
        // Remove entries from txn_components, as there may be aborted txn components
        if (txnids.size() > 0) {
          List<String> queries = new ArrayList<String>();

          // Prepare prefix and suffix
          StringBuilder prefix = new StringBuilder();
          StringBuilder suffix = new StringBuilder();

          prefix.append("delete from TXN_COMPONENTS where ");

          //because 1 txn may include different partitions/tables even in auto commit mode
          suffix.append(" and tc_database = ");
          suffix.append(quoteString(info.dbname));
          suffix.append(" and tc_table = ");
          suffix.append(quoteString(info.tableName));
          if (info.partName != null) {
            suffix.append(" and tc_partition = ");
            suffix.append(quoteString(info.partName));
          }

          // Populate the complete query with provided prefix and suffix
          TxnUtils.buildQueryWithINClause(conf, queries, prefix, suffix, txnids, "tc_txnid", true, false);

          for (String query : queries) {
            LOG.debug("Going to execute update <" + query + ">");
            int rc = stmt.executeUpdate(query);
            LOG.debug("Removed " + rc + " records from txn_components");

            // Don't bother cleaning from the txns table.  A separate call will do that.  We don't
            // know here which txns still have components from other tables or partitions in the
            // table, so we don't know which ones we can and cannot clean.
          }
        }

        LOG.debug("Going to commit");
        dbConn.commit();
      } catch (SQLException e) {
        LOG.error("Unable to delete from compaction queue " + e.getMessage());
        LOG.debug("Going to rollback");
        rollbackDBConn(dbConn);
        checkRetryable(dbConn, e, "markCleaned(" + info + ")");
        throw new MetaException("Unable to connect to transaction database " +
          StringUtils.stringifyException(e));
      } finally {
        closeStmt(pStmt);
        close(rs, stmt, dbConn);
      }
    } catch (RetryException e) {
      markCleaned(info);
    }
  }

  /**
   * Clean up aborted transactions from txns that have no components in txn_components.  The reason such
   * txns exist can be that now work was done in this txn (e.g. Streaming opened TransactionBatch and
   * abandoned it w/o doing any work) or due to {@link #markCleaned(CompactionInfo)} being called.
   */
  public void cleanEmptyAbortedTxns() throws MetaException {
    try {
      Connection dbConn = null;
      Statement stmt = null;
      ResultSet rs = null;
      try {
        //Aborted is a terminal state, so nothing about the txn can change
        //after that, so READ COMMITTED is sufficient.
        dbConn = getDbConn(Connection.TRANSACTION_READ_COMMITTED);
        stmt = dbConn.createStatement();
        String s = "select txn_id from TXNS where " +
          "txn_id not in (select tc_txnid from TXN_COMPONENTS) and " +
          "txn_state = '" + TXN_ABORTED + "'";
        LOG.debug("Going to execute query <" + s + ">");
        rs = stmt.executeQuery(s);
        List<Long> txnids = new ArrayList<>();
        while (rs.next()) txnids.add(rs.getLong(1));
        close(rs);
        if(txnids.size() <= 0) {
          return;
        }
        Collections.sort(txnids);//easier to read logs
        List<String> queries = new ArrayList<String>();
        StringBuilder prefix = new StringBuilder();
        StringBuilder suffix = new StringBuilder();

        prefix.append("delete from TXNS where ");
        suffix.append("");

        TxnUtils.buildQueryWithINClause(conf, queries, prefix, suffix, txnids, "txn_id", false, false);

        for (String query : queries) {
          LOG.debug("Going to execute update <" + query + ">");
          int rc = stmt.executeUpdate(query);
          LOG.info("Removed " + rc + "  empty Aborted transactions from TXNS");
        }
        LOG.info("Aborted transactions removed from TXNS: " + txnids);

        LOG.debug("Going to commit");
        dbConn.commit();
      } catch (SQLException e) {
        LOG.error("Unable to delete from txns table " + e.getMessage());
        LOG.debug("Going to rollback");
        rollbackDBConn(dbConn);
        checkRetryable(dbConn, e, "cleanEmptyAbortedTxns");
        throw new MetaException("Unable to connect to transaction database " +
          StringUtils.stringifyException(e));
      } finally {
        close(rs, stmt, dbConn);
      }
    } catch (RetryException e) {
      cleanEmptyAbortedTxns();
    }
  }

  /**
   * This will take all entries assigned to workers
   * on a host return them to INITIATED state.  The initiator should use this at start up to
   * clean entries from any workers that were in the middle of compacting when the metastore
   * shutdown.  It does not reset entries from worker threads on other hosts as those may still
   * be working.
   * @param hostname Name of this host.  It is assumed this prefixes the thread's worker id,
   *                 so that like hostname% will match the worker id.
   */
  public void revokeFromLocalWorkers(String hostname) throws MetaException {
    try {
      Connection dbConn = null;
      Statement stmt = null;
      try {
        dbConn = getDbConn(Connection.TRANSACTION_READ_COMMITTED);
        stmt = dbConn.createStatement();
        String s = "update COMPACTION_QUEUE set cq_worker_id = null, cq_start = null, cq_state = '"
          + INITIATED_STATE+ "' where cq_state = '" + WORKING_STATE + "' and cq_worker_id like '"
          +  hostname + "%'";
        LOG.debug("Going to execute update <" + s + ">");
        // It isn't an error if the following returns no rows, as the local workers could have died
        // with  nothing assigned to them.
        stmt.executeUpdate(s);
        LOG.debug("Going to commit");
        dbConn.commit();
      } catch (SQLException e) {
        LOG.error("Unable to change dead worker's records back to initiated state " +
          e.getMessage());
        LOG.debug("Going to rollback");
        rollbackDBConn(dbConn);
        checkRetryable(dbConn, e, "revokeFromLocalWorkers(hostname:" + hostname +")");
        throw new MetaException("Unable to connect to transaction database " +
          StringUtils.stringifyException(e));
      } finally {
        closeStmt(stmt);
        closeDbConn(dbConn);
      }
    } catch (RetryException e) {
      revokeFromLocalWorkers(hostname);
    }
  }

  /**
   * This call will return all compaction queue
   * entries assigned to a worker but over the timeout back to the initiated state.
   * This should be called by the initiator on start up and occasionally when running to clean up
   * after dead threads.  At start up {@link #revokeFromLocalWorkers(String)} should be called
   * first.
   * @param timeout number of milliseconds since start time that should elapse before a worker is
   *                declared dead.
   */
  public void revokeTimedoutWorkers(long timeout) throws MetaException {
    try {
      Connection dbConn = null;
      Statement stmt = null;
      try {
        dbConn = getDbConn(Connection.TRANSACTION_READ_COMMITTED);
        long latestValidStart = getDbTime(dbConn) - timeout;
        stmt = dbConn.createStatement();
        String s = "update COMPACTION_QUEUE set cq_worker_id = null, cq_start = null, cq_state = '"
          + INITIATED_STATE+ "' where cq_state = '" + WORKING_STATE + "' and cq_start < "
          +  latestValidStart;
        LOG.debug("Going to execute update <" + s + ">");
        // It isn't an error if the following returns no rows, as the local workers could have died
        // with  nothing assigned to them.
        stmt.executeUpdate(s);
        LOG.debug("Going to commit");
        dbConn.commit();
      } catch (SQLException e) {
        LOG.error("Unable to change dead worker's records back to initiated state " +
          e.getMessage());
        LOG.debug("Going to rollback");
        rollbackDBConn(dbConn);
        checkRetryable(dbConn, e, "revokeTimedoutWorkers(timeout:" + timeout + ")");
        throw new MetaException("Unable to connect to transaction database " +
          StringUtils.stringifyException(e));
      } finally {
        closeStmt(stmt);
        closeDbConn(dbConn);
      }
    } catch (RetryException e) {
      revokeTimedoutWorkers(timeout);
    }
  }

  /**
   * Queries metastore DB directly to find columns in the table which have statistics information.
   * If {@code ci} includes partition info then per partition stats info is examined, otherwise
   * table level stats are examined.
   * @throws MetaException
   */
  public List<String> findColumnsWithStats(CompactionInfo ci) throws MetaException {
    Connection dbConn = null;
    Statement stmt = null;
    ResultSet rs = null;
    try {
      try {
        dbConn = getDbConn(Connection.TRANSACTION_READ_COMMITTED);
        String quote = getIdentifierQuoteString(dbConn);
        stmt = dbConn.createStatement();
        StringBuilder bldr = new StringBuilder();
        bldr.append("SELECT ").append(quote).append("COLUMN_NAME").append(quote)
          .append(" FROM ")
          .append(quote).append((ci.partName == null ? "TAB_COL_STATS" : "PART_COL_STATS"))
          .append(quote)
          .append(" WHERE ")
          .append(quote).append("DB_NAME").append(quote).append(" = '").append(ci.dbname)
          .append("' AND ").append(quote).append("TABLE_NAME").append(quote)
          .append(" = '").append(ci.tableName).append("'");
        if (ci.partName != null) {
          bldr.append(" AND ").append(quote).append("PARTITION_NAME").append(quote).append(" = '")
            .append(ci.partName).append("'");
        }
        String s = bldr.toString();

      /*String s = "SELECT COLUMN_NAME FROM " + (ci.partName == null ? "TAB_COL_STATS" :
          "PART_COL_STATS")
         + " WHERE DB_NAME='" + ci.dbname + "' AND TABLE_NAME='" + ci.tableName + "'"
        + (ci.partName == null ? "" : " AND PARTITION_NAME='" + ci.partName + "'");*/
        LOG.debug("Going to execute <" + s + ">");
        rs = stmt.executeQuery(s);
        List<String> columns = new ArrayList<String>();
        while (rs.next()) {
          columns.add(rs.getString(1));
        }
        LOG.debug("Found columns to update stats: " + columns + " on " + ci.tableName +
          (ci.partName == null ? "" : "/" + ci.partName));
        dbConn.commit();
        return columns;
      } catch (SQLException e) {
        rollbackDBConn(dbConn);
        checkRetryable(dbConn, e, "findColumnsWithStats(" + ci.tableName +
          (ci.partName == null ? "" : "/" + ci.partName) + ")");
        throw new MetaException("Unable to connect to transaction database " +
          StringUtils.stringifyException(e));
      } finally {
        close(rs, stmt, dbConn);
      }
    } catch (RetryException ex) {
      return findColumnsWithStats(ci);
    }
  }

  /**
   * Record the highest txn id that the {@code ci} compaction job will pay attention to.
   * This is the highest resolved txn id, i.e. such that there are no open txns with lower ids.
   */
  public void setCompactionHighestTxnId(CompactionInfo ci, long highestTxnId) throws MetaException {
    Connection dbConn = null;
    Statement stmt = null;
    try {
      try {
        dbConn = getDbConn(Connection.TRANSACTION_READ_COMMITTED);
        stmt = dbConn.createStatement();
        int updCount = stmt.executeUpdate("UPDATE COMPACTION_QUEUE SET CQ_HIGHEST_TXN_ID = " + highestTxnId +
          " WHERE CQ_ID = " + ci.id);
        if(updCount != 1) {
          throw new IllegalStateException("Could not find record in COMPACTION_QUEUE for " + ci);
        }
        dbConn.commit();
      } catch (SQLException e) {
        rollbackDBConn(dbConn);
        checkRetryable(dbConn, e, "setCompactionHighestTxnId(" + ci + "," + highestTxnId + ")");
        throw new MetaException("Unable to connect to transaction database " +
          StringUtils.stringifyException(e));
      } finally {
        close(null, stmt, dbConn);
      }
    } catch (RetryException ex) {
      setCompactionHighestTxnId(ci, highestTxnId);
    }
  }
  private static class RetentionCounters {
    int attemptedRetention = 0;
    int failedRetention = 0;
    int succeededRetention = 0;
    RetentionCounters(int attemptedRetention, int failedRetention, int succeededRetention) {
      this.attemptedRetention = attemptedRetention;
      this.failedRetention = failedRetention;
      this.succeededRetention = succeededRetention;
    }
  }
  private void checkForDeletion(List<Long> deleteSet, CompactionInfo ci, RetentionCounters rc) {
    switch (ci.state) {
      case ATTEMPTED_STATE:
        if(--rc.attemptedRetention < 0) {
          deleteSet.add(ci.id);
        }
        break;
      case FAILED_STATE:
        if(--rc.failedRetention < 0) {
          deleteSet.add(ci.id);
        }
        break;
      case SUCCEEDED_STATE:
        if(--rc.succeededRetention < 0) {
          deleteSet.add(ci.id);
        }
        break;
      default:
        //do nothing to hanlde future RU/D where we may want to add new state types
    }
  }

  /**
   * For any given compactable entity (partition; table if not partitioned) the history of compactions
   * may look like "sssfffaaasffss", for example.  The idea is to retain the tail (most recent) of the
   * history such that a configurable number of each type of state is present.  Any other entries
   * can be purged.  This scheme has advantage of always retaining the last failure/success even if
   * it's not recent.
   * @throws MetaException
   */
  public void purgeCompactionHistory() throws MetaException {
    Connection dbConn = null;
    Statement stmt = null;
    ResultSet rs = null;
    List<Long> deleteSet = new ArrayList<>();
    RetentionCounters rc = null;
    try {
      try {
        dbConn = getDbConn(Connection.TRANSACTION_READ_COMMITTED);
        stmt = dbConn.createStatement();
        /*cc_id is monotonically increasing so for any entity sorts in order of compaction history,
        thus this query groups by entity and withing group sorts most recent first*/
        rs = stmt.executeQuery("select cc_id, cc_database, cc_table, cc_partition, cc_state from " +
          "COMPLETED_COMPACTIONS order by cc_database, cc_table, cc_partition, cc_id desc");
        String lastCompactedEntity = null;
        /*In each group, walk from most recent and count occurences of each state type.  Once you
        * have counted enough (for each state) to satisfy retention policy, delete all other
        * instances of this status.*/
        while(rs.next()) {
          CompactionInfo ci = new CompactionInfo(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5).charAt(0));
          if(!ci.getFullPartitionName().equals(lastCompactedEntity)) {
            lastCompactedEntity = ci.getFullPartitionName();
            rc = new RetentionCounters(conf.getIntVar(HiveConf.ConfVars.COMPACTOR_HISTORY_RETENTION_ATTEMPTED),
              getFailedCompactionRetention(),
              conf.getIntVar(HiveConf.ConfVars.COMPACTOR_HISTORY_RETENTION_SUCCEEDED));
          }
          checkForDeletion(deleteSet, ci, rc);
        }
        close(rs);

        if (deleteSet.size() <= 0) {
          return;
        }

        List<String> queries = new ArrayList<String>();

        StringBuilder prefix = new StringBuilder();
        StringBuilder suffix = new StringBuilder();

        prefix.append("delete from COMPLETED_COMPACTIONS where ");
        suffix.append("");

        TxnUtils.buildQueryWithINClause(conf, queries, prefix, suffix, deleteSet, "cc_id", false, false);

        for (String query : queries) {
          LOG.debug("Going to execute update <" + query + ">");
          int count = stmt.executeUpdate(query);
          LOG.debug("Removed " + count + " records from COMPLETED_COMPACTIONS");
        }
        dbConn.commit();
      } catch (SQLException e) {
        rollbackDBConn(dbConn);
        checkRetryable(dbConn, e, "purgeCompactionHistory()");
        throw new MetaException("Unable to connect to transaction database " +
          StringUtils.stringifyException(e));
      } finally {
        close(rs, stmt, dbConn);
      }
    } catch (RetryException ex) {
      purgeCompactionHistory();
    }
  }
  /**
   * this ensures that the number of failed compaction entries retained is > than number of failed
   * compaction threshold which prevents new compactions from being scheduled.
   */
  public int getFailedCompactionRetention() {
    int failedThreshold = conf.getIntVar(HiveConf.ConfVars.COMPACTOR_INITIATOR_FAILED_THRESHOLD);
    int failedRetention = conf.getIntVar(HiveConf.ConfVars.COMPACTOR_HISTORY_RETENTION_FAILED);
    if(failedRetention < failedThreshold) {
      LOG.warn("Invalid configuration " + HiveConf.ConfVars.COMPACTOR_INITIATOR_FAILED_THRESHOLD.varname +
        "=" + failedRetention + " < " + HiveConf.ConfVars.COMPACTOR_HISTORY_RETENTION_FAILED + "=" +
        failedRetention + ".  Will use " + HiveConf.ConfVars.COMPACTOR_INITIATOR_FAILED_THRESHOLD.varname +
        "=" + failedRetention);
      failedRetention = failedThreshold;
    }
    return failedRetention;
  }
  /**
   * Returns {@code true} if there already exists sufficient number of consecutive failures for
   * this table/partition so that no new automatic compactions will be scheduled.
   * User initiated compactions don't do this check.
   *
   * Do we allow compacting whole table (when it's partitioned)?  No, though perhaps we should.
   * That would be a meta operations, i.e. first find all partitions for this table (which have 
   * txn info) and schedule each compaction separately.  This avoids complications in this logic.
   */
  public boolean checkFailedCompactions(CompactionInfo ci) throws MetaException {
    Connection dbConn = null;
    Statement stmt = null;
    ResultSet rs = null;
    try {
      try {
        dbConn = getDbConn(Connection.TRANSACTION_READ_COMMITTED);
        stmt = dbConn.createStatement();
        rs = stmt.executeQuery("select CC_STATE from COMPLETED_COMPACTIONS where " +
          "CC_DATABASE = " + quoteString(ci.dbname) + " and " +
          "CC_TABLE = " + quoteString(ci.tableName) +
          (ci.partName != null ? "and CC_PARTITION = " + quoteString(ci.partName) : "") +
          " and CC_STATE != " + quoteChar(ATTEMPTED_STATE) + " order by CC_ID desc");
        int numFailed = 0;
        int numTotal = 0;
        int failedThreshold = conf.getIntVar(HiveConf.ConfVars.COMPACTOR_INITIATOR_FAILED_THRESHOLD);
        while(rs.next() && ++numTotal <= failedThreshold) {
          if(rs.getString(1).charAt(0) == FAILED_STATE) {
            numFailed++;
          }
          else {
            numFailed--;
          }
        }
        return numFailed == failedThreshold;
      }
      catch (SQLException e) {
        LOG.error("Unable to delete from compaction queue " + e.getMessage());
        LOG.debug("Going to rollback");
        rollbackDBConn(dbConn);
        checkRetryable(dbConn, e, "checkFailedCompactions(" + ci + ")");
        LOG.error("Unable to connect to transaction database " + StringUtils.stringifyException(e));
        return false;//weren't able to check
      } finally {
        close(rs, stmt, dbConn);
      }
    } catch (RetryException e) {
      return checkFailedCompactions(ci);
    }
  }
  /**
   * If there is an entry in compaction_queue with ci.id, remove it
   * Make entry in completed_compactions with status 'f'.
   * If there is no entry in compaction_queue, it means Initiator failed to even schedule a compaction,
   * which we record as ATTEMPTED_STATE entry in history.
   */
  public void markFailed(CompactionInfo ci) throws MetaException {//todo: this should not throw
    //todo: this should take "comment" as parameter to set in CC_META_INFO to provide some context for the failure
    try {
      Connection dbConn = null;
      Statement stmt = null;
      PreparedStatement pStmt = null;
      ResultSet rs = null;
      try {
        dbConn = getDbConn(Connection.TRANSACTION_READ_COMMITTED);
        stmt = dbConn.createStatement();
        rs = stmt.executeQuery("select CQ_ID, CQ_DATABASE, CQ_TABLE, CQ_PARTITION, CQ_STATE, CQ_TYPE, CQ_TBLPROPERTIES, CQ_WORKER_ID, CQ_START, CQ_RUN_AS, CQ_HIGHEST_TXN_ID, CQ_META_INFO, CQ_HADOOP_JOB_ID from COMPACTION_QUEUE WHERE CQ_ID = " + ci.id);
        if(rs.next()) {
          ci = CompactionInfo.loadFullFromCompactionQueue(rs);
          String s = "delete from COMPACTION_QUEUE where cq_id = " + ci.id;
          LOG.debug("Going to execute update <" + s + ">");
          int updCnt = stmt.executeUpdate(s);
        }
        else {
          if(ci.id > 0) {
            //the record with valid CQ_ID has disappeared - this is a sign of something wrong
            throw new IllegalStateException("No record with CQ_ID=" + ci.id + " found in COMPACTION_QUEUE");
          }
        }
        if(ci.id == 0) {
          //The failure occurred before we even made an entry in COMPACTION_QUEUE
          //generate ID so that we can make an entry in COMPLETED_COMPACTIONS
          ci.id = generateCompactionQueueId(stmt);
          //mostly this indicates that the Initiator is paying attention to some table even though
          //compactions are not happening.
          ci.state = ATTEMPTED_STATE;
          //this is not strictly accurate, but 'type' cannot be null.
          if(ci.type == null) { ci.type = CompactionType.MINOR; }
          ci.start = getDbTime(dbConn);
        }
        else {
          ci.state = FAILED_STATE;
        }
        close(rs, stmt, null);

        pStmt = dbConn.prepareStatement("insert into COMPLETED_COMPACTIONS(CC_ID, CC_DATABASE, CC_TABLE, CC_PARTITION, CC_STATE, CC_TYPE, CC_TBLPROPERTIES, CC_WORKER_ID, CC_START, CC_END, CC_RUN_AS, CC_HIGHEST_TXN_ID, CC_META_INFO, CC_HADOOP_JOB_ID) VALUES(?,?,?,?,?, ?,?,?,?,?, ?,?,?,?)");
        CompactionInfo.insertIntoCompletedCompactions(pStmt, ci, getDbTime(dbConn));
        int updCount = pStmt.executeUpdate();
        LOG.debug("Going to commit");
        closeStmt(pStmt);
        dbConn.commit();
      } catch (SQLException e) {
        LOG.warn("markFailed(" + ci.id + "):" + e.getMessage());
        LOG.debug("Going to rollback");
        rollbackDBConn(dbConn);
        try {
          checkRetryable(dbConn, e, "markFailed(" + ci + ")");
        }
        catch(MetaException ex) {
          LOG.error("Unable to connect to transaction database " + StringUtils.stringifyException(ex));
        }
        LOG.error("markFailed(" + ci + ") failed: " + e.getMessage(), e);
      } finally {
        close(rs, stmt, null);
        close(null, pStmt, dbConn);
      }
    } catch (RetryException e) {
      markFailed(ci);
    }
  }
  @Override
  public void setHadoopJobId(String hadoopJobId, long id) {
    try {
      Connection dbConn = null;
      Statement stmt = null;
      try {
        dbConn = getDbConn(Connection.TRANSACTION_READ_COMMITTED);
        stmt = dbConn.createStatement();
        String s = "update COMPACTION_QUEUE set CQ_HADOOP_JOB_ID = " + quoteString(hadoopJobId) + " WHERE CQ_ID = " + id;
        LOG.debug("Going to execute <" + s + ">");
        int updateCount = stmt.executeUpdate(s);
        LOG.debug("Going to commit");
        closeStmt(stmt);
        dbConn.commit();
      } catch (SQLException e) {
        LOG.warn("setHadoopJobId(" + hadoopJobId + "," + id + "):" + e.getMessage());
        LOG.debug("Going to rollback");
        rollbackDBConn(dbConn);
        try {
          checkRetryable(dbConn, e, "setHadoopJobId(" + hadoopJobId + "," + id + ")");
        }
        catch(MetaException ex) {
          LOG.error("Unable to connect to transaction database " + StringUtils.stringifyException(ex));
        }
        LOG.error("setHadoopJobId(" + hadoopJobId + "," + id + ") failed: " + e.getMessage(), e);
      } finally {
        close(null, stmt, dbConn);
      }
    } catch (RetryException e) {
      setHadoopJobId(hadoopJobId, id);
    }
  }
}


