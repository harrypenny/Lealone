/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.cbase.dbobject.table;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.lealone.api.DatabaseEventListener;
import org.lealone.api.ErrorCode;
import org.lealone.cbase.dbobject.index.CBaseDelegateIndex;
import org.lealone.cbase.dbobject.index.CBaseIndex;
import org.lealone.cbase.dbobject.index.CBasePrimaryIndex;
import org.lealone.cbase.dbobject.index.CBaseSecondaryIndex;
import org.lealone.cbase.dbobject.index.HashIndex;
import org.lealone.cbase.dbobject.index.NonUniqueHashIndex;
import org.lealone.cbase.engine.CBaseStorageEngine;
import org.lealone.cbase.engine.CBaseStorageEngine.Store;
import org.lealone.command.ddl.Analyze;
import org.lealone.command.ddl.CreateTableData;
import org.lealone.dbobject.DbObject;
import org.lealone.dbobject.SchemaObject;
import org.lealone.dbobject.constraint.Constraint;
import org.lealone.dbobject.constraint.ConstraintReferential;
import org.lealone.dbobject.index.Cursor;
import org.lealone.dbobject.index.Index;
import org.lealone.dbobject.index.IndexType;
import org.lealone.dbobject.table.Column;
import org.lealone.dbobject.table.IndexColumn;
import org.lealone.dbobject.table.Table;
import org.lealone.dbobject.table.TableBase;
import org.lealone.engine.Constants;
import org.lealone.engine.Session;
import org.lealone.engine.SysProperties;
import org.lealone.message.DbException;
import org.lealone.message.Trace;
import org.lealone.result.Row;
import org.lealone.result.SortOrder;
import org.lealone.transaction.local.DefaultTransactionEngine;
import org.lealone.transaction.local.LocalTransaction;
import org.lealone.util.MathUtils;
import org.lealone.util.New;
import org.lealone.value.DataType;
import org.lealone.value.Value;

/**
 * A table stored in a MVStore.
 */
public class CBaseTable extends TableBase {

    private CBasePrimaryIndex primaryIndex;
    private final ArrayList<Index> indexes = New.arrayList();
    private long lastModificationId;
    private volatile Session lockExclusiveSession;

    // using a ConcurrentHashMap as a set
    private final ConcurrentHashMap<Session, Session> lockSharedSessions = new ConcurrentHashMap<Session, Session>();

    /**
     * The queue of sessions waiting to lock the table. It is a FIFO queue to
     * prevent starvation, since Java's synchronized locking is biased.
     */
    private final ArrayDeque<Session> waitingSessions = new ArrayDeque<Session>();
    private final Trace traceLock;
    private int changesSinceAnalyze;
    private int nextAnalyze;
    private boolean containsLargeObject;
    private Column rowIdColumn;

    private final DefaultTransactionEngine transactionEngine;

    public CBaseTable(CreateTableData data, CBaseStorageEngine.Store store) {
        super(data);
        nextAnalyze = database.getSettings().analyzeAuto;
        this.transactionEngine = store.getTransactionEngine();
        this.isHidden = data.isHidden;
        for (Column col : getColumns()) {
            if (DataType.isLargeObject(col.getType())) {
                containsLargeObject = true;
            }
        }
        traceLock = database.getTrace(Trace.LOCK);
    }

    /**
     * Initialize the table.
     *
     * @param session the session
     */
    public void init(Session session) {
        primaryIndex = new CBasePrimaryIndex(session, this, getId(), IndexColumn.wrap(getColumns()),
                IndexType.createScan(true));
        indexes.add(primaryIndex);
    }

    public String getMapName() {
        return primaryIndex.getMapName();
    }

    @Override
    public boolean lock(Session session, boolean exclusive, boolean forceLockEvenInMvcc) {
        int lockMode = database.getLockMode();
        if (lockMode == Constants.LOCK_MODE_OFF) {
            return false;
        }
        if (!forceLockEvenInMvcc && database.isMultiVersion()) {
            // MVCC: update, delete, and insert use a shared lock.
            // Select doesn't lock except when using FOR UPDATE and
            // the system property h2.selectForUpdateMvcc
            // is not enabled
            if (exclusive) {
                exclusive = false;
            } else {
                if (lockExclusiveSession == null) {
                    return false;
                }
            }
        }
        if (lockExclusiveSession == session) {
            return true;
        }
        if (!exclusive && lockSharedSessions.contains(session)) {
            return true;
        }
        synchronized (getLockSyncObject()) {
            if (!exclusive && lockSharedSessions.contains(session)) {
                return true;
            }
            session.setWaitForLock(this, Thread.currentThread());
            waitingSessions.addLast(session);
            try {
                doLock1(session, lockMode, exclusive);
            } finally {
                session.setWaitForLock(null, null);
                waitingSessions.remove(session);
            }
        }
        return false;
    }

    /**
     * The the object on which to synchronize and wait on. For the
     * multi-threaded mode, this is this object, but for non-multi-threaded, it
     * is the database, as in this case all operations are synchronized on the
     * database object.
     *
     * @return the lock sync object
     */
    private Object getLockSyncObject() {
        if (database.isMultiThreaded()) {
            return this;
        }
        return database;
    }

    private void doLock1(Session session, int lockMode, boolean exclusive) {
        traceLock(session, exclusive, "requesting for");
        // don't get the current time unless necessary
        long max = 0;
        boolean checkDeadlock = false;
        while (true) {
            // if I'm the next one in the queue
            if (waitingSessions.getFirst() == session) {
                if (doLock2(session, lockMode, exclusive)) {
                    return;
                }
            }
            if (checkDeadlock) {
                ArrayList<Session> sessions = checkDeadlock(session, null, null);
                if (sessions != null) {
                    throw DbException.get(ErrorCode.DEADLOCK_1, getDeadlockDetails(sessions, exclusive));
                }
            } else {
                // check for deadlocks from now on
                checkDeadlock = true;
            }
            long now = System.currentTimeMillis();
            if (max == 0) {
                // try at least one more time
                max = now + session.getLockTimeout();
            } else if (now >= max) {
                traceLock(session, exclusive, "timeout after " + session.getLockTimeout());
                throw DbException.get(ErrorCode.LOCK_TIMEOUT_1, getName());
            }
            try {
                traceLock(session, exclusive, "waiting for");
                if (database.getLockMode() == Constants.LOCK_MODE_TABLE_GC) {
                    for (int i = 0; i < 20; i++) {
                        long free = Runtime.getRuntime().freeMemory();
                        System.gc();
                        long free2 = Runtime.getRuntime().freeMemory();
                        if (free == free2) {
                            break;
                        }
                    }
                }
                // don't wait too long so that deadlocks are detected early
                long sleep = Math.min(Constants.DEADLOCK_CHECK, max - now);
                if (sleep == 0) {
                    sleep = 1;
                }
                getLockSyncObject().wait(sleep);
            } catch (InterruptedException e) {
                // ignore
            }
        }
    }

    private boolean doLock2(Session session, int lockMode, boolean exclusive) {
        if (exclusive) {
            if (lockExclusiveSession == null) {
                if (lockSharedSessions.isEmpty()) {
                    traceLock(session, exclusive, "added for");
                    session.addLock(this);
                    lockExclusiveSession = session;
                    return true;
                } else if (lockSharedSessions.size() == 1 && lockSharedSessions.contains(session)) {
                    traceLock(session, exclusive, "add (upgraded) for ");
                    lockExclusiveSession = session;
                    return true;
                }
            }
        } else {
            if (lockExclusiveSession == null) {
                if (lockMode == Constants.LOCK_MODE_READ_COMMITTED) {
                    if (!database.isMultiThreaded() && !database.isMultiVersion()) {
                        // READ_COMMITTED: a read lock is acquired,
                        // but released immediately after the operation
                        // is complete.
                        // When allowing only one thread, no lock is
                        // required.
                        // Row level locks work like read committed.
                        return true;
                    }
                }
                if (!lockSharedSessions.contains(session)) {
                    traceLock(session, exclusive, "ok");
                    session.addLock(this);
                    lockSharedSessions.put(session, session);
                }
                return true;
            }
        }
        return false;
    }

    private static String getDeadlockDetails(ArrayList<Session> sessions, boolean exclusive) {
        // We add the thread details here to make it easier for customers to
        // match up these error messages with their own logs.
        StringBuilder buff = new StringBuilder();
        for (Session s : sessions) {
            Table lock = s.getWaitForLock();
            Thread thread = s.getWaitForLockThread();
            buff.append("\nSession ").append(s.toString()).append(" on thread ").append(thread.getName())
                    .append(" is waiting to lock ").append(lock.toString())
                    .append(exclusive ? " (exclusive)" : " (shared)").append(" while locking ");
            int i = 0;
            for (Table t : s.getLocks()) {
                if (i++ > 0) {
                    buff.append(", ");
                }
                buff.append(t.toString());
                if (t instanceof CBaseTable) {
                    if (((CBaseTable) t).lockExclusiveSession == s) {
                        buff.append(" (exclusive)");
                    } else {
                        buff.append(" (shared)");
                    }
                }
            }
            buff.append('.');
        }
        return buff.toString();
    }

    @Override
    public ArrayList<Session> checkDeadlock(Session session, Session clash, Set<Session> visited) {
        // only one deadlock check at any given time
        synchronized (CBaseTable.class) {
            if (clash == null) {
                // verification is started
                clash = session;
                visited = New.hashSet();
            } else if (clash == session) {
                // we found a circle where this session is involved
                return New.arrayList();
            } else if (visited.contains(session)) {
                // we have already checked this session.
                // there is a circle, but the sessions in the circle need to
                // find it out themselves
                return null;
            }
            visited.add(session);
            ArrayList<Session> error = null;
            for (Session s : lockSharedSessions.keySet()) {
                if (s == session) {
                    // it doesn't matter if we have locked the object already
                    continue;
                }
                Table t = s.getWaitForLock();
                if (t != null) {
                    error = t.checkDeadlock(s, clash, visited);
                    if (error != null) {
                        error.add(session);
                        break;
                    }
                }
            }
            if (error == null && lockExclusiveSession != null) {
                Table t = lockExclusiveSession.getWaitForLock();
                if (t != null) {
                    error = t.checkDeadlock(lockExclusiveSession, clash, visited);
                    if (error != null) {
                        error.add(session);
                    }
                }
            }
            return error;
        }
    }

    private void traceLock(Session session, boolean exclusive, String s) {
        if (traceLock.isDebugEnabled()) {
            traceLock.debug("{0} {1} {2} {3}", session.getId(),
                    exclusive ? "exclusive write lock" : "shared read lock", s, getName());
        }
    }

    @Override
    public boolean isLockedExclusively() {
        return lockExclusiveSession != null;
    }

    @Override
    public boolean isLockedExclusivelyBy(Session session) {
        return lockExclusiveSession == session;
    }

    @Override
    public void unlock(Session s) {
        if (database != null) {
            traceLock(s, lockExclusiveSession == s, "unlock");
            if (lockExclusiveSession == s) {
                lockExclusiveSession = null;
            }
            synchronized (getLockSyncObject()) {
                if (lockSharedSessions.size() > 0) {
                    lockSharedSessions.remove(s);
                }
                if (!waitingSessions.isEmpty()) {
                    getLockSyncObject().notifyAll();
                }
            }
        }
    }

    @Override
    public boolean canTruncate() {
        if (getCheckForeignKeyConstraints() && database.getReferentialIntegrity()) {
            ArrayList<Constraint> constraints = getConstraints();
            if (constraints != null) {
                for (int i = 0, size = constraints.size(); i < size; i++) {
                    Constraint c = constraints.get(i);
                    if (!(c.getConstraintType().equals(Constraint.REFERENTIAL))) {
                        continue;
                    }
                    ConstraintReferential ref = (ConstraintReferential) c;
                    if (ref.getRefTable() == this) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    @Override
    public void close(Session session) {
        // ignore
    }

    @Override
    public Row getRow(Session session, long key) {
        return primaryIndex.getRow(session, key);
    }

    @Override
    public Index addIndex(Session session, String indexName, int indexId, IndexColumn[] cols, IndexType indexType,
            boolean create, String indexComment) {
        if (indexType.isPrimaryKey()) {
            for (IndexColumn c : cols) {
                Column column = c.column;
                if (column.isNullable()) {
                    throw DbException.get(ErrorCode.COLUMN_MUST_NOT_BE_NULLABLE_1, column.getName());
                }
                column.setPrimaryKey(true);
            }
        }
        boolean isSessionTemporary = isTemporary() && !isGlobalTemporary();
        if (!isSessionTemporary) {
            database.lockMeta(session);
        }
        Index index;
        // TODO support in-memory indexes
        // if (isPersistIndexes() && indexType.isPersistent()) {
        int mainIndexColumn;
        mainIndexColumn = getMainIndexColumn(indexType, cols);
        if (database.isStarting()) {
            if (transactionEngine.store.hasMap("index." + indexId)) {
                mainIndexColumn = -1;
            }
        } else if (primaryIndex.getRowCountMax() != 0) {
            mainIndexColumn = -1;
        }
        if (mainIndexColumn != -1) {
            primaryIndex.setMainIndexColumn(mainIndexColumn);
            index = new CBaseDelegateIndex(this, indexId, indexName, primaryIndex, indexType);
        } else if (indexType.isHash() && cols.length <= 1) { //TODO 是否要支持多版本
            if (indexType.isUnique()) {
                index = new HashIndex(this, indexId, indexName, cols, indexType);
            } else {
                index = new NonUniqueHashIndex(this, indexId, indexName, cols, indexType);
            }
        } else {
            index = new CBaseSecondaryIndex(session, this, indexId, indexName, cols, indexType);
        }
        if (index instanceof CBaseIndex && index.needRebuild()) {
            rebuildIndex(session, (CBaseIndex) index, indexName);
        }
        index.setTemporary(isTemporary());
        if (index.getCreateSQL() != null) {
            index.setComment(indexComment);
            if (isSessionTemporary) {
                session.addLocalTempTableIndex(index);
            } else {
                database.addSchemaObject(session, index);
            }
        }
        indexes.add(index);
        setModified();
        return index;
    }

    private void rebuildIndex(Session session, CBaseIndex index, String indexName) {
        try {
            if (CBaseStorageEngine.getStore(session) == null) {
                // in-memory
                rebuildIndexBuffered(session, index);
            } else {
                rebuildIndexBlockMerge(session, index);
            }
        } catch (DbException e) {
            getSchema().freeUniqueName(indexName);
            try {
                index.remove(session);
            } catch (DbException e2) {
                // this could happen, for example on failure in the storage
                // but if that is not the case it means
                // there is something wrong with the database
                trace.error(e2, "could not remove index");
                throw e2;
            }
            throw e;
        }
    }

    private void rebuildIndexBlockMerge(Session session, CBaseIndex index) {
        // Read entries in memory, sort them, write to a new map (in sorted
        // order); repeat (using a new map for every block of 1 MB) until all
        // record are read. Merge all maps to the target (using merge sort;
        // duplicates are detected in the target). For randomly ordered data,
        // this should use relatively few write operations.
        // A possible optimization is: change the buffer size from "row count"
        // to "amount of memory", and buffer index keys instead of rows.
        Index scan = getScanIndex(session);
        long remaining = scan.getRowCount(session);
        long total = remaining;
        Cursor cursor = scan.find(session, null, null);
        long i = 0;
        Store store = CBaseStorageEngine.getStore(session);

        int bufferSize = database.getMaxMemoryRows() / 2;
        ArrayList<Row> buffer = New.arrayList(bufferSize);
        String n = getName() + ":" + index.getName();
        int t = MathUtils.convertLongToInt(total);
        ArrayList<String> bufferNames = New.arrayList();
        while (cursor.next()) {
            Row row = cursor.get();
            buffer.add(row);
            database.setProgress(DatabaseEventListener.STATE_CREATE_INDEX, n, MathUtils.convertLongToInt(i++), t);
            if (buffer.size() >= bufferSize) {
                sortRows(buffer, index);
                String mapName = store.nextTemporaryMapName();
                index.addRowsToBuffer(buffer, mapName);
                bufferNames.add(mapName);
                buffer.clear();
            }
            remaining--;
        }
        sortRows(buffer, index);
        if (bufferNames.size() > 0) {
            String mapName = store.nextTemporaryMapName();
            index.addRowsToBuffer(buffer, mapName);
            bufferNames.add(mapName);
            buffer.clear();
            index.addBufferedRows(bufferNames);
        } else {
            addRowsToIndex(session, buffer, index);
        }
        if (SysProperties.CHECK && remaining != 0) {
            DbException.throwInternalError("rowcount remaining=" + remaining + " " + getName());
        }
    }

    private void rebuildIndexBuffered(Session session, Index index) {
        Index scan = getScanIndex(session);
        long remaining = scan.getRowCount(session);
        long total = remaining;
        Cursor cursor = scan.find(session, null, null);
        long i = 0;
        int bufferSize = (int) Math.min(total, database.getMaxMemoryRows());
        ArrayList<Row> buffer = New.arrayList(bufferSize);
        String n = getName() + ":" + index.getName();
        int t = MathUtils.convertLongToInt(total);
        while (cursor.next()) {
            Row row = cursor.get();
            buffer.add(row);
            database.setProgress(DatabaseEventListener.STATE_CREATE_INDEX, n, MathUtils.convertLongToInt(i++), t);
            if (buffer.size() >= bufferSize) {
                addRowsToIndex(session, buffer, index);
            }
            remaining--;
        }
        addRowsToIndex(session, buffer, index);
        if (SysProperties.CHECK && remaining != 0) {
            DbException.throwInternalError("rowcount remaining=" + remaining + " " + getName());
        }
    }

    private int getMainIndexColumn(IndexType indexType, IndexColumn[] cols) {
        if (primaryIndex.getMainIndexColumn() != -1) {
            return -1;
        }
        if (!indexType.isPrimaryKey() || cols.length != 1) {
            return -1;
        }
        IndexColumn first = cols[0];
        if (first.sortType != SortOrder.ASCENDING) {
            return -1;
        }
        switch (first.column.getType()) {
        case Value.BYTE:
        case Value.SHORT:
        case Value.INT:
        case Value.LONG:
            break;
        default:
            return -1;
        }
        return first.column.getColumnId();
    }

    private static void addRowsToIndex(Session session, ArrayList<Row> list, Index index) {
        sortRows(list, index);
        for (Row row : list) {
            index.add(session, row);
        }
        list.clear();
    }

    private static void sortRows(ArrayList<Row> list, final Index index) {
        Collections.sort(list, new Comparator<Row>() {
            @Override
            public int compare(Row r1, Row r2) {
                return index.compareRows(r1, r2);
            }
        });
    }

    @Override
    public void removeRow(Session session, Row row) {
        lastModificationId = database.getNextModificationDataId();
        LocalTransaction t = getTransaction(session);
        long savepoint = t.setSavepoint();
        try {
            for (int i = indexes.size() - 1; i >= 0; i--) {
                Index index = indexes.get(i);
                index.remove(session, row);
            }
        } catch (Throwable e) {
            t.rollbackToSavepoint(savepoint);
            throw DbException.convert(e);
        }
        analyzeIfRequired(session);
    }

    @Override
    public void truncate(Session session) {
        lastModificationId = database.getNextModificationDataId();
        for (int i = indexes.size() - 1; i >= 0; i--) {
            Index index = indexes.get(i);
            index.truncate(session);
        }
        changesSinceAnalyze = 0;
    }

    @Override
    public void addRow(Session session, Row row) {
        lastModificationId = database.getNextModificationDataId();
        LocalTransaction t = getTransaction(session);
        long savepoint = t.setSavepoint();
        try {
            for (int i = 0, size = indexes.size(); i < size; i++) {
                Index index = indexes.get(i);
                index.add(session, row);
            }
        } catch (Throwable e) {
            t.rollbackToSavepoint(savepoint);
            DbException de = DbException.convert(e);
            //            if (de.getErrorCode() == ErrorCode.DUPLICATE_KEY_1) {
            //                for (int j = 0; j < indexes.size(); j++) {
            //                    Index index = indexes.get(j);
            //                    if (index.getIndexType().isUnique() && index instanceof MultiVersionIndex) {
            //                        MultiVersionIndex mv = (MultiVersionIndex) index;
            //                        if (mv.isUncommittedFromOtherSession(session, row)) {
            //                            throw DbException.get(ErrorCode.CONCURRENT_UPDATE_1, index.getName());
            //                        }
            //                    }
            //                }
            //            }
            throw de;
        }
        analyzeIfRequired(session);
    }

    @Override
    protected void analyzeIfRequired(Session session) {
        if (nextAnalyze == 0 || nextAnalyze > changesSinceAnalyze++) {
            return;
        }
        changesSinceAnalyze = 0;
        int n = 2 * nextAnalyze;
        if (n > 0) {
            nextAnalyze = n;
        }
        int rows = session.getDatabase().getSettings().analyzeSample / 10;
        Analyze.analyzeTable(session, this, rows, false);
    }

    @Override
    public void checkSupportAlter() {
        // ok
    }

    @Override
    public String getTableType() {
        return Table.TABLE;
    }

    @Override
    public Index getScanIndex(Session session) {
        return primaryIndex;
    }

    @Override
    public Index getUniqueIndex() {
        return primaryIndex;
    }

    @Override
    public ArrayList<Index> getIndexes() {
        return indexes;
    }

    @Override
    public long getMaxDataModificationId() {
        return lastModificationId;
    }

    @Override
    public boolean getContainsLargeObject() {
        return containsLargeObject;
    }

    @Override
    public boolean isDeterministic() {
        return true;
    }

    @Override
    public boolean canGetRowCount() {
        return true;
    }

    @Override
    public boolean canDrop() {
        return true;
    }

    @Override
    public void removeChildrenAndResources(Session session) {
        if (containsLargeObject) {
            // unfortunately, the data is gone on rollback
            truncate(session);
            database.getLobStorage().removeAllForTable(getId());
            database.lockMeta(session);
        }
        //database.getMvStore().removeTable(this);
        CBaseStorageEngine.getStore(session).removeTable(this);
        super.removeChildrenAndResources(session);
        // go backwards because database.removeIndex will
        // call table.removeIndex
        while (indexes.size() > 1) {
            Index index = indexes.get(1);
            if (index.getName() != null) {
                database.removeSchemaObject(session, index);
            }
            // needed for session temporary indexes
            indexes.remove(index);
        }
        if (SysProperties.CHECK) {
            for (SchemaObject obj : database.getAllSchemaObjects(DbObject.INDEX)) {
                Index index = (Index) obj;
                if (index.getTable() == this) {
                    DbException.throwInternalError("index not dropped: " + index.getName());
                }
            }
        }
        primaryIndex.remove(session);
        database.removeMeta(session, getId());
        close(session);
        invalidate();
    }

    @Override
    public long getRowCount(Session session) {
        return primaryIndex.getRowCount(session);
    }

    @Override
    public long getRowCountApproximation() {
        return primaryIndex.getRowCountApproximation();
    }

    @Override
    public long getDiskSpaceUsed() {
        return primaryIndex.getDiskSpaceUsed();
    }

    @Override
    public void checkRename() {
        // ok
    }

    /**
     * Get the transaction to use for this session.
     *
     * @param session the session
     * @return the transaction
     */
    public LocalTransaction getTransaction(Session session) {
        return (LocalTransaction) session.getTransaction();
    }

    @Override
    public Column getRowIdColumn() {
        if (rowIdColumn == null) {
            rowIdColumn = new Column(Column.ROWID, Value.LONG);
            rowIdColumn.setTable(this, -1);
        }
        return rowIdColumn;
    }

    @Override
    public String toString() {
        return getSQL();
    }

    @Override
    public boolean isMVStore() {
        return true;
    }

    /**
     * Mark the transaction as committed, so that the modification counter of
     * the database is incremented.
     */
    public void commit() {
        if (database != null) {
            lastModificationId = database.getNextModificationDataId();
        }
    }

}
