/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.memory;

import com.facebook.presto.Session;
import com.facebook.presto.execution.TaskId;
import com.facebook.presto.execution.TaskStateMachine;
import com.facebook.presto.memory.context.MemoryReservationHandler;
import com.facebook.presto.memory.context.MemoryTrackingContext;
import com.facebook.presto.operator.TaskContext;
import com.facebook.presto.spi.QueryId;
import com.facebook.presto.spiller.SpillSpaceTracker;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.units.DataSize;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;

import static com.facebook.presto.ExceededMemoryLimitException.exceededLocalLimit;
import static com.facebook.presto.ExceededSpillLimitException.exceededPerQueryLocalLimit;
import static com.facebook.presto.memory.context.AggregatedMemoryContext.newRootAggregatedMemoryContext;
import static com.facebook.presto.operator.Operator.NOT_BLOCKED;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Verify.verify;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static io.airlift.units.DataSize.Unit.MEGABYTE;
import static io.airlift.units.DataSize.succinctBytes;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

@ThreadSafe
public class QueryContext
{
    private static final long GUARANTEED_MEMORY = new DataSize(1, MEGABYTE).toBytes();

    private final QueryId queryId;
    private final Executor notificationExecutor;
    private final ScheduledExecutorService yieldExecutor;
    private final long maxSpill;
    private final SpillSpaceTracker spillSpaceTracker;
    private final Map<TaskId, TaskContext> taskContexts = new ConcurrentHashMap();
    private final MemoryPool systemMemoryPool;

    // TODO: This field should be final. However, due to the way QueryContext is constructed the memory limit is not known in advance
    @GuardedBy("this")
    private long maxMemory;

    private final MemoryTrackingContext queryMemoryContext;

    @GuardedBy("this")
    private MemoryPool memoryPool;

    @GuardedBy("this")
    private long spillUsed;

    public QueryContext(
            QueryId queryId,
            DataSize maxMemory,
            MemoryPool memoryPool,
            MemoryPool systemMemoryPool,
            Executor notificationExecutor,
            ScheduledExecutorService yieldExecutor,
            DataSize maxSpill,
            SpillSpaceTracker spillSpaceTracker)
    {
        this.queryId = requireNonNull(queryId, "queryId is null");
        this.maxMemory = requireNonNull(maxMemory, "maxMemory is null").toBytes();
        this.memoryPool = requireNonNull(memoryPool, "memoryPool is null");
        this.systemMemoryPool = requireNonNull(systemMemoryPool, "systemMemoryPool is null");
        this.notificationExecutor = requireNonNull(notificationExecutor, "notificationExecutor is null");
        this.yieldExecutor = requireNonNull(yieldExecutor, "yieldExecutor is null");
        this.maxSpill = requireNonNull(maxSpill, "maxSpill is null").toBytes();
        this.spillSpaceTracker = requireNonNull(spillSpaceTracker, "spillSpaceTracker is null");
        this.queryMemoryContext = new MemoryTrackingContext(
                newRootAggregatedMemoryContext(new QueryMemoryReservationHandler(this::updateUserMemory, this::tryUpdateUserMemory), GUARANTEED_MEMORY),
                newRootAggregatedMemoryContext(new QueryMemoryReservationHandler(this::updateRevocableMemory, this::tryReserveMemoryNotSupported), 0L),
                newRootAggregatedMemoryContext(new QueryMemoryReservationHandler(this::updateSystemMemory, this::tryReserveMemoryNotSupported), 0L));
    }

    // TODO: This method should be removed, and the correct limit set in the constructor. However, due to the way QueryContext is constructed the memory limit is not known in advance
    public synchronized void setResourceOvercommit()
    {
        // Allow the query to use the entire pool. This way the worker will kill the query, if it uses the entire local general pool.
        // The coordinator will kill the query if the cluster runs out of memory.
        maxMemory = memoryPool.getMaxBytes();
    }

    @VisibleForTesting
    MemoryTrackingContext getQueryMemoryContext()
    {
        return queryMemoryContext;
    }

    private synchronized ListenableFuture<?> updateUserMemory(long delta)
    {
        if (delta >= 0) {
            if (queryMemoryContext.getUserMemory() + delta > maxMemory) {
                throw exceededLocalLimit(succinctBytes(maxMemory));
            }
            return memoryPool.reserve(queryId, delta);
        }
        memoryPool.free(queryId, -delta);
        return NOT_BLOCKED;
    }

    private synchronized ListenableFuture<?> updateRevocableMemory(long delta)
    {
        if (delta >= 0) {
            return memoryPool.reserveRevocable(queryId, delta);
        }
        memoryPool.freeRevocable(queryId, -delta);
        return NOT_BLOCKED;
    }

    private synchronized ListenableFuture<?> updateSystemMemory(long delta)
    {
        if (delta >= 0) {
            return systemMemoryPool.reserve(queryId, delta);
        }
        systemMemoryPool.free(queryId, -delta);
        return NOT_BLOCKED;
    }

    //TODO move spill tracking to the new memory tracking framework
    public synchronized ListenableFuture<?> reserveSpill(long bytes)
    {
        checkArgument(bytes >= 0, "bytes is negative");
        if (spillUsed + bytes > maxSpill) {
            throw exceededPerQueryLocalLimit(succinctBytes(maxSpill));
        }
        ListenableFuture<?> future = spillSpaceTracker.reserve(bytes);
        spillUsed += bytes;
        return future;
    }

    private synchronized boolean tryUpdateUserMemory(long delta)
    {
        if (delta <= 0) {
            ListenableFuture<?> future = updateUserMemory(delta);
            verify(future.isDone(), "future should be done");
            return true;
        }
        if (queryMemoryContext.getUserMemory() + delta > maxMemory) {
            return false;
        }
        return memoryPool.tryReserve(queryId, delta);
    }

    public synchronized void freeSpill(long bytes)
    {
        checkArgument(spillUsed - bytes >= 0, "tried to free more memory than is reserved");
        spillUsed -= bytes;
        spillSpaceTracker.free(bytes);
    }

    public synchronized void setMemoryPool(MemoryPool pool)
    {
        requireNonNull(pool, "pool is null");
        if (memoryPool == pool) {
            // Don't unblock our tasks and thrash the pools, if this is a no-op
            return;
        }
        MemoryPool originalPool = memoryPool;
        long originalReserved = queryMemoryContext.getUserMemory();
        long originalRevocableReserved = queryMemoryContext.getRevocableMemory();
        memoryPool = pool;
        ListenableFuture<?> future = pool.reserve(queryId, originalReserved);
        originalPool.free(queryId, originalReserved);
        pool.reserveRevocable(queryId, originalRevocableReserved);
        originalPool.freeRevocable(queryId, originalRevocableReserved);
        future.addListener(() -> {
            // Unblock all the tasks, if they were waiting for memory, since we're in a new pool.
            taskContexts.values().forEach(TaskContext::moreMemoryAvailable);
        }, directExecutor());
    }

    public synchronized MemoryPool getMemoryPool()
    {
        return memoryPool;
    }

    public TaskContext addTaskContext(TaskStateMachine taskStateMachine, Session session, boolean verboseStats, boolean cpuTimerEnabled)
    {
        TaskContext taskContext = TaskContext.createTaskContext(
                this,
                taskStateMachine,
                notificationExecutor,
                yieldExecutor,
                session,
                queryMemoryContext.newMemoryTrackingContext(),
                verboseStats,
                cpuTimerEnabled);
        taskContexts.put(taskStateMachine.getTaskId(), taskContext);
        return taskContext;
    }

    public <C, R> R accept(QueryContextVisitor<C, R> visitor, C context)
    {
        return visitor.visitQueryContext(this, context);
    }

    public <C, R> List<R> acceptChildren(QueryContextVisitor<C, R> visitor, C context)
    {
        return taskContexts.values()
                .stream()
                .map(taskContext -> taskContext.accept(visitor, context))
                .collect(toList());
    }

    public TaskContext getTaskContextByTaskId(TaskId taskId)
    {
        TaskContext taskContext = taskContexts.get(taskId);
        verify(taskContext != null, "task does not exist");
        return taskContext;
    }

    private static class QueryMemoryReservationHandler
            implements MemoryReservationHandler
    {
        private final LongFunction<ListenableFuture<?>> reserveMemoryFunction;
        private final LongPredicate tryReserveMemoryFunction;

        public QueryMemoryReservationHandler(LongFunction<ListenableFuture<?>> reserveMemoryFunction, LongPredicate tryReserveMemoryFunction)
        {
            this.reserveMemoryFunction = requireNonNull(reserveMemoryFunction, "reserveMemoryFunction is null");
            this.tryReserveMemoryFunction = requireNonNull(tryReserveMemoryFunction, "tryReserveMemoryFunction is null");
        }

        @Override
        public ListenableFuture<?> reserveMemory(long delta)
        {
            return reserveMemoryFunction.apply(delta);
        }

        @Override
        public boolean tryReserveMemory(long delta)
        {
            return tryReserveMemoryFunction.test(delta);
        }
    }

    private boolean tryReserveMemoryNotSupported(long bytes)
    {
        throw new UnsupportedOperationException("tryReserveMemory is not supported");
    }
}
